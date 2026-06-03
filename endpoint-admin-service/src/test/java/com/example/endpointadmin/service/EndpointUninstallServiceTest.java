package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestResponse;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-028 Phase 1b — service-layer tests for {@link EndpointUninstallService}
 * (Faz 22.5.6).
 *
 * <p>Covers the Codex plan-time iter-2 absorb items + Phase 0's BE-014A
 * {@code noRollbackFor} pattern reuse:
 *
 * <ul>
 *   <li>Happy propose returns PENDING_APPROVAL.</li>
 *   <li>Happy approve transitions to APPROVED with commandId.</li>
 *   <li>Idempotency replay runs BEFORE catalog/provenance/in-flight guards
 *       (Codex iter-1 must-fix #1).</li>
 *   <li>422 gates: NOT_UNINSTALL_SUPPORTED / UNINSTALL_PROTECTED /
 *       NO_PROVENANCE / catalog not APPROVED.</li>
 *   <li>409 in-flight (read-side guard).</li>
 *   <li>BE-014A maker-checker durable audit regression
 *       ({@code noRollbackFor=EndpointUninstallMakerCheckerViolationException}).
 *       If the {@code noRollbackFor} clause is removed, the audit row would
 *       roll back with the 403 throw and the durability assertion would FAIL.</li>
 *   <li>422 CAPABILITY_NOT_ADVERTISED at approve (retryable non-terminal).</li>
 *   <li>424 STALE_HEARTBEAT at approve (retryable non-terminal).</li>
 * </ul>
 *
 * <p>Feature-flag-off (503) is covered by
 * {@link EndpointUninstallServiceFeatureFlagOffTest} (separate context).
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointUninstallService.class,
        EndpointAuditService.class,
        EndpointSoftwareCatalogService.class,
        DetectionRuleValidator.class,
        NoOpAuditChainLock.class
})
@TestPropertySource(properties = {
        "endpoint-admin.uninstall.enabled=true",
        "endpoint-admin.uninstall.heartbeat-freshness-ttl=PT5M",
        "endpoint-admin.uninstall.required-capability=UNINSTALL_SOFTWARE"
})
class EndpointUninstallServiceTest {

    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-44444444aa44");
    private static final UUID TENANT_DURABILITY = UUID.fromString("55555555-5555-5555-5555-55555555bb55");
    private static final String SUBJECT_ALICE = "alice@example.com";
    private static final String SUBJECT_BOB = "bob@example.com";

    @Autowired
    private EndpointUninstallService uninstallService;
    @Autowired
    private EndpointSoftwareCatalogService catalogService;
    @Autowired
    private EndpointDeviceRepository deviceRepository;
    @Autowired
    private EndpointSoftwareCatalogItemRepository catalogRepository;
    @Autowired
    private EndpointInstallAuditRepository installAuditRepository;
    @Autowired
    private EndpointHeartbeatRepository heartbeatRepository;
    @Autowired
    private EndpointAuditEventRepository auditEventRepository;
    @Autowired
    private EndpointCommandRepository commandRepository;

    // ─────────────────────────────────────────────────────────────────
    // Happy path

    @Test
    void propose_happyPath_returnsPendingApproval() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-happy-propose");

        AdminUninstallRequestResponse resp = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(
                        f.catalogSlug(), null, "test propose"));

        assertThat(resp.state()).isEqualTo(UninstallRequestState.PENDING_APPROVAL);
        assertThat(resp.commandId()).isNull();
        assertThat(resp.createdBy()).isEqualTo(SUBJECT_ALICE);
        assertThat(resp.deviceId()).isEqualTo(f.deviceId());
        assertThat(resp.catalogItemId()).isEqualTo(f.catalogUuid());
    }

    @Test
    void approve_happyPath_transitionsToApprovedWithCommandId() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-happy-approve");

        AdminUninstallRequestResponse proposed = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "remove"));

        // BOB approves Alice's request — maker-checker satisfied.
        AdminUninstallRequestResponse approved = uninstallService.approve(
                new AdminTenantContext(TENANT, SUBJECT_BOB),
                f.deviceId(),
                proposed.requestId(),
                new AdminUninstallRequestApproval("agreed"));

        assertThat(approved.state()).isEqualTo(UninstallRequestState.APPROVED);
        assertThat(approved.commandId()).isNotNull();
        assertThat(approved.approvedBy()).isEqualTo(SUBJECT_BOB);

        // Codex post-impl iter-1 must-fix #4 absorb: the dispatched command
        // payload must carry approvedBy (it was previously read BEFORE the
        // setApprovedBy call, so it would land as null on the wire).
        EndpointCommand dispatched = commandRepository.findById(approved.commandId()).orElseThrow();
        assertThat(dispatched.getPayload())
                .as("approvedBy must be set in the dispatched command payload "
                        + "(Codex iter-1 must-fix #4)")
                .containsEntry("approvedBy", SUBJECT_BOB)
                .containsEntry("createdBy", SUBJECT_ALICE)
                .containsEntry("intent", "UNINSTALL");
    }

    @Test
    void approve_doubleApproveSequential_secondCallReturns409() {
        // Codex post-impl iter-1 must-fix #2 absorb: the PESSIMISTIC_WRITE
        // lock + state guard guarantees that a second approver on an already-
        // approved request gets a clean 409, not a 500 from the
        // endpoint_commands idempotency-key unique constraint.
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-double-approve");

        AdminUninstallRequestResponse proposed = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "first"));
        uninstallService.approve(
                new AdminTenantContext(TENANT, SUBJECT_BOB),
                f.deviceId(),
                proposed.requestId(),
                new AdminUninstallRequestApproval("first approve"));

        // A second admin (charlie) attempts to approve the now-APPROVED row.
        assertThatThrownBy(() -> uninstallService.approve(
                new AdminTenantContext(TENANT, "charlie@example.com"),
                f.deviceId(),
                proposed.requestId(),
                new AdminUninstallRequestApproval("second approve")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("PENDING_APPROVAL");
    }

    // ─────────────────────────────────────────────────────────────────
    // Idempotency replay BEFORE catalog/provenance/in-flight guards
    // (Codex iter-1 must-fix #1)

    @Test
    void propose_idempotencyReplay_returnsExistingRequestId() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-idem-replay");
        AdminUninstallRequestCreate body = new AdminUninstallRequestCreate(
                f.catalogSlug(), "fixed-key-001", "first call");

        AdminUninstallRequestResponse first = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE), f.deviceId(), body);
        AdminUninstallRequestResponse replay = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE), f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), "fixed-key-001", "second"));

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.state()).isEqualTo(UninstallRequestState.PENDING_APPROVAL);
    }

    @Test
    void propose_idempotencyReplay_differentDevice_eachGetsOwnRequest() {
        // The canonical idempotency key embeds (deviceId, catalogUuid), so a
        // user-supplied key collision across different (device, catalog) pairs
        // resolves to DIFFERENT canonical keys; each propose succeeds with its
        // own request id. The defensive mismatch check inside the service
        // (`!Objects.equals(deviceId, ...)`) is a safety net for programmatic
        // abuse where the canonical key is bypassed; it cannot be exercised
        // through the public propose surface.
        Fixture f1 = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-idem-dev-a");
        Fixture f2 = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-idem-dev-b");

        AdminUninstallRequestResponse a = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f1.deviceId(),
                new AdminUninstallRequestCreate(f1.catalogSlug(), "shared-key", "first"));
        AdminUninstallRequestResponse b = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f2.deviceId(),
                new AdminUninstallRequestCreate(f2.catalogSlug(), "shared-key", "second"));

        assertThat(a.requestId()).isNotEqualTo(b.requestId());
        assertThat(a.deviceId()).isEqualTo(f1.deviceId());
        assertThat(b.deviceId()).isEqualTo(f2.deviceId());
    }

    // ─────────────────────────────────────────────────────────────────
    // Catalog gates (422)

    @Test
    void propose_catalogNotUninstallSupported_throws422() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-not-supported");
        // Override: clear uninstall_supported flag (direct mutation for test).
        EndpointSoftwareCatalogItem item = catalogRepository
                .findByTenantIdAndId(TENANT, f.catalogUuid()).orElseThrow();
        item.setUninstallSupported(false);
        catalogRepository.saveAndFlush(item);

        assertThatThrownBy(() -> uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "test")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("uninstall_supported");
    }

    @Test
    void propose_catalogProtected_throws422() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-protected");
        EndpointSoftwareCatalogItem item = catalogRepository
                .findByTenantIdAndId(TENANT, f.catalogUuid()).orElseThrow();
        item.setUninstallProtected(true);
        catalogRepository.saveAndFlush(item);

        assertThatThrownBy(() -> uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "test")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("uninstall-protected");
    }

    @Test
    void propose_noProvenance_throws422() {
        // Set up catalog + device but NO install audit row → provenance gate
        // rejects.
        UUID deviceId = seedDevice(TENANT, "no-provenance");
        UUID catalogUuid = seedApprovedUninstallableCatalog(TENANT, "no-provenance-7zip");
        seedFreshHeartbeatWithCapability(TENANT, deviceId);
        String slug = "no-provenance-7zip";

        assertThatThrownBy(() -> uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                deviceId,
                new AdminUninstallRequestCreate(slug, null, "test")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("provenance");
    }

    // ─────────────────────────────────────────────────────────────────
    // In-flight guard (409)

    @Test
    void propose_inFlight_throws409() {
        Fixture f = setupFullFixture(TENANT, SUBJECT_ALICE, "7zip-inflight");
        uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "first"));
        assertThatThrownBy(() -> uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "second")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("in-flight");
    }

    // ─────────────────────────────────────────────────────────────────
    // BE-014A maker-checker durable audit regression
    // (mirrors Phase 0's noRollbackFor regression test)

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approve_selfApproveDurableAudit_noRollbackForRegression() {
        // BE-014A pattern reuse — suspend class-level @DataJpaTest tx
        // (NOT_SUPPORTED) so approve(...) runs in its OWN tx. The reject
        // audit is emitted in the same tx as the throw; noRollbackFor =
        // EndpointUninstallMakerCheckerViolationException.class keeps that
        // tx from rolling back. If noRollbackFor is removed, this assertion
        // MUST fail because the reject audit row would not survive.
        Fixture f = setupFullFixture(TENANT_DURABILITY, SUBJECT_ALICE, "7zip-self-approve");

        AdminUninstallRequestResponse proposed = uninstallService.propose(
                new AdminTenantContext(TENANT_DURABILITY, SUBJECT_ALICE),
                f.deviceId(),
                new AdminUninstallRequestCreate(f.catalogSlug(), null, "for-self-approve-test"));

        // Alice attempts to approve her own request → maker-checker violation.
        assertThatThrownBy(() -> uninstallService.approve(
                new AdminTenantContext(TENANT_DURABILITY, SUBJECT_ALICE),
                f.deviceId(),
                proposed.requestId(),
                new AdminUninstallRequestApproval("self approve")))
                .isInstanceOf(EndpointUninstallMakerCheckerViolationException.class);

        // Durability assertion outside any test tx, scoped to the dedicated
        // durability tenant: reject audit row must survive the 403 throw.
        // If noRollbackFor is removed, this assertion FAILS.
        assertThat(auditEventTypes(TENANT_DURABILITY))
                .as("reject audit must persist past the 403 throw "
                        + "(noRollbackFor=EndpointUninstallMakerCheckerViolationException invariant)")
                .contains(EndpointUninstallService.EVENT_APPROVAL_REJECTED_MAKER_CHECKER);
    }

    // ─────────────────────────────────────────────────────────────────
    // Capability + heartbeat guards

    @Test
    void approve_capabilityNotAdvertised_throws422_retryable() {
        // Seed device with a fresh heartbeat but NO `capabilities` advertising
        // UNINSTALL_SOFTWARE.
        UUID deviceId = seedDevice(TENANT, "no-capability");
        UUID catalogUuid = seedApprovedUninstallableCatalog(TENANT, "no-cap-7zip");
        seedProvenance(TENANT, deviceId, catalogUuid);
        seedHeartbeatWithoutCapability(TENANT, deviceId);

        AdminUninstallRequestResponse proposed = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                deviceId,
                new AdminUninstallRequestCreate("no-cap-7zip", null, "propose"));

        assertThatThrownBy(() -> uninstallService.approve(
                new AdminTenantContext(TENANT, SUBJECT_BOB),
                deviceId,
                proposed.requestId(),
                new AdminUninstallRequestApproval("approve")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("UNINSTALL_SOFTWARE");
    }

    @Test
    void approve_staleHeartbeat_throws424_retryable() {
        // Codex post-impl iter-1 absorb (thread `019e8dcd` must-fix #3):
        // freshness now reads `heartbeat.receivedAt` (NOT device.lastSeenAt
        // which is also touched by enrollment / cert-rotate paths). Seed a
        // STALE heartbeat row so the assertion exercises the right gate.
        UUID deviceId = seedDevice(TENANT, "stale-hb");
        UUID catalogUuid = seedApprovedUninstallableCatalog(TENANT, "stale-hb-7zip");
        seedProvenance(TENANT, deviceId, catalogUuid);
        // Stale heartbeat (15 minutes ago) WITH the capability advertised —
        // the gate rejects on the freshness check BEFORE the capability check,
        // so we should still get 424 even though the capability would have
        // passed.
        seedHeartbeat(TENANT, deviceId,
                Instant.now().minusSeconds(900),
                Map.of("capabilities", List.of("UNINSTALL_SOFTWARE")));

        AdminUninstallRequestResponse proposed = uninstallService.propose(
                new AdminTenantContext(TENANT, SUBJECT_ALICE),
                deviceId,
                new AdminUninstallRequestCreate("stale-hb-7zip", null, "propose"));

        assertThatThrownBy(() -> uninstallService.approve(
                new AdminTenantContext(TENANT, SUBJECT_BOB),
                deviceId,
                proposed.requestId(),
                new AdminUninstallRequestApproval("approve")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FAILED_DEPENDENCY)
                .hasMessageContaining("heartbeat is stale");
    }

    // ─────────────────────────────────────────────────────────────────
    // Fixture helpers

    private record Fixture(UUID deviceId, UUID catalogUuid, String catalogSlug) {}

    private Fixture setupFullFixture(UUID tenantId, String subject, String slug) {
        UUID deviceId = seedDevice(tenantId, slug);
        UUID catalogUuid = seedApprovedUninstallableCatalog(tenantId, slug);
        seedProvenance(tenantId, deviceId, catalogUuid);
        seedFreshHeartbeatWithCapability(tenantId, deviceId);
        return new Fixture(deviceId, catalogUuid, slug);
    }

    private UUID seedDevice(UUID tenantId, String hostname) {
        return seedDeviceWithStaleLastSeen(tenantId, hostname, Instant.now());
    }

    private UUID seedDeviceWithStaleLastSeen(UUID tenantId, String hostname, Instant lastSeenAt) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.3.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(lastSeenAt);
        EndpointDevice saved = deviceRepository.saveAndFlush(device);
        return saved.getId();
    }

    private UUID seedApprovedUninstallableCatalog(UUID tenantId, String slug) {
        AdminCatalogItemRequest req = sevenZipCatalogRequest(slug);
        catalogService.createCatalogItem(new AdminTenantContext(tenantId, SUBJECT_ALICE), req);
        EndpointSoftwareCatalogItem approved = mapCatalogToEntity(
                catalogService.approveCatalogItem(
                        new AdminTenantContext(tenantId, SUBJECT_BOB), slug).id(),
                tenantId);
        approved.setUninstallSupported(true);
        approved.setUninstallProtected(false);
        catalogRepository.saveAndFlush(approved);
        return approved.getId();
    }

    private EndpointSoftwareCatalogItem mapCatalogToEntity(UUID id, UUID tenantId) {
        return catalogRepository.findByTenantIdAndId(tenantId, id).orElseThrow();
    }

    private void seedProvenance(UUID tenantId, UUID deviceId, UUID catalogUuid) {
        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(tenantId);
        audit.setOrgId(tenantId);
        audit.setDeviceId(deviceId);
        audit.setCommandId(UUID.randomUUID());
        audit.setCatalogItemId(catalogUuid);
        audit.setCatalogPackageId("7zip.7zip");
        audit.setCatalogRowVersion(0L);
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(Instant.now().minusSeconds(120));
        audit.setActorSubject(SUBJECT_ALICE);
        audit.setResultStatus(CommandResultStatus.SUCCEEDED);
        audit.setPostVerification(InstallPostVerification.SATISFIED);
        audit.setReportedAt(Instant.now().minusSeconds(60));
        audit.setFinishedAt(Instant.now().minusSeconds(60));
        audit.setStartedAt(Instant.now().minusSeconds(90));
        audit.setRedactedPayload(new HashMap<>());
        audit.setPostVerificationEvidence(new HashMap<>());
        installAuditRepository.saveAndFlush(audit);
    }

    private void seedFreshHeartbeatWithCapability(UUID tenantId, UUID deviceId) {
        seedHeartbeat(tenantId, deviceId, Instant.now(),
                Map.of("capabilities", List.of("UNINSTALL_SOFTWARE", "INSTALL_SOFTWARE")));
    }

    private void seedHeartbeatWithCapability(UUID tenantId, UUID deviceId) {
        seedHeartbeat(tenantId, deviceId, Instant.now(),
                Map.of("capabilities", List.of("UNINSTALL_SOFTWARE")));
    }

    private void seedHeartbeatWithoutCapability(UUID tenantId, UUID deviceId) {
        seedHeartbeat(tenantId, deviceId, Instant.now(),
                Map.of("capabilities", List.of("INSTALL_SOFTWARE")));
    }

    private void seedHeartbeat(UUID tenantId, UUID deviceId, Instant at,
                                Map<String, Object> payload) {
        EndpointDevice device = deviceRepository.findById(deviceId).orElseThrow();
        EndpointHeartbeat hb = new EndpointHeartbeat();
        hb.setTenantId(tenantId);
        hb.setDevice(device);
        hb.setReceivedAt(at);
        hb.setAgentVersion("0.3.0");
        hb.setPayload(new HashMap<>(payload));
        heartbeatRepository.saveAndFlush(hb);
    }

    private AdminCatalogItemRequest sevenZipCatalogRequest(String slug) {
        Map<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        return new AdminCatalogItemRequest(
                slug,
                CatalogProvider.WINGET,
                CatalogSourceType.WINGET,
                "winget",
                CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                "7zip.7zip",
                "7-Zip",
                "7-Zip",
                CatalogVersionPolicyType.LATEST,
                null,
                CatalogInstallerType.WINGET_SILENT,
                CatalogSilentArgsPolicy.VENDOR_RECOMMENDED,
                null,
                null,
                detection,
                CatalogRiskTier.LOW);
    }

    private List<String> auditEventTypes(UUID tenantId) {
        return auditEventRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(tenantId)
                .stream()
                .map(EndpointAuditEvent::getEventType)
                .toList();
    }
}
