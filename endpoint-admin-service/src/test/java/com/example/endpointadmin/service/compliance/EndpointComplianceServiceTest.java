package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.ComplianceEnforcementMode;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceComplianceState;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointComplianceEvaluationRepository;
import com.example.endpointadmin.repository.EndpointDeviceComplianceStateRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCompliancePolicyItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EndpointComplianceService} — BE-023 (Faz 22.5).
 *
 * <p>Covers the decision precedence ladder, per-reason taxonomy, hash
 * determinism, and inventory-staleness preservation around the
 * UNAUTHORIZED branch. Postgres-only behaviour (advisory lock,
 * composite-FK enforcement) is verified in
 * {@code EndpointComplianceServicePostgresIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class EndpointComplianceServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");
    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "admin@example.com");

    @Mock
    private EndpointDeviceRepository deviceRepository;
    @Mock
    private EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    @Mock
    private EndpointSoftwareCompliancePolicyItemRepository policyRepository;
    @Mock
    private EndpointComplianceEvaluationRepository evaluationRepository;
    @Mock
    private EndpointDeviceComplianceStateRepository stateRepository;

    private EndpointComplianceService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EndpointComplianceService(
                deviceRepository, snapshotRepository, policyRepository,
                evaluationRepository, stateRepository,
                null, // no JdbcTemplate — advisory lock path is a no-op in unit tests
                singletonProvider(fixed));
    }

    private static ObjectProvider<Clock> singletonProvider(Clock clock) {
        return new ObjectProvider<>() {
            @Override public Clock getObject() { return clock; }
            @Override public Clock getObject(Object... args) { return clock; }
            @Override public Clock getIfAvailable() { return clock; }
            @Override public Clock getIfUnique() { return clock; }
        };
    }

    @Test
    void deviceNotFoundThrows404() {
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateForAdmin(TENANT, DEVICE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void inventoryMissingProducesUNKNOWN() {
        mockDevice();
        mockSnapshot(null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("inventory_missing", "policy_empty");
    }

    @Test
    void emptyPolicyDrivesUNKNOWNEvenWithFreshInventory() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        mockSnapshot(snap);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("policy_empty");
        assertThat(outcome.blockingReasons()).isEmpty();
    }

    @Test
    void requiredAppMissingProducesNON_COMPLIANT() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "Some Other Tool", "1.0", "Other Vendor");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog("7zip.7zip", "7-Zip",
                CatalogVersionPolicyType.LATEST, null);
        EndpointSoftwareCompliancePolicyItem policy =
                buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(policy));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.NON_COMPLIANT);
        assertThat(outcome.blockingReasons()).containsExactly("missing_required_app");
    }

    @Test
    void requiredAppOutdatedProducesNON_COMPLIANT() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "7-Zip", "20.0", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog("7zip.7zip", "7-Zip",
                CatalogVersionPolicyType.MINIMUM, "24.0");
        EndpointSoftwareCompliancePolicyItem policy =
                buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(policy));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.NON_COMPLIANT);
        assertThat(outcome.blockingReasons()).containsExactly("outdated_required_app");
    }

    @Test
    void forbiddenAppInstalledProducesUNAUTHORIZED() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "Bittorrent Client", "1.0", "BadVendor");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "BadVendor.Bittorrent", "Bittorrent Client",
                CatalogVersionPolicyType.LATEST, null);
        EndpointSoftwareCompliancePolicyItem policy =
                buildPolicy(catalog, ComplianceEnforcementMode.FORBIDDEN);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(policy));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNAUTHORIZED);
        assertThat(outcome.blockingReasons()).containsExactly("forbidden_app_installed");
    }

    @Test
    void unauthorizedTakesPriorityOverMissingRequired() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "Bittorrent Client", "1.0", "BadVendor");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem required = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        EndpointSoftwareCatalogItem forbidden = buildCatalog(
                "BadVendor.Bittorrent", "Bittorrent Client",
                CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(
                        buildPolicy(required, ComplianceEnforcementMode.REQUIRED),
                        buildPolicy(forbidden, ComplianceEnforcementMode.FORBIDDEN)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNAUTHORIZED);
        assertThat(outcome.reasons())
                .contains("forbidden_app_installed", "missing_required_app");
    }

    @Test
    void unauthorizedPreservedUnderHardStaleInventory() {
        mockDevice();
        Instant collected = NOW.minus(Duration.ofDays(10));
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(collected, true);
        addItem(snap, "Bittorrent Client", "1.0", "BadVendor");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "BadVendor.Bittorrent", "Bittorrent Client",
                CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.FORBIDDEN)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNAUTHORIZED);
        assertThat(outcome.reasons())
                .contains("forbidden_app_installed", "inventory_stale_hard");
    }

    @Test
    void compliantWhenRequiredSatisfiedAndNoForbidden() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "7-Zip 24.07 (x64)", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        assertThat(outcome.blockingReasons()).isEmpty();
        assertThat(outcome.warnings()).doesNotContain("inventory_stale_hard");
    }

    @Test
    void softStaleDoesNotFlipCompliant() {
        mockDevice();
        Instant collected = NOW.minus(Duration.ofHours(36));
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(collected, true);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        assertThat(outcome.warnings()).contains("inventory_stale_soft");
    }

    @Test
    void hardStaleWithoutForbiddenEvidenceProducesUNKNOWN() {
        mockDevice();
        Instant collected = NOW.minus(Duration.ofDays(7));
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(collected, true);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("inventory_stale_hard");
    }

    @Test
    void unsupportedVersionPolicyEmitsVERSION_COMPARE_UNSUPPORTED() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "7-Zip", "unparseable-version-xyzqwerty", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.RANGE, "garbage");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("version_compare_unsupported");
    }

    @Test
    void wingetEgressMissingDrivesUNKNOWN() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        snap.setWingetEgress(null);
        snap.setWingetEgressCollectedAt(null);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("winget_egress_missing");
    }

    @Test
    void wingetEgressSupportedFalseDrivesWINGET_EGRESS_UNSUPPORTED() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        Map<String, Object> egress = new LinkedHashMap<>();
        egress.put("supported", false);
        snap.setWingetEgress(egress);
        snap.setWingetEgressCollectedAt(NOW);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("winget_egress_unsupported");
    }

    @Test
    void catalogPolicyHashIsDeterministicAcrossEvaluations() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome first = service.evaluateForAdmin(TENANT, DEVICE_ID);
        ComplianceEvaluationOutcome second = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(first.catalogPolicyHash()).isEqualTo(second.catalogPolicyHash());
        assertThat(first.catalogPolicyHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void stalenessReportIsPerStreamWithWorst() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        snap.setSummaryCollectedAt(NOW);
        snap.setAppsCollectedAt(NOW.minus(Duration.ofHours(80))); // HARD
        snap.setWingetEgress(null);
        snap.setWingetEgressCollectedAt(null);
        addItem(snap, "7-Zip", "24.07", "7-Zip");
        mockSnapshot(snap);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.staleness().summary()).isEqualTo(StalenessSeverity.FRESH);
        assertThat(outcome.staleness().apps()).isEqualTo(StalenessSeverity.HARD);
        assertThat(outcome.staleness().wingetEgress()).isEqualTo(StalenessSeverity.UNAVAILABLE);
        assertThat(outcome.staleness().worst()).isEqualTo(StalenessSeverity.HARD);
    }

    @Test
    void evaluateForEventReturnsEmptyOnDeviceMissing() {
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        Optional<ComplianceEvaluationOutcome> outcome =
                service.evaluateForEvent(TENANT_ID, DEVICE_ID);

        assertThat(outcome).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────
    // Mock helpers

    private void mockDevice() {
        EndpointDevice device = new EndpointDevice();
        setField(device, "id", DEVICE_ID);
        setField(device, "tenantId", TENANT_ID);
        device.setStatus(DeviceStatus.ONLINE);
        when(deviceRepository.findByTenantIdAndId(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(device));
    }

    private void mockSnapshot(EndpointSoftwareInventorySnapshot snap) {
        when(snapshotRepository.findByTenantIdAndDevice_Id(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.ofNullable(snap));
    }

    private void mockSaveEvaluationEcho() {
        when(evaluationRepository.save(any(EndpointComplianceEvaluation.class)))
                .thenAnswer(inv -> {
                    EndpointComplianceEvaluation e = inv.getArgument(0);
                    if (e.getId() == null) {
                        setField(e, "id", UUID.randomUUID());
                    }
                    return e;
                });
    }

    private void mockUpsertNoExisting() {
        when(stateRepository.findByTenantIdAndDeviceId(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());
        when(stateRepository.save(any(EndpointDeviceComplianceState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ────────────────────────────────────────────────────────────────
    // Fixture builders

    private EndpointSoftwareInventorySnapshot buildSnapshotFresh(Instant collectedAt, boolean withEgress) {
        EndpointSoftwareInventorySnapshot snap = new EndpointSoftwareInventorySnapshot();
        setField(snap, "id", UUID.randomUUID());
        setField(snap, "tenantId", TENANT_ID);
        setField(snap, "items", new ArrayList<EndpointSoftwareInventoryItem>());
        snap.setSupported(true);
        snap.setAppsAvailable(true);
        snap.setSummaryCollectedAt(collectedAt);
        snap.setAppsCollectedAt(collectedAt);
        setField(snap, "updatedAt", collectedAt);
        if (withEgress) {
            Map<String, Object> egress = new LinkedHashMap<>();
            egress.put("supported", true);
            egress.put("schemaVersion", 1);
            snap.setWingetEgress(egress);
            snap.setWingetEgressCollectedAt(collectedAt);
            snap.setWingetEgressSchemaVersion(1);
        }
        return snap;
    }

    private void addItem(
            EndpointSoftwareInventorySnapshot snap,
            String displayName, String version, String publisher) {
        EndpointSoftwareInventoryItem item = new EndpointSoftwareInventoryItem();
        setField(item, "id", UUID.randomUUID());
        setField(item, "tenantId", TENANT_ID);
        setField(item, "deviceId", DEVICE_ID);
        item.setDisplayName(displayName);
        item.setDisplayVersion(version);
        item.setPublisher(publisher);
        item.setInstallSource(SoftwareInstallSource.HKLM);
        snap.getItems().add(item);
    }

    private EndpointSoftwareCatalogItem buildCatalog(
            String packageId, String displayName,
            CatalogVersionPolicyType versionPolicyType, String versionPolicyValue) {
        EndpointSoftwareCatalogItem c = new EndpointSoftwareCatalogItem();
        setField(c, "id", UUID.randomUUID());
        c.setTenantId(TENANT_ID);
        c.setCatalogItemId(packageId);
        c.setStatus(CatalogItemStatus.APPROVED);
        c.setProvider(CatalogProvider.WINGET);
        c.setSourceType(CatalogSourceType.WINGET);
        c.setSourceName("winget");
        c.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        c.setPackageId(packageId);
        c.setDisplayName(displayName);
        c.setPublisher("Vendor");
        c.setVersionPolicyType(versionPolicyType);
        c.setVersionPolicyValue(versionPolicyValue);
        c.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        c.setSilentArgsPolicy(CatalogSilentArgsPolicy.DEFAULT);
        c.setRiskTier(CatalogRiskTier.LOW);
        c.setEnabled(true);
        c.setCreatedBySubject("creator");
        c.setLastUpdatedBySubject("creator");
        setField(c, "version", 1L);
        return c;
    }

    private EndpointSoftwareCompliancePolicyItem buildPolicy(
            EndpointSoftwareCatalogItem catalog,
            ComplianceEnforcementMode mode) {
        EndpointSoftwareCompliancePolicyItem p = new EndpointSoftwareCompliancePolicyItem();
        setField(p, "id", UUID.randomUUID());
        p.setTenantId(TENANT_ID);
        p.setCatalogItemId(catalog.getId());
        setField(p, "catalogItem", catalog);
        p.setEnforcementMode(mode);
        p.setEnabled(true);
        p.setCreatedBySubject("creator");
        p.setLastUpdatedBySubject("creator");
        setField(p, "version", 1L);
        return p;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(
                    "Could not set field " + fieldName + " on "
                            + target.getClass().getName(), ex);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
