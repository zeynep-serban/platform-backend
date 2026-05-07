package com.example.permission.service;

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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
}
