package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstalledState;
import com.example.endpointadmin.exception.InstallBlockedException;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-021 — unit tests for {@link EndpointAdminCommandService#createInstall(AdminTenantContext, UUID, CreateInstallRequest)}
 * (Codex 019e6dfb iter-4 P1-4 acceptance coverage). Mockito-only so
 * the test is isolated from the cross-class H2 lifecycle that affects
 * the @DataJpaTest slice in CI.
 *
 * <p>Acceptance scenarios:
 *
 * <ul>
 *   <li>Idempotency replay runs BEFORE preflight recompute — an
 *       existing INSTALL_SOFTWARE command returns its DTO and
 *       {@link EndpointInstallPreflightService#evaluate} is never
 *       called (Codex iter-3 P0-1 / iter-4 P1-1).</li>
 *   <li>Same key with mismatched device / catalog → 409 Conflict.</li>
 *   <li>No existing command + preflight BLOCK → 409 via
 *       {@link InstallBlockedException}.</li>
 *   <li>No existing command + preflight PASS → command queued with the
 *       backend-controlled payload (caller payload cannot override
 *       catalog metadata).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EndpointAdminCommandServiceInstallTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_DEVICE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CATALOG_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_CATALOG_UUID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "alice@example.com");
    private static final String CATALOG_SLUG = "7zip-stable";
    private static final String CALLER_KEY = "client-supplied-key-001";

    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private EndpointCommandApprovalRepository approvalRepository;
    @Mock private EndpointDeviceRepository deviceRepository;
    @Mock private EndpointSoftwareCatalogItemRepository catalogRepository;
    @Mock private EndpointInstallPreflightService preflightService;
    @Mock private EndpointAuditService auditService;

    private EndpointAdminCommandService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EndpointAdminCommandService(
                commandRepository, resultRepository, approvalRepository,
                deviceRepository, catalogRepository, preflightService,
                auditService, fixed,
                Set.of(CommandType.COLLECT_INVENTORY));
    }

    @Test
    void createInstallReturnsExistingCommandWithoutRunningPreflight() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));

        EndpointCommand existing = existingInstallCommand(device);
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.of(existing));
        when(resultRepository.findByCommand_Id(existing.getId())).thenReturn(Optional.empty());

        CreateInstallRequest request = new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null);

        EndpointCommandDto dto = service.createInstall(TENANT, DEVICE_ID, request);

        assertThat(dto.id()).isEqualTo(existing.getId());
        // P1-1 contract: preflight MUST NOT run when an existing
        // INSTALL_SOFTWARE command matches the idempotency key.
        verify(preflightService, never()).evaluate(any(), any(), anyString());
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createInstallRejectsIdempotencyKeyReusedWithDifferentDevice() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));

        EndpointCommand existing = existingInstallCommand(testDevice(OTHER_DEVICE_ID));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.of(existing));

        CreateInstallRequest request = new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null);

        assertThatThrownBy(() -> service.createInstall(TENANT, DEVICE_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Install idempotency key already used");
        verify(preflightService, never()).evaluate(any(), any(), anyString());
    }

    @Test
    void createInstallRejectsIdempotencyKeyReusedWithDifferentCatalog() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));

        EndpointCommand existing = existingInstallCommand(device);
        existing.getPayload().put("catalogItemUuid", OTHER_CATALOG_UUID.toString());
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.of(existing));

        CreateInstallRequest request = new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null);

        assertThatThrownBy(() -> service.createInstall(TENANT, DEVICE_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Install idempotency key already used");
        verify(preflightService, never()).evaluate(any(), any(), anyString());
    }

    @Test
    void createInstallThrowsInstallBlockedExceptionOnBlockPreflight() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());

        InstallPreflightResponse blocked = preflightOf(InstallPreflightDecision.BLOCK);
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG)).thenReturn(blocked);

        CreateInstallRequest request = new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null);

        assertThatThrownBy(() -> service.createInstall(TENANT, DEVICE_ID, request))
                .isInstanceOf(InstallBlockedException.class)
                .extracting(ex -> ((InstallBlockedException) ex).preflight().decision())
                .isEqualTo(InstallPreflightDecision.BLOCK);
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createInstallQueuesCommandWithBackendControlledPayloadOnPass() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    return cmd;
                });

        CreateInstallRequest request = new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, "scheduled install");

        EndpointCommandDto dto = service.createInstall(TENANT, DEVICE_ID, request);

        assertThat(dto.type()).isEqualTo(CommandType.INSTALL_SOFTWARE);
        assertThat(dto.payload())
                .containsEntry("catalogItemId", CATALOG_SLUG)
                .containsEntry("catalogItemUuid", CATALOG_UUID.toString())
                .containsEntry("catalogPackageId", catalog.getPackageId())
                .containsEntry("preflightDecision", "PASS")
                .containsEntry("reason", "scheduled install")
                // AG-027 COMMAND-CONTRACT §7 — agent-consumed fields the agent
                // fail-closes on. These were absent before the fix, so the
                // agent rejected the install at "missing detectionRule.type"
                // before winget ever ran (the install-pilot blocker).
                .containsEntry("provider", "WINGET")
                .containsEntry("packageId", "7zip.7zip")
                .containsEntry("argsPolicyPreset", "DEFAULT")
                .containsEntry("catalogItemKey", CATALOG_SLUG)
                .containsKey("detectionRule")
                .containsKey("versionPredicate");
        @SuppressWarnings("unchecked")
        Map<String, Object> detectionRule = (Map<String, Object>) dto.payload().get("detectionRule");
        assertThat(detectionRule)
                .as("detectionRule.type/packageId drive AG-027 pre/post detection")
                .containsEntry("type", "WINGET_PACKAGE")
                .containsEntry("packageId", "7zip.7zip");
        // Agent enforces packageId == detectionRule.packageId (case-insensitive).
        assertThat(dto.payload().get("packageId")).isEqualTo(detectionRule.get("packageId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> versionPredicate = (Map<String, Object>) dto.payload().get("versionPredicate");
        assertThat(versionPredicate).containsEntry("type", "LATEST");
        verify(commandRepository, times(1)).saveAndFlush(any(EndpointCommand.class));
        verify(auditService, times(1)).record(
                any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("ENDPOINT_INSTALL_COMMAND_CREATED"),
                org.mockito.ArgumentMatchers.eq("CREATE_INSTALL_COMMAND"),
                anyString(), anyString(), any(), any(), any());
    }

    @Test
    void createInstallMapsVendorRecommendedArgsPolicyPreset() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        catalog.setSilentArgsPolicy(CatalogSilentArgsPolicy.VENDOR_RECOMMENDED);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    return cmd;
                });

        EndpointCommandDto dto = service.createInstall(
                TENANT, DEVICE_ID, new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null));

        // VENDOR_RECOMMENDED catalog policy maps to the agent's distinct preset
        // slot (install_winget.go::argsPresets); v1 ships the same arg slice but
        // the name preserves operator intent in the audit trail.
        assertThat(dto.payload()).containsEntry("argsPolicyPreset", "VENDOR_RECOMMENDED_WINGET_NO_UPGRADE");
    }

    // Path C2 (Codex 019e893a): FILE_EXISTS, FILE_SHA256, FILE_VERSION are
    // now agent-installable. The pre-C2 fail-closed-on-FILE_* test has been
    // replaced with positive forward tests that assert the wire payload
    // includes the agent's canonical `path` field (renamed from the catalog
    // `absolutePath`) + the per-type extras.

    @Test
    void createInstallForwardsFileExistsDetectionRule() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "FILE_EXISTS");
        rule.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        catalog.setDetectionRule(rule);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    setField(cmd, "createdAt", Instant.now());
                    return cmd;
                });

        service.createInstall(TENANT, DEVICE_ID,
                new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null));

        org.mockito.ArgumentCaptor<EndpointCommand> captor =
                org.mockito.ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        EndpointCommand saved = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = saved.getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> wireRule = (Map<String, Object>) payload.get("detectionRule");
        assertThat(wireRule).containsEntry("type", "FILE_EXISTS");
        // Agent contract: `path` (not catalog `absolutePath`).
        assertThat(wireRule).containsEntry("path", "C:\\Program Files\\7-Zip\\7z.exe");
        assertThat(wireRule).doesNotContainKey("absolutePath");
    }

    @Test
    void createInstallForwardsFileSha256DetectionRule() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "FILE_SHA256");
        rule.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        rule.put("expectedSha256", "a".repeat(64));
        rule.put("maxHashBytes", 256 * 1024L);
        catalog.setDetectionRule(rule);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    setField(cmd, "createdAt", Instant.now());
                    return cmd;
                });

        service.createInstall(TENANT, DEVICE_ID,
                new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null));

        org.mockito.ArgumentCaptor<EndpointCommand> captor =
                org.mockito.ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = captor.getValue().getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> wireRule = (Map<String, Object>) payload.get("detectionRule");
        assertThat(wireRule).containsEntry("type", "FILE_SHA256")
                .containsEntry("path", "C:\\Program Files\\7-Zip\\7z.exe")
                .containsEntry("expectedSha256", "a".repeat(64))
                .containsEntry("maxHashBytes", 256 * 1024L);
        assertThat(wireRule).doesNotContainKey("absolutePath");
    }

    @Test
    void createInstallForwardsFileVersionDetectionRule() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "FILE_VERSION");
        rule.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "EXACT");
        predicate.put("spec", "24.07");
        rule.put("versionPredicate", predicate);
        rule.put("fileVersionField", "PRODUCT_VERSION");
        catalog.setDetectionRule(rule);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    setField(cmd, "createdAt", Instant.now());
                    return cmd;
                });

        service.createInstall(TENANT, DEVICE_ID,
                new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null));

        org.mockito.ArgumentCaptor<EndpointCommand> captor =
                org.mockito.ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = captor.getValue().getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> wireRule = (Map<String, Object>) payload.get("detectionRule");
        assertThat(wireRule).containsEntry("type", "FILE_VERSION")
                .containsEntry("path", "C:\\Program Files\\7-Zip\\7z.exe")
                .containsEntry("fileVersionField", "PRODUCT_VERSION");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) wireRule.get("versionPredicate");
        assertThat(p).containsEntry("type", "EXACT").containsEntry("spec", "24.07");
        assertThat(wireRule).doesNotContainKey("absolutePath");
    }

    @Test
    void createInstallForwardsRegistryUninstallDetectionRule() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "REGISTRY_UNINSTALL");
        rule.put("displayName", "7-Zip");
        rule.put("displayNameMatch", "PREFIX");
        rule.put("publisher", "Igor Pavlov");
        rule.put("publisherMatch", "EXACT");
        catalog.setDetectionRule(rule);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class)))
                .thenAnswer(inv -> {
                    EndpointCommand cmd = inv.getArgument(0);
                    setField(cmd, "id", UUID.randomUUID());
                    return cmd;
                });

        EndpointCommandDto dto = service.createInstall(
                TENANT, DEVICE_ID, new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null));

        @SuppressWarnings("unchecked")
        Map<String, Object> detectionRule = (Map<String, Object>) dto.payload().get("detectionRule");
        // The normalized registry selector is forwarded verbatim (no fabrication).
        assertThat(detectionRule)
                .containsEntry("type", "REGISTRY_UNINSTALL")
                .containsEntry("displayName", "7-Zip")
                .containsEntry("displayNameMatch", "PREFIX")
                .containsEntry("publisher", "Igor Pavlov")
                .containsEntry("publisherMatch", "EXACT");
        // Registry selector IS the rule identity — no packageId, no WINGET
        // identity invariant. The install target packageId still comes from the
        // catalog column.
        assertThat(detectionRule).doesNotContainKey("packageId");
        assertThat(dto.payload()).containsEntry("packageId", "7zip.7zip");
        verify(commandRepository, times(1)).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createInstallFailsClosedOnDetectionRulePackageIdDrift() {
        EndpointDevice device = testDevice(DEVICE_ID);
        EndpointSoftwareCatalogItem catalog = testCatalog();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "WINGET_PACKAGE");
        rule.put("wingetPackageId", "Some.Other.Package"); // drifted from packageId 7zip.7zip
        catalog.setDetectionRule(rule);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(catalogRepository.findByTenantIdAndCatalogItemId(TENANT_ID, CATALOG_SLUG))
                .thenReturn(Optional.of(catalog));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-install:" + DEVICE_ID + ":" + CATALOG_UUID + ":" + CALLER_KEY))
                .thenReturn(Optional.empty());
        when(preflightService.evaluate(TENANT, DEVICE_ID, CATALOG_SLUG))
                .thenReturn(preflightOf(InstallPreflightDecision.PASS));

        // Fail-closed: detectionRule.wingetPackageId drift from packageId would
        // let the agent install package A while verifying package B.
        assertThatThrownBy(() -> service.createInstall(
                TENANT, DEVICE_ID, new CreateInstallRequest(CATALOG_SLUG, CALLER_KEY, null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(commandRepository, org.mockito.Mockito.never()).saveAndFlush(any(EndpointCommand.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static EndpointDevice testDevice(UUID id) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        setField(device, "id", id);
        return device;
    }

    private static EndpointSoftwareCatalogItem testCatalog() {
        EndpointSoftwareCatalogItem catalog = new EndpointSoftwareCatalogItem();
        catalog.setTenantId(TENANT_ID);
        catalog.setCatalogItemId(CATALOG_SLUG);
        catalog.setStatus(CatalogItemStatus.APPROVED);
        catalog.setProvider(CatalogProvider.WINGET);
        catalog.setSourceType(CatalogSourceType.WINGET);
        catalog.setPackageId("7zip.7zip");
        catalog.setDisplayName("7-Zip");
        catalog.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        catalog.setEnabled(true);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "WINGET_PACKAGE");
        rule.put("packageId", "7zip.7zip");
        catalog.setDetectionRule(rule);
        setField(catalog, "id", CATALOG_UUID);
        setField(catalog, "version", 3L);
        return catalog;
    }

    private static EndpointCommand existingInstallCommand(EndpointDevice device) {
        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(TENANT_ID);
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.INSTALL_SOFTWARE);
        cmd.setStatus(com.example.endpointadmin.model.CommandStatus.QUEUED);
        cmd.setApprovalStatus(com.example.endpointadmin.model.ApprovalStatus.NOT_REQUIRED);
        cmd.setIdempotencyKey("admin-install:" + device.getId() + ":" + CATALOG_UUID + ":" + CALLER_KEY);
        cmd.setIssuedBySubject("alice@example.com");
        cmd.setIssuedAt(NOW);
        cmd.setVisibleAfterAt(NOW);
        cmd.setPriority(100);
        cmd.setAttemptCount(0);
        cmd.setMaxAttempts(3);
        setField(cmd, "id", UUID.randomUUID());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalogItemUuid", CATALOG_UUID.toString());
        payload.put("catalogPackageId", "7zip.7zip");
        cmd.setPayload(payload);
        return cmd;
    }

    private static InstallPreflightResponse preflightOf(InstallPreflightDecision decision) {
        return new InstallPreflightResponse(
                decision,
                CATALOG_SLUG,
                CATALOG_UUID,
                DEVICE_ID,
                NOW,
                InstalledState.UNKNOWN,
                new InstallPreflightResponse.InstallPreflightEvidence(
                        null, null, null, null, null, null, null, null, null, null, 3L, NOW),
                List.of(),
                decision == InstallPreflightDecision.BLOCK ? List.of("device_not_online") : List.of(),
                List.of(),
                decision == InstallPreflightDecision.BLOCK
                        ? List.of("Device must be ONLINE.") : List.of());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
