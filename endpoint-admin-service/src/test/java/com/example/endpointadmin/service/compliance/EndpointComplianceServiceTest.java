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
import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @Mock
    private com.example.endpointadmin.repository.EndpointInstallAuditRepository installAuditRepository;
    @Mock
    private com.example.endpointadmin.repository.EndpointProhibitedSoftwareRuleRepository
            prohibitedSoftwareRuleRepository;

    private EndpointComplianceService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EndpointComplianceService(
                deviceRepository, snapshotRepository, policyRepository,
                evaluationRepository, stateRepository, installAuditRepository,
                prohibitedSoftwareRuleRepository,
                null, // no JdbcTemplate — advisory lock path is a no-op in unit tests
                singletonProvider(fixed),
                java.time.Duration.ofMinutes(15));
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

    // ── BE-025 prohibited-software denylist ────────────────────────────

    @Test
    void prohibitedDenylistMatchProducesUNAUTHORIZED() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "uTorrent", "3.5", "BitTorrent Inc");
        addItem(snap, "7-Zip", "24.07", "Igor Pavlov");
        mockSnapshot(snap);
        // No catalog policy at all — prohibited is NOT catalog-bound.
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        // Drives UNAUTHORIZED even with an otherwise-empty catalog policy set.
        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNAUTHORIZED);
        assertThat(outcome.blockingReasons()).contains("prohibited_app_installed");
        // Evidence carries the redacted matched fields (name/publisher/version).
        Map<String, Object> matched = matchedItems(outcome);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prohibited =
                (List<Map<String, Object>>) matched.get("prohibitedInstalled");
        assertThat(prohibited).hasSize(1);
        assertThat(prohibited.get(0))
                .containsEntry("matchedName", "uTorrent")
                .containsEntry("matchedPublisher", "BitTorrent Inc")
                .containsEntry("matchedVersion", "3.5")
                .containsEntry("matchType", "NAME")
                .containsEntry("matchMode", "EXACT");
        // Redaction: no notes / createdBySubject leak into evidence.
        assertThat(prohibited.get(0)).doesNotContainKeys("notes", "createdBySubject");
    }

    @Test
    void cleanDeviceWithDenylistRulesStaysCompliant() {
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        addItem(snap, "7-Zip 24.07 (x64)", "24.07", "Igor Pavlov");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        // A denylist rule exists but matches nothing installed.
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "torrent", null)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        assertThat(outcome.reasons()).doesNotContain("prohibited_app_installed");
    }

    @Test
    void prohibitedPreservedUnderHardStaleInventory() {
        // The locked invariant is the precedence ladder (any UNAUTHORIZED
        // severity outranks UNKNOWN), NOT that only forbidden_app_installed
        // drives it. A prohibited match must survive hard-stale telemetry the
        // same way a catalog FORBIDDEN hit does.
        mockDevice();
        Instant collected = NOW.minus(Duration.ofDays(10));
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(collected, true);
        addItem(snap, "Sketchy Tool", "1.0", "Sketchy Software LLC");
        mockSnapshot(snap);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.PUBLISHER,
                        ProhibitedSoftwareMatchMode.EXACT, null, "Sketchy Software LLC")));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNAUTHORIZED);
        assertThat(outcome.reasons())
                .contains("prohibited_app_installed", "inventory_stale_hard");
    }

    // ── BE-025 prohibited-rule drift visibility (Codex 019e763a REVISE #1) ──

    @Test
    void prohibitedRuleSetFoldsIntoPolicyHash() {
        // Two tenants with identical (empty) catalog policy but DIFFERENT
        // enabled denylist rule sets must produce different policy hashes, so
        // the prohibited-rule set genuinely participates in the drift hash.
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of());
        String hashNoRules = service.computeCurrentPolicyHash(TENANT_ID);

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        String hashWithRule = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(hashWithRule).isNotEqualTo(hashNoRules);
        assertThat(hashWithRule).hasSize(64); // SHA-256 hex
    }

    @Test
    void addingProhibitedRuleSurfacesDriftForPreviouslyCompliantDevice() {
        // THE previously-silent case (Codex 019e763a REVISE #1): a device is
        // evaluated COMPLIANT when NO denylist rule exists; later an enabled
        // rule that matches its installed software is added. The persisted
        // evaluation hash must no longer equal computeCurrentPolicyHash, so
        // the controller reports policyDrift=true and the operator sees a
        // "re-evaluate" CTA instead of a silently-stale COMPLIANT.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        // A satisfied REQUIRED policy makes the device genuinely COMPLIANT
        // (an empty policy set would drive UNKNOWN via policy_empty). The
        // uTorrent item is unrelated to the catalog item and is not yet
        // prohibited by any rule.
        addItem(snap, "7-Zip 24.07 (x64)", "24.07", "Igor Pavlov");
        addItem(snap, "uTorrent", "3.5", "BitTorrent Inc");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        // At EVALUATION time there are no denylist rules → COMPLIANT.
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of());
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);
        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        String persistedHash = outcome.catalogPolicyHash();

        // LATER a matching enabled prohibited rule is added for the tenant.
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        String currentHash = service.computeCurrentPolicyHash(TENANT_ID);

        // The previously-silent stale state is now visible: hashes diverge.
        assertThat(currentHash).isNotEqualTo(persistedHash);
    }

    @Test
    void updatingProhibitedRulePatternSurfacesDrift() {
        // A genuine pattern change on an existing rule changes the hash.
        UUID ruleId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRuleWithId(ruleId,
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "torrent", null)));
        String before = service.computeCurrentPolicyHash(TENANT_ID);

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRuleWithId(ruleId,
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "bittorrent", null)));
        String after = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void deletingProhibitedRuleSurfacesDrift() {
        // Removing the last enabled rule changes the hash (delete path).
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        String withRule = service.computeCurrentPolicyHash(TENANT_ID);

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of());
        String afterDelete = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(afterDelete).isNotEqualTo(withRule);
    }

    @Test
    void caseOrWhitespaceOnlyPatternEditDoesNotFalselyDrift() {
        // Normalized patterns: a pure case/whitespace edit the matcher treats
        // as equivalent must NOT register as drift (no spurious re-evaluate
        // CTA). Same rule id, normalized name folds to the same value.
        UUID ruleId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRuleWithId(ruleId,
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        String original = service.computeCurrentPolicyHash(TENANT_ID);

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRuleWithId(ruleId,
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "  UTORRENT  ", null)));
        String afterCosmeticEdit = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(afterCosmeticEdit).isEqualTo(original);
    }

    @Test
    void prohibitedRuleProjectionIsOrderIndependent() {
        // Hash must not depend on repository result ordering — two orderings
        // of the same rule set fold to the same hash.
        EndpointProhibitedSoftwareRule a = denylistRuleWithId(
                UUID.fromString("11111111-aaaa-1111-aaaa-111111111111"),
                ProhibitedSoftwareMatchType.NAME,
                ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null);
        EndpointProhibitedSoftwareRule b = denylistRuleWithId(
                UUID.fromString("22222222-bbbb-2222-bbbb-222222222222"),
                ProhibitedSoftwareMatchType.PUBLISHER,
                ProhibitedSoftwareMatchMode.EXACT, null, "Sketchy LLC");

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(a, b));
        String ab = service.computeCurrentPolicyHash(TENANT_ID);

        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(b, a));
        String ba = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(ab).isEqualTo(ba);
    }

    @Test
    void prohibitedNotEvaluatedWhenAppsUnavailable() {
        // Apps-unavailable inventory is not authoritative → no prohibited
        // findings produced off it (mirrors the catalog FORBIDDEN gate): the
        // matching path early-returns on the apps-unavailable snapshot. The
        // hash path (drift visibility, Codex 019e763a REVISE #1) still folds
        // the rule set in, so a rule change surfaces drift even on an
        // apps-unavailable device. Codex 019e763a single-snapshot fix: the
        // rule set is loaded ONCE at the start of evaluateInternal and shared
        // by both consumers, so the repository is consulted exactly once.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        snap.setAppsAvailable(false);
        mockSnapshot(snap);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of());
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        // No finding produced off non-authoritative inventory…
        assertThat(outcome.reasons()).doesNotContain("prohibited_app_installed");
        // …but the hash DID fold the rule set in (the single shared snapshot is
        // read exactly once per evaluation).
        verify(prohibitedSoftwareRuleRepository, times(1))
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID);
    }

    @Test
    void singleProhibitedRuleSnapshotPerEvaluation() {
        // Codex 019e763a REVISE #1 (concurrency drift variant): a single
        // evaluation must read the enabled prohibited-rule set ONCE so the
        // persisted decision/evidence and the persisted catalogPolicyHash can
        // never derive from two reads that straddle a concurrent rule commit
        // under READ_COMMITTED.
        //
        // The repository is stubbed to return DIFFERENT results on successive
        // calls within one evaluateInternal: EMPTY first, then ONE rule that
        // matches the installed "uTorrent". Under the OLD two-read design the
        // matching path would have seen the EMPTY list (evidence: no prohibited
        // finding, device COMPLIANT) while the hash path would have seen the
        // ONE-rule list (catalogPolicyHash folds the rule in) — an evaluation
        // row whose evidence and stored hash disagree, the exact silent-drift
        // re-opening. Under the single-load design both consumers receive the
        // EMPTY first value, so the row is internally consistent.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        // A satisfied REQUIRED policy makes the device genuinely COMPLIANT when
        // the denylist is empty (an empty policy set would drive UNKNOWN).
        addItem(snap, "7-Zip 24.07 (x64)", "24.07", "Igor Pavlov");
        addItem(snap, "uTorrent", "3.5", "BitTorrent Inc");
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        // Successive calls return DIFFERENT rule sets: empty, then one rule.
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(
                        List.of(),
                        List.of(denylistRule(
                                ProhibitedSoftwareMatchType.NAME,
                                ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        // Core invariant: the rule set was consulted EXACTLY ONCE within this
        // single evaluation (the two-read design consulted it twice, exposing
        // the straddle window).
        verify(prohibitedSoftwareRuleRepository, times(1))
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID);

        // Capture the row as persisted so evidence + stored hash are read
        // from the SAME object the service wrote (not just the outcome DTO).
        ArgumentCaptor<EndpointComplianceEvaluation> savedCaptor =
                ArgumentCaptor.forClass(EndpointComplianceEvaluation.class);
        verify(evaluationRepository).save(savedCaptor.capture());
        EndpointComplianceEvaluation persisted = savedCaptor.getValue();

        // The single read returned the EMPTY denylist → no prohibited finding,
        // device COMPLIANT. Evidence reflects the SAME empty snapshot.
        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        assertThat(outcome.reasons()).doesNotContain("prohibited_app_installed");
        @SuppressWarnings("unchecked")
        Map<String, Object> matched =
                (Map<String, Object>) persisted.getEvidence().get("matchedItems");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prohibited =
                (List<Map<String, Object>>) matched.get("prohibitedInstalled");
        assertThat(prohibited).isEmpty();

        // Evidence ↔ hash consistency: the persisted hash MUST reflect the same
        // EMPTY rule set the evidence reflects — NOT the one-rule second read.
        // Proof: a fresh current-hash read over the SAME empty denylist equals
        // the persisted hash (policyDrift would be false), whereas a current
        // read over the one-rule set diverges. Under the old two-read design
        // the persisted hash already folded in the rule, so it would have
        // equalled the one-rule current hash and DISAGREED with its own empty
        // evidence.
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of());
        String currentHashEmpty = service.computeCurrentPolicyHash(TENANT_ID);
        when(prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(TENANT_ID))
                .thenReturn(List.of(denylistRule(
                        ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)));
        String currentHashOneRule = service.computeCurrentPolicyHash(TENANT_ID);

        assertThat(persisted.getCatalogPolicyHash())
                .isEqualTo(outcome.catalogPolicyHash())
                .isEqualTo(currentHashEmpty)        // persisted hash == empty-snapshot hash
                .isNotEqualTo(currentHashOneRule);  // … and NOT the one-rule hash
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
    // BE-021 — install audit fallback acceptance scenarios
    // (Codex 019e6dfb iter-4 P1-4 group 3 of 3 coverage)

    @Test
    void installAuditFallbackNotAppliedWhenSnapshotMissing() {
        // T1: snapshot null → policy item is early-returned (telemetry
        // gate UNKNOWN). Audit fallback is intentionally NOT consulted.
        mockDevice();
        mockSnapshot(null);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.UNKNOWN);
        assertThat(outcome.reasons()).contains("inventory_missing");
        // Conservative MVP: audit fallback NOT invoked when inventory is missing.
        org.mockito.Mockito.verifyNoInteractions(installAuditRepository);
    }

    @Test
    void installAuditFallbackSatisfiesRequiredWithinGrace() {
        // T3: snapshot fresh + apps_available, package NOT observed in
        // inventory, SUCCEEDED+SATISFIED audit within install grace
        // window → REQUIRED satisfied → COMPLIANT.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        // Note: NO addItem — package not currently observable.
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        com.example.endpointadmin.model.EndpointInstallAudit audit =
                buildAudit(catalog.getId(), "24.07",
                        NOW.minusSeconds(60));    // within 15-min grace
        when(installAuditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        eq(TENANT_ID), eq(DEVICE_ID), eq(catalog.getId()), any()))
                .thenReturn(Optional.of(audit));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.COMPLIANT);
        assertThat(outcome.blockingReasons()).isEmpty();
    }

    @Test
    void installAuditFallbackContradictedPastGraceTriggersWarn() {
        // T4: snapshot fresh + apps_available, package NOT observed,
        // SUCCEEDED+SATISFIED audit reported well before snapshot
        // apps_collected_at (past install grace) → MISSING_REQUIRED_APP
        // + INSTALL_AUDIT_CONTRADICTED_BY_INVENTORY.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        snap.setAppsCollectedAt(NOW);   // fresh snapshot
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.LATEST, null);
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        com.example.endpointadmin.model.EndpointInstallAudit audit =
                buildAudit(catalog.getId(), "24.07",
                        NOW.minus(Duration.ofHours(1)));    // grace = 15min → contradicted
        when(installAuditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        eq(TENANT_ID), eq(DEVICE_ID), eq(catalog.getId()), any()))
                .thenReturn(Optional.of(audit));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.NON_COMPLIANT);
        assertThat(outcome.reasons())
                .contains("missing_required_app", "install_audit_contradicted_by_inventory");
    }

    @Test
    void installAuditFallbackVersionViolatesProducesOutdated() {
        // T5: within grace + audit detectedVersion violates the
        // catalog policy → OUTDATED_REQUIRED_APP.
        mockDevice();
        EndpointSoftwareInventorySnapshot snap = buildSnapshotFresh(NOW, true);
        mockSnapshot(snap);
        EndpointSoftwareCatalogItem catalog = buildCatalog(
                "7zip.7zip", "7-Zip", CatalogVersionPolicyType.MINIMUM, "24.0");
        when(policyRepository.findEnabledByTenantAndModes(eq(TENANT_ID), any()))
                .thenReturn(List.of(buildPolicy(catalog, ComplianceEnforcementMode.REQUIRED)));
        com.example.endpointadmin.model.EndpointInstallAudit audit =
                buildAudit(catalog.getId(), "20.0",
                        NOW.minusSeconds(60));
        when(installAuditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        eq(TENANT_ID), eq(DEVICE_ID), eq(catalog.getId()), any()))
                .thenReturn(Optional.of(audit));
        mockSaveEvaluationEcho();
        mockUpsertNoExisting();

        ComplianceEvaluationOutcome outcome = service.evaluateForAdmin(TENANT, DEVICE_ID);

        assertThat(outcome.decision()).isEqualTo(ComplianceDecision.NON_COMPLIANT);
        assertThat(outcome.blockingReasons()).contains("outdated_required_app");
    }

    private com.example.endpointadmin.model.EndpointInstallAudit buildAudit(
            UUID catalogItemId, String detectedVersion, Instant reportedAt) {
        com.example.endpointadmin.model.EndpointInstallAudit a =
                new com.example.endpointadmin.model.EndpointInstallAudit();
        setField(a, "id", UUID.randomUUID());
        a.setTenantId(TENANT_ID);
        a.setDeviceId(DEVICE_ID);
        a.setCatalogItemId(catalogItemId);
        a.setDetectedVersion(detectedVersion);
        a.setReportedAt(reportedAt);
        setField(a, "rowVersion", 1L);
        return a;
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

    private EndpointProhibitedSoftwareRule denylistRule(
            ProhibitedSoftwareMatchType matchType,
            ProhibitedSoftwareMatchMode matchMode,
            String namePattern, String publisherPattern) {
        return denylistRuleWithId(UUID.randomUUID(), matchType, matchMode,
                namePattern, publisherPattern);
    }

    private EndpointProhibitedSoftwareRule denylistRuleWithId(
            UUID id,
            ProhibitedSoftwareMatchType matchType,
            ProhibitedSoftwareMatchMode matchMode,
            String namePattern, String publisherPattern) {
        EndpointProhibitedSoftwareRule r = new EndpointProhibitedSoftwareRule();
        setField(r, "id", id);
        r.setTenantId(TENANT_ID);
        r.setMatchType(matchType);
        r.setMatchMode(matchMode);
        r.setNamePattern(namePattern);
        r.setPublisherPattern(publisherPattern);
        r.setEnabled(true);
        r.setNotes("internal note — must NOT leak to evidence");
        r.setCreatedBySubject("creator");
        r.setLastUpdatedBySubject("creator");
        setField(r, "version", 1L);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> matchedItems(ComplianceEvaluationOutcome outcome) {
        return (Map<String, Object>) outcome.evidence().get("matchedItems");
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
