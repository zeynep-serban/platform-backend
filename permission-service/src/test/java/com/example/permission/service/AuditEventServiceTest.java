package com.example.permission.service;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationActionPredicate;
import com.example.permission.audit.ImpersonationAuditEventTypes;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.dto.v1.AuditExportJobResponseDto;
import com.example.permission.model.AuditExportJob;
import com.example.permission.dto.v1.AuditEventIngestRequestDto;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.model.UserAuditEventMirror;
import com.example.permission.repository.AuditExportJobRepository;
import com.example.permission.repository.PermissionAuditEventRepository;
import com.example.permission.repository.UserAuditEventMirrorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    @Mock
    private PermissionAuditEventRepository repository;

    @Mock
    private AuditExportJobRepository auditExportJobRepository;

    @Mock
    private UserAuditEventMirrorRepository userAuditEventMirrorRepository;

    @Mock
    private AuditEventStream auditEventStream;

    private AuditEventService auditEventService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        auditEventService = new AuditEventService(repository, auditExportJobRepository, userAuditEventMirrorRepository, objectMapper, auditEventStream, true);
    }

    @Test
    void listEvents_masksSensitiveFields() throws Exception {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setId(42L);
        event.setOccurredAt(Instant.parse("2025-01-01T00:00:00Z"));
        event.setUserEmail("alice@example.com");
        event.setService("permission-service");
        event.setLevel("INFO");
        event.setAction("ASSIGN_ROLE");
        event.setCorrelationId("corr-1");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", 10);
        metadata.put("email", "alice@example.com");
        metadata.put("contactName", "Alice Adams");
        metadata.put("nested", Map.of("phoneNumber", "5551234567"));
        event.setMetadata(objectMapper.writeValueAsString(metadata));
        event.setBeforeState(objectMapper.writeValueAsString(Map.of("name", "Bob Builder")));
        event.setAfterState(objectMapper.writeValueAsString(Map.of("addressLine", "Secret Street 123")));

        when(repository.findAll()).thenReturn(List.of(event));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        AuditEventPageResponse response = auditEventService.listEvents(0, 50, null, Map.of());
        assertThat(response.events()).hasSize(1);

        AuditEventResponse masked = response.events().get(0);
        assertThat(masked.userEmail()).isEqualTo("a***@example.com");
        assertThat(masked.metadata().get("email")).isEqualTo("a***@example.com");
        assertThat(masked.metadata().get("contactName")).isEqualTo("A***");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) masked.metadata().get("nested");
        assertThat(nested.get("phoneNumber")).isEqualTo("5***");

        assertThat(masked.before().get("name")).isEqualTo("B***");
        assertThat(masked.after().get("addressLine")).isEqualTo("S***");
    }

    @Test
    void listEvents_mergesUserAuditEntries() {
        PermissionAuditEvent permissionEvent = new PermissionAuditEvent();
        permissionEvent.setId(7L);
        permissionEvent.setOccurredAt(Instant.parse("2025-01-01T00:00:00Z"));
        permissionEvent.setService("permission-service");
        permissionEvent.setLevel("INFO");
        permissionEvent.setAction("ROLE_UPDATED");
        permissionEvent.setDetails("Role updated");

        UserAuditEventMirror userEvent = new UserAuditEventMirror();
        userEvent.setId(9L);
        userEvent.setEventType("USER_ACTIVATE");
        userEvent.setPerformedBy(1L);
        userEvent.setTargetUserId(99L);
        userEvent.setDetails("User activated");
        userEvent.setOccurredAt(java.time.LocalDateTime.of(2025, 1, 2, 10, 0));

        when(repository.findAll()).thenReturn(List.of(permissionEvent));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of(userEvent));

        AuditEventPageResponse response = auditEventService.listEvents(0, 50, null, Map.of());

        assertThat(response.events()).hasSize(2);
        assertThat(response.events().getFirst().id()).isEqualTo("user-9");
        assertThat(response.events().getFirst().service()).isEqualTo("user-service");
        assertThat(response.events().getFirst().action()).isEqualTo("USER_ACTIVATE");
        assertThat(response.events().get(1).id()).isEqualTo("7");
    }

    @Test
    void recordMirroredEvent_persistsExternalServiceAuditIntoCentralFeed() {
        AuditEventIngestRequestDto request = new AuditEventIngestRequestDto();
        request.setEventType("SESSION_CREATED");
        request.setPerformedBy(99L);
        request.setUserEmail("user@example.com");
        request.setService("auth-service");
        request.setLevel("INFO");
        request.setAction("SESSION_CREATED");
        request.setDetails("Session created");
        request.setCorrelationId("corr-auth-1");
        request.setMetadata(Map.of("companyId", 42L, "permissionCount", 3));
        request.setOccurredAt(Instant.parse("2026-03-13T20:00:00Z"));

        when(repository.save(any(PermissionAuditEvent.class))).thenAnswer(invocation -> {
            PermissionAuditEvent event = invocation.getArgument(0);
            event.setId(77L);
            return event;
        });

        PermissionAuditEvent saved = auditEventService.recordMirroredEvent(request);

        assertThat(saved.getId()).isEqualTo(77L);
        assertThat(saved.getService()).isEqualTo("auth-service");
        assertThat(saved.getAction()).isEqualTo("SESSION_CREATED");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-auth-1");
        assertThat(saved.getMetadata()).contains("permissionCount");
        verify(repository).save(any(PermissionAuditEvent.class));
    }

    /**
     * Codex iter-1 absorb on PR #87 — service-level proof that a
     * mirrored event with {@code performedBy=null} actually reaches
     * the JPA save() with null preserved (the controller-slice test
     * only proved the validation pass, not the storage path).
     *
     * <p>Closes the live audit-trail gap on testai where every
     * {@code REPORT_ACCESS} event from a UUID-actor admin was being
     * rejected at validation; with this PR they now persist with
     * NULL performedBy + actor metadata captured upstream by
     * report-service's ReportAuditClient.
     */
    @Test
    void recordMirroredEvent_persistsNullPerformedBy_whenActorIsKeycloakUuid() {
        AuditEventIngestRequestDto request = new AuditEventIngestRequestDto();
        request.setEventType("REPORT_ACCESS");
        // performedBy intentionally not set — UUID-only actor case.
        request.setUserEmail("admin@example.com");
        request.setService("report-service");
        request.setLevel("INFO");
        request.setAction("REPORT_ACCESS");
        request.setDetails("fin-muhasebe-detay report accessed");
        request.setCorrelationId("trace-uuid-1");
        request.setMetadata(Map.of(
                "reportKey", "fin-muhasebe-detay",
                "actorIdentifier", "3520324b-3035-4510-8fca-a8a18dbd1da2",
                "actorKind", "keycloak_sub"));
        request.setOccurredAt(Instant.parse("2026-05-07T05:35:32Z"));

        when(repository.save(any(PermissionAuditEvent.class))).thenAnswer(invocation -> {
            PermissionAuditEvent event = invocation.getArgument(0);
            event.setId(78L);
            return event;
        });

        PermissionAuditEvent saved = auditEventService.recordMirroredEvent(request);

        assertThat(saved.getId()).isEqualTo(78L);
        assertThat(saved.getPerformedBy()).isNull();
        assertThat(saved.getUserEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getCorrelationId()).isEqualTo("trace-uuid-1");
        // actor identity survives in metadata even though performedBy is null.
        assertThat(saved.getMetadata()).contains("actorIdentifier");
        assertThat(saved.getMetadata()).contains("3520324b-3035-4510-8fca-a8a18dbd1da2");
        assertThat(saved.getMetadata()).contains("keycloak_sub");
        verify(repository).save(argThat(event -> event.getPerformedBy() == null));
    }

    @Test
    void createExportJob_completesAndReturnsDownloadPath() {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setId(42L);
        event.setOccurredAt(Instant.parse("2025-01-01T00:00:00Z"));
        event.setService("permission-service");
        event.setLevel("INFO");
        event.setAction("ASSIGN_ROLE");
        event.setDetails("Role assigned");

        when(repository.findAll()).thenReturn(List.of(event));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());
        when(auditExportJobRepository.save(any(AuditExportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditExportJobResponseDto response = auditEventService.createExportJob(
                "admin@example.com",
                "json",
                50,
                "timestamp,desc",
                Map.of("service", "permission-service")
        );

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.downloadPath()).contains("/api/audit/events/export-jobs/");
        assertThat(response.filename()).endsWith(".json");
        assertThat(response.eventCount()).isEqualTo(1);
        verify(auditExportJobRepository, org.mockito.Mockito.atLeastOnce()).save(any(AuditExportJob.class));
    }

    // =====================================================================
    // PR-D2 (User Impersonation v1): scope-aware list / find / live stream
    // Codex peer review thread 019e10bf iter-2 AGREE WITH AMENDMENTS.
    // =====================================================================

    private static PermissionAuditEvent eventWithAction(long id, String action) {
        PermissionAuditEvent e = new PermissionAuditEvent();
        e.setId(id);
        e.setOccurredAt(Instant.parse("2026-05-09T10:00:00Z").plusSeconds(id));
        e.setUserEmail("admin@example.com");
        e.setService("permission-service");
        e.setLevel("INFO");
        e.setAction(action);
        e.setDetails(action + " details");
        e.setCorrelationId("corr-" + id);
        return e;
    }

    @Test
    void listEvents_genericScope_excludesImpersonationActions() {
        when(repository.findAll()).thenReturn(List.of(
                eventWithAction(1L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED),
                eventWithAction(2L, "ASSIGN_ROLE"),
                // KEY case: NON_IMPERSONATION_* should NOT be excluded by the
                // generic feed — the previous substring matcher ate this row.
                eventWithAction(3L, "NON_IMPERSONATION_STARTED"),
                eventWithAction(4L, ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED),
                eventWithAction(5L, "REPORT_ACCESS")
        ));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        AuditEventPageResponse resp = auditEventService.listEvents(
                0, 50, null, Map.of(), AuditReadScope.GENERIC_AUDIT);

        assertThat(resp.events()).extracting(AuditEventResponse::action)
                .containsExactlyInAnyOrder("ASSIGN_ROLE", "NON_IMPERSONATION_STARTED", "REPORT_ACCESS");
        assertThat(resp.events()).extracting(AuditEventResponse::action)
                .doesNotContain(ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
                        ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED);
    }

    @Test
    void listEvents_impersonationScope_returnsOnlyImpersonationActions() {
        when(repository.findAll()).thenReturn(List.of(
                eventWithAction(1L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED),
                eventWithAction(2L, ImpersonationAuditEventTypes.IMPERSONATION_STOPPED),
                eventWithAction(3L, ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED),
                eventWithAction(4L, ImpersonationAuditEventTypes.IMPERSONATION_FAILED),
                eventWithAction(5L, ImpersonationAuditEventTypes.IMPERSONATION_REVOKED),
                eventWithAction(6L, "ASSIGN_ROLE"),
                eventWithAction(7L, "NON_IMPERSONATION_STARTED")
        ));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        AuditEventPageResponse resp = auditEventService.listEvents(
                0, 50, null, Map.of(), AuditReadScope.IMPERSONATION_AUDIT);

        assertThat(resp.events()).hasSize(5);
        assertThat(resp.events()).extracting(AuditEventResponse::action)
                .containsExactlyInAnyOrder(
                        ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
                        ImpersonationAuditEventTypes.IMPERSONATION_STOPPED,
                        ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED,
                        ImpersonationAuditEventTypes.IMPERSONATION_FAILED,
                        ImpersonationAuditEventTypes.IMPERSONATION_REVOKED);
    }

    @Test
    void listEvents_impersonationScope_specificActionFilter_narrows() {
        when(repository.findAll()).thenReturn(List.of(
                eventWithAction(1L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED),
                eventWithAction(2L, ImpersonationAuditEventTypes.IMPERSONATION_FAILED)
        ));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        AuditEventPageResponse resp = auditEventService.listEvents(
                0, 50, null,
                Map.of("action", ImpersonationAuditEventTypes.IMPERSONATION_FAILED),
                AuditReadScope.IMPERSONATION_AUDIT);

        assertThat(resp.events()).hasSize(1);
        assertThat(resp.events().get(0).action()).isEqualTo(ImpersonationAuditEventTypes.IMPERSONATION_FAILED);
    }

    @Test
    void listEvents_impersonationScope_aliasIsNoNarrowing() {
        when(repository.findAll()).thenReturn(List.of(
                eventWithAction(1L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED),
                eventWithAction(2L, ImpersonationAuditEventTypes.IMPERSONATION_FAILED),
                eventWithAction(3L, ImpersonationAuditEventTypes.IMPERSONATION_REVOKED)
        ));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        AuditEventPageResponse resp = auditEventService.listEvents(
                0, 50, null,
                Map.of("action", ImpersonationActionPredicate.ALIAS_ALL),
                AuditReadScope.IMPERSONATION_AUDIT);

        assertThat(resp.events()).hasSize(3);
    }

    @Test
    void findByIdPage_genericScope_impersonationEvent_returns404() {
        PermissionAuditEvent imp = eventWithAction(42L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED);
        when(repository.findById(42L)).thenReturn(Optional.of(imp));

        assertThatThrownBy(() ->
                auditEventService.findByIdPage("42", AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Audit event not found");
    }

    @Test
    void findByIdPage_genericScope_normalEvent_returnsEvent() {
        PermissionAuditEvent normal = eventWithAction(43L, "ASSIGN_ROLE");
        when(repository.findById(43L)).thenReturn(Optional.of(normal));

        AuditEventPageResponse resp = auditEventService.findByIdPage("43", AuditReadScope.GENERIC_AUDIT);

        assertThat(resp.events()).hasSize(1);
        assertThat(resp.events().get(0).action()).isEqualTo("ASSIGN_ROLE");
    }

    @Test
    void findByIdPage_impersonationScope_impersonationEvent_returnsEvent() {
        PermissionAuditEvent imp = eventWithAction(44L, ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED);
        when(repository.findById(44L)).thenReturn(Optional.of(imp));

        AuditEventPageResponse resp = auditEventService.findByIdPage("44", AuditReadScope.IMPERSONATION_AUDIT);

        assertThat(resp.events()).hasSize(1);
        assertThat(resp.events().get(0).action()).isEqualTo(ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED);
    }

    @Test
    void recordEvent_impersonationAction_notDispatchedToLiveStream() {
        when(repository.save(any(PermissionAuditEvent.class))).thenAnswer(inv -> {
            PermissionAuditEvent e = inv.getArgument(0);
            e.setId(99L);
            return e;
        });

        PermissionAuditEvent imp = new PermissionAuditEvent();
        imp.setEventType(ImpersonationAuditEventTypes.IMPERSONATION_STARTED);
        imp.setAction(ImpersonationAuditEventTypes.IMPERSONATION_STARTED);
        imp.setOccurredAt(Instant.parse("2026-05-09T10:00:00Z"));
        imp.setService("permission-service");
        imp.setLevel("INFO");

        auditEventService.recordEvent(imp);

        verify(repository).save(any(PermissionAuditEvent.class));
        // PR-D2: AUDIT.can_view live SSE channel must NOT receive impersonation
        // events; the AuditEventStream.publish call is suppressed at source.
        verify(auditEventStream, never()).publish(any());
    }

    @Test
    void recordEvent_normalAction_dispatchedToLiveStream() {
        when(repository.save(any(PermissionAuditEvent.class))).thenAnswer(inv -> {
            PermissionAuditEvent e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });

        PermissionAuditEvent normal = new PermissionAuditEvent();
        normal.setAction("ASSIGN_ROLE");
        normal.setOccurredAt(Instant.parse("2026-05-09T10:00:00Z"));
        normal.setService("permission-service");
        normal.setLevel("INFO");

        auditEventService.recordEvent(normal);

        verify(auditEventStream).publish(any());
    }

    @Test
    void exportEvents_genericScope_excludesImpersonationActions() {
        when(repository.findAll()).thenReturn(List.of(
                eventWithAction(1L, ImpersonationAuditEventTypes.IMPERSONATION_STARTED),
                eventWithAction(2L, "ASSIGN_ROLE"),
                eventWithAction(3L, "NON_IMPERSONATION_STARTED")
        ));
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        List<AuditEventResponse> events = auditEventService.exportEvents(
                null, Map.of(), null, AuditReadScope.GENERIC_AUDIT);

        assertThat(events).extracting(AuditEventResponse::action)
                .containsExactlyInAnyOrder("ASSIGN_ROLE", "NON_IMPERSONATION_STARTED");
    }

    // =====================================================================
    // PR-D2 iter-3 absorb (Codex 019e10bf P1): scope-aware download path +
    // legacy persisted-payload leak closure. Three regression scenarios:
    //   1. Legacy job (no scope marker) → 404
    //   2. Mismatched scope (IMPERSONATION-marked job, GENERIC requested) → 404
    //   3. Defensive payload re-filter — if a GENERIC-marked JSON job somehow
    //      contains an IMPERSONATION_* row, the row is stripped before return
    // =====================================================================

    private AuditExportJob completedJob(String id, String requestedBy, String filterSnapshotJson, byte[] payload) {
        AuditExportJob job = new AuditExportJob();
        job.setId(id);
        job.setRequestedBy(requestedBy);
        job.setStatus("COMPLETED");
        job.setFormat("json");
        job.setContentType("application/json");
        job.setFilename("audit-events-" + id + ".json");
        job.setPayload(payload);
        job.setFilterSnapshot(filterSnapshotJson);
        job.setCreatedAt(Instant.parse("2026-05-09T10:00:00Z"));
        job.setCompletedAt(Instant.parse("2026-05-09T10:00:01Z"));
        return job;
    }

    @Test
    void getCompletedExportJob_legacyJobWithoutScopeMarker_returns404() {
        // Pre-PR-D2 jobs persisted only the user filters as the snapshot —
        // no internal scope marker. Even an AUDIT.can_manage caller must NOT
        // be able to download a legacy completed job, because its payload
        // may embed IMPERSONATION_* rows from before the exclusion was wired
        // into createExportJob(). Fail-closed (404) is the contract.
        AuditExportJob legacy = completedJob(
                "legacy-1",
                "admin@example.com",
                "{\"service\":\"permission-service\"}", // no __audit_read_scope key
                "[{\"action\":\"IMPERSONATION_STARTED\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(auditExportJobRepository.findById("legacy-1")).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() ->
                auditEventService.getCompletedExportJob("legacy-1", "admin@example.com", AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Audit export job not found");
    }

    @Test
    void getCompletedExportJob_mismatchedScopeMarker_returns404() {
        // Job was created under IMPERSONATION_AUDIT scope (hypothetical — once
        // a dedicated impersonation export endpoint is added in a future PR);
        // a GENERIC_AUDIT caller hitting /export-jobs/{id}/download must get
        // 404, never the impersonation payload.
        AuditExportJob mismatched = completedJob(
                "imp-1",
                "admin@example.com",
                "{\"__audit_read_scope\":\"IMPERSONATION_AUDIT\"}",
                "[{\"action\":\"IMPERSONATION_STARTED\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(auditExportJobRepository.findById("imp-1")).thenReturn(Optional.of(mismatched));

        assertThatThrownBy(() ->
                auditEventService.getCompletedExportJob("imp-1", "admin@example.com", AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Audit export job not found");
    }

    @Test
    void getCompletedExportJob_genericScopeMarker_jsonPayloadContainsImpersonationRow_stripsRowDefensively() throws Exception {
        // Belt-and-braces: even if a GENERIC-marked JSON payload somehow
        // contains an IMPERSONATION_* row (manual SQL edit, restored backup,
        // future bug), the download path re-filters and the row never leaves
        // the service. Authoritative gate is the scope marker; this is the
        // defense-in-depth second pass per Codex iter-3 P1.
        Map<String, Object> normalRow = new LinkedHashMap<>();
        normalRow.put("id", "1");
        normalRow.put("action", "ASSIGN_ROLE");
        Map<String, Object> impRow = new LinkedHashMap<>();
        impRow.put("id", "2");
        impRow.put("action", ImpersonationAuditEventTypes.IMPERSONATION_STARTED);
        byte[] poisonedPayload = objectMapper.writeValueAsBytes(List.of(normalRow, impRow));

        AuditExportJob poisoned = completedJob(
                "poisoned-1",
                "admin@example.com",
                "{\"__audit_read_scope\":\"GENERIC_AUDIT\"}",
                poisonedPayload);
        when(auditExportJobRepository.findById("poisoned-1")).thenReturn(Optional.of(poisoned));

        AuditExportJob result = auditEventService.getCompletedExportJob(
                "poisoned-1", "admin@example.com", AuditReadScope.GENERIC_AUDIT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = objectMapper.readValue(result.getPayload(), List.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("ASSIGN_ROLE");
    }

    @Test
    void getCompletedExportJob_genericScopeMarker_cleanPayload_returnsAsIs() {
        // Happy path: scope marker matches, payload clean. Bytes pass through
        // untouched (no defensive rewrite logged).
        byte[] clean = "[{\"id\":\"1\",\"action\":\"ASSIGN_ROLE\"}]"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AuditExportJob job = completedJob(
                "clean-1",
                "admin@example.com",
                "{\"__audit_read_scope\":\"GENERIC_AUDIT\"}",
                clean);
        when(auditExportJobRepository.findById("clean-1")).thenReturn(Optional.of(job));

        AuditExportJob result = auditEventService.getCompletedExportJob(
                "clean-1", "admin@example.com", AuditReadScope.GENERIC_AUDIT);
        assertThat(result.getPayload()).isEqualTo(clean);
    }

    @Test
    void getExportJob_legacyJobWithoutScopeMarker_returns404() {
        // Status fetch (no payload involved) also fail-closes on legacy jobs:
        // even job metadata is hidden cross-scope.
        AuditExportJob legacy = completedJob(
                "legacy-status-1",
                "admin@example.com",
                "{\"service\":\"permission-service\"}",
                "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(auditExportJobRepository.findById("legacy-status-1")).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() ->
                auditEventService.getExportJob("legacy-status-1", "admin@example.com", AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Audit export job not found");
    }

    @Test
    void createExportJob_persistsScopeMarkerInFilterSnapshot() {
        // Forward-compat: every fresh job written by createExportJob must
        // include the __audit_read_scope marker so subsequent download
        // requests can re-validate scope. If this test ever fails, the
        // download-path scope check above will silently 404 every legitimate
        // job — making the marker essentially unforgeable but still required.
        when(repository.findAll()).thenReturn(List.of());
        when(userAuditEventMirrorRepository.findAll()).thenReturn(List.of());

        // Capture the persisted job to inspect its filterSnapshot.
        java.util.concurrent.atomic.AtomicReference<AuditExportJob> savedJob = new java.util.concurrent.atomic.AtomicReference<>();
        when(auditExportJobRepository.save(any(AuditExportJob.class))).thenAnswer(inv -> {
            AuditExportJob job = inv.getArgument(0);
            savedJob.set(job);
            return job;
        });

        auditEventService.createExportJob(
                "admin@example.com", "json", 50, null,
                Map.of("service", "permission-service"),
                AuditReadScope.GENERIC_AUDIT);

        AuditExportJob persisted = savedJob.get();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getFilterSnapshot())
                .as("scope marker must be embedded in filterSnapshot for download-time scope re-validation")
                .contains("__audit_read_scope")
                .contains("GENERIC_AUDIT");
    }
}
