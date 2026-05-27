package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogRevokeRequest;
import com.example.endpointadmin.exception.CatalogMakerCheckerViolationException;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-020 — Approved Software Catalog admin service tests (Faz 22.5.3).
 *
 * <p>Covers Codex 019e6a3e iter-2 acceptance gates:
 * <ul>
 *   <li>State transitions DRAFT -&gt; APPROVED -&gt; REVOKED (invalid moves reject)</li>
 *   <li>Maker-checker invariant + reject audit row durable across rollback
 *       (BE-014A {@code noRollbackFor} pattern reuse)</li>
 *   <li>Provider compatibility matrix (WINGET requires WINGET source_type +
 *       WINGET_COMMUNITY_REVIEWED / MICROSOFT_STORE trust)</li>
 *   <li>Tenant isolation (cross-tenant read/update/approve/revoke reject)</li>
 *   <li>Audit hash-chain emits one row per successful operation</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        TimeConfig.class,
        EndpointSoftwareCatalogService.class,
        EndpointAuditService.class,
        DetectionRuleValidator.class,
        NoOpAuditChainLock.class
})
class EndpointSoftwareCatalogServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    /**
     * Dedicated tenant for the {@code NOT_SUPPORTED} regression test
     * (Codex 019e6a64 iter-2 PARTIAL absorb): that test suspends the
     * class-level {@code @DataJpaTest} transaction and commits real rows
     * into the H2 instance — using a tenant that no other test in this
     * class queries against keeps tenant-wide count assertions order-
     * independent.
     */
    private static final UUID TENANT_DURABILITY =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SUBJECT_ALICE = "alice@example.com";
    private static final String SUBJECT_BOB = "bob@example.com";

    @Autowired
    private EndpointSoftwareCatalogService catalogService;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ----------------------------------------------------------------
    // Create

    @Test
    void createCatalogItemPersistsDraftWithEnabledFalseAndEmitsAudit() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-7zip-winget-stable");

        AdminCatalogItemResponse response = catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req);

        assertThat(response.status()).isEqualTo(CatalogItemStatus.DRAFT);
        assertThat(response.enabled()).isFalse();
        assertThat(response.createdBySubject()).isEqualTo(SUBJECT_ALICE);
        assertThat(response.lastUpdatedBySubject()).isEqualTo(SUBJECT_ALICE);
        assertThat(response.approvedBySubject()).isNull();

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareCatalogService.EVENT_CREATED);
    }

    @Test
    void createCatalogItemRejectsDuplicateSlugPerTenant() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-7zip-winget-stable");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void createCatalogItemAllowsSameSlugAcrossTenants() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-7zip-winget-stable");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);
        catalogService.createCatalogItem(context(TENANT_B, SUBJECT_ALICE), req);

        assertThat(catalogService.listCatalogItems(
                context(TENANT_A, SUBJECT_ALICE), null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(catalogService.listCatalogItems(
                context(TENANT_B, SUBJECT_ALICE), null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void createCatalogItemRejectsNonWingetProvider() {
        AdminCatalogItemRequest base = sevenZipRequest("evil-msi-item");
        AdminCatalogItemRequest req = new AdminCatalogItemRequest(
                base.catalogItemId(),
                base.provider(),
                CatalogSourceType.INTERNAL_ARTIFACT, // mismatched with WINGET
                base.sourceName(),
                CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                base.packageId(), base.displayName(), base.publisher(),
                base.versionPolicyType(), base.versionPolicyValue(),
                base.installerType(), base.silentArgsPolicy(),
                base.sha256(), base.provenance(),
                base.detectionRule(), base.riskTier());

        assertThatThrownBy(() -> catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sourceType=WINGET");
    }

    @Test
    void createCatalogItemRejectsWingetWithVendorSignedTrust() {
        AdminCatalogItemRequest base = sevenZipRequest("evil-vendor-trust");
        AdminCatalogItemRequest req = new AdminCatalogItemRequest(
                base.catalogItemId(), base.provider(),
                CatalogSourceType.WINGET, base.sourceName(),
                CatalogSourceTrust.VENDOR_SIGNED_HASH_PINNED,
                base.packageId(), base.displayName(), base.publisher(),
                base.versionPolicyType(), base.versionPolicyValue(),
                base.installerType(), base.silentArgsPolicy(),
                "0".repeat(64), "https://internal/artifact",
                base.detectionRule(), base.riskTier());

        assertThatThrownBy(() -> catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sourceTrust");
    }

    @Test
    void createCatalogItemRejectsRawCommandDetectionRule() {
        Map<String, Object> rawCommandRule = new HashMap<>();
        rawCommandRule.put("type", "EXIT_CODE");
        rawCommandRule.put("command", "powershell.exe -c Get-Process");

        AdminCatalogItemRequest base = sevenZipRequest("evil-raw-command");
        AdminCatalogItemRequest req = new AdminCatalogItemRequest(
                base.catalogItemId(), base.provider(), base.sourceType(),
                base.sourceName(), base.sourceTrust(),
                base.packageId(), base.displayName(), base.publisher(),
                base.versionPolicyType(), base.versionPolicyValue(),
                base.installerType(), base.silentArgsPolicy(),
                base.sha256(), base.provenance(),
                rawCommandRule, base.riskTier());

        assertThatThrownBy(() -> catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value()));
    }

    // ----------------------------------------------------------------
    // Update

    @Test
    void updateCatalogItemOnDraftSucceedsAndEmitsAudit() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-update");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);
        AdminCatalogItemRequest updated = new AdminCatalogItemRequest(
                req.catalogItemId(), req.provider(), req.sourceType(),
                req.sourceName(), req.sourceTrust(), req.packageId(),
                "7-Zip 24.07", // new display name
                req.publisher(), req.versionPolicyType(),
                req.versionPolicyValue(), req.installerType(),
                req.silentArgsPolicy(), req.sha256(), req.provenance(),
                req.detectionRule(), CatalogRiskTier.MED);

        AdminCatalogItemResponse response = catalogService.updateCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId(), updated);

        assertThat(response.displayName()).isEqualTo("7-Zip 24.07");
        assertThat(response.riskTier()).isEqualTo(CatalogRiskTier.MED);
        assertThat(response.lastUpdatedBySubject()).isEqualTo(SUBJECT_BOB);

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareCatalogService.EVENT_UPDATED);
    }

    @Test
    void updateCatalogItemOnApprovedRejectsAs409() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-update-after-approve");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);
        catalogService.approveCatalogItem(context(TENANT_A, SUBJECT_BOB),
                req.catalogItemId());

        assertThatThrownBy(() -> catalogService.updateCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    // ----------------------------------------------------------------
    // Approve / maker-checker

    @Test
    void approveCatalogItemHappyPathFlipsStatusAndEnabledAndEmitsAudit() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-approve-happy");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        AdminCatalogItemResponse approved = catalogService.approveCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId());

        assertThat(approved.status()).isEqualTo(CatalogItemStatus.APPROVED);
        assertThat(approved.enabled()).isTrue();
        assertThat(approved.approvedBySubject()).isEqualTo(SUBJECT_BOB);
        assertThat(approved.approvedAt()).isNotNull();

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareCatalogService.EVENT_APPROVED);
    }

    @Test
    void approveCatalogItemSelfApprovalEmitsRejectAuditAndThrows() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-self-approve");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.approveCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req.catalogItemId()))
                .isInstanceOf(CatalogMakerCheckerViolationException.class)
                .satisfies(ex -> assertThat(
                        ((CatalogMakerCheckerViolationException) ex).getStatusCode().value())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value()));

        flushAndClear();
        // The reject audit row MUST be durable across the failed approve
        // transaction (BE-014A `noRollbackFor` pattern reuse).
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareCatalogService
                        .EVENT_APPROVAL_REJECTED_MAKER_CHECKER);
        // And the catalog row stays DRAFT (the approve transaction did not
        // commit the status flip).
        AdminCatalogItemResponse current = catalogService.getCatalogItem(
                context(TENANT_A, SUBJECT_ALICE), req.catalogItemId());
        assertThat(current.status()).isEqualTo(CatalogItemStatus.DRAFT);
        assertThat(current.enabled()).isFalse();
    }

    @Test
    void approveCatalogItemOnRevokedRejectsAs409() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-approve-revoked");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);
        catalogService.approveCatalogItem(context(TENANT_A, SUBJECT_BOB),
                req.catalogItemId());
        catalogService.revokeCatalogItem(context(TENANT_A, SUBJECT_BOB),
                req.catalogItemId(),
                new AdminCatalogRevokeRequest("operator removed"));

        assertThatThrownBy(() -> catalogService.approveCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    // ----------------------------------------------------------------
    // Revoke

    @Test
    void revokeCatalogItemHappyPathEmitsAuditAndDisables() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-revoke-happy");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);
        catalogService.approveCatalogItem(context(TENANT_A, SUBJECT_BOB),
                req.catalogItemId());

        AdminCatalogItemResponse revoked = catalogService.revokeCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId(),
                new AdminCatalogRevokeRequest("vendor pulled the package"));

        assertThat(revoked.status()).isEqualTo(CatalogItemStatus.REVOKED);
        assertThat(revoked.enabled()).isFalse();
        assertThat(revoked.revokedBySubject()).isEqualTo(SUBJECT_BOB);
        assertThat(revoked.revocationReason())
                .isEqualTo("vendor pulled the package");

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareCatalogService.EVENT_REVOKED);
    }

    @Test
    void revokeCatalogItemOnDraftRejectsAs409() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-revoke-draft");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.revokeCatalogItem(
                context(TENANT_A, SUBJECT_BOB), req.catalogItemId(),
                new AdminCatalogRevokeRequest("not yet approved")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    // ----------------------------------------------------------------
    // Tenant isolation

    @Test
    void getCatalogItemAcrossTenantsReturnsNotFound() {
        AdminCatalogItemRequest req = sevenZipRequest("7zip-tenant-isolation");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.getCatalogItem(
                context(TENANT_B, SUBJECT_ALICE), req.catalogItemId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void listCatalogItemsByEnabledOnlyReturnsEnabledRowsOnly() {
        // Codex 019e6a64 post-impl PARTIAL absorb #2: status==null + enabled
        // filter must apply (was silently ignored before).
        AdminCatalogItemRequest draft = sevenZipRequest("7zip-enabled-draft");
        AdminCatalogItemRequest approved =
                sevenZipRequest("7zip-enabled-approved");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), draft);
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE),
                approved);
        catalogService.approveCatalogItem(context(TENANT_A, SUBJECT_BOB),
                approved.catalogItemId());

        var enabledOnly = catalogService.listCatalogItems(
                context(TENANT_A, SUBJECT_ALICE), null, true, PageRequest.of(0, 10));
        assertThat(enabledOnly.getTotalElements()).isEqualTo(1);
        assertThat(enabledOnly.getContent().get(0).catalogItemId())
                .isEqualTo(approved.catalogItemId());

        var disabledOnly = catalogService.listCatalogItems(
                context(TENANT_A, SUBJECT_ALICE), null, false, PageRequest.of(0, 10));
        assertThat(disabledOnly.getTotalElements()).isEqualTo(1);
        assertThat(disabledOnly.getContent().get(0).catalogItemId())
                .isEqualTo(draft.catalogItemId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approveCatalogItemSelfApprovalAuditDurableAcrossOwnTransaction_noRollbackForRegression() {
        // Codex 019e6a64 post-impl PARTIAL absorb #5 / BE-014A pattern reuse
        // (EndpointMaintenanceTokenServiceTest.consumeTokenDeviceMismatch...):
        // suspend the class-level @DataJpaTest tx (NOT_SUPPORTED) so the
        // approve call runs in its OWN tx. The maker-checker reject audit is
        // emitted in the same tx as approveCatalogItem; noRollbackFor=
        // CatalogMakerCheckerViolationException keeps that tx from rolling
        // back when the 422 throws. If noRollbackFor is removed from
        // approveCatalogItem, this assertion MUST fail because the reject
        // audit row would not survive the throw.
        //
        // Codex 019e6a64 iter-2 PARTIAL absorb: the suspended-tx commits
        // real rows into the class-scoped H2; using TENANT_DURABILITY
        // (a tenant no other test in this class queries) keeps the
        // tenant-wide count assertions in this class order-independent.
        AdminCatalogItemRequest req =
                sevenZipRequest("7zip-self-approve-not-supported");
        catalogService.createCatalogItem(
                context(TENANT_DURABILITY, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.approveCatalogItem(
                context(TENANT_DURABILITY, SUBJECT_ALICE),
                req.catalogItemId()))
                .isInstanceOf(CatalogMakerCheckerViolationException.class);

        // Durability assertion outside any test tx, scoped to the dedicated
        // durability tenant: audit row must survive the 422 throw. If
        // noRollbackFor is removed from approveCatalogItem, the reject audit
        // would roll back and this assertion would FAIL.
        assertThat(auditEventTypes(TENANT_DURABILITY))
                .as("reject audit row must persist past the 422 throw "
                        + "(noRollbackFor=CatalogMakerCheckerViolationException "
                        + "invariant)")
                .contains(EndpointSoftwareCatalogService
                        .EVENT_APPROVAL_REJECTED_MAKER_CHECKER);
    }

    @Test
    void approveCatalogItemAcrossTenantsReturnsNotFound() {
        AdminCatalogItemRequest req =
                sevenZipRequest("7zip-tenant-approve-isolation");
        catalogService.createCatalogItem(context(TENANT_A, SUBJECT_ALICE), req);

        assertThatThrownBy(() -> catalogService.approveCatalogItem(
                context(TENANT_B, SUBJECT_BOB), req.catalogItemId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // ----------------------------------------------------------------
    // Helpers

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> auditEventTypes(UUID tenantId) {
        return auditRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(tenantId)
                .stream()
                .map(EndpointAuditEvent::getEventType)
                .toList();
    }

    private AdminTenantContext context(UUID tenantId, String subject) {
        return new AdminTenantContext(tenantId, subject);
    }

    private AdminCatalogItemRequest sevenZipRequest(String slug) {
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
                "Igor Pavlov",
                CatalogVersionPolicyType.LATEST,
                null,
                CatalogInstallerType.WINGET_SILENT,
                CatalogSilentArgsPolicy.DEFAULT,
                null,
                null,
                detection,
                CatalogRiskTier.LOW
        );
    }
}
