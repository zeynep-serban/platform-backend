package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogRevokeRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRevokeRequest;
import com.example.endpointadmin.exception.SoftwareBundleMakerCheckerViolationException;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.SoftwareBundleStatus;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-029 — approved package bundles control-plane tests.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointSoftwareBundleService.class,
        EndpointSoftwareCatalogService.class,
        EndpointAuditService.class,
        DetectionRuleValidator.class,
        NoOpAuditChainLock.class
})
class EndpointSoftwareBundleServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUBJECT_ALICE = "alice@example.com";
    private static final String SUBJECT_BOB = "bob@example.com";

    @Autowired
    private EndpointSoftwareBundleService bundleService;

    @Autowired
    private EndpointSoftwareCatalogService catalogService;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createBundlePersistsDraftFromApprovedCatalogItemsAndEmitsAudit() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        seedApprovedCatalogItem(TENANT_A, "notepadplusplus");

        AdminSoftwareBundleResponse response = bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("office-tools", List.of("7zip", "notepadplusplus")));

        assertThat(response.status()).isEqualTo(SoftwareBundleStatus.DRAFT);
        assertThat(response.enabled()).isFalse();
        assertThat(response.itemCount()).isEqualTo(2);
        assertThat(response.items())
                .extracting(item -> item.catalogItem().catalogItemId())
                .containsExactly("7zip", "notepadplusplus");
        assertThat(response.createdBySubject()).isEqualTo(SUBJECT_ALICE);

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareBundleService.EVENT_CREATED);
    }

    @Test
    void createBundleRejectsDuplicateCatalogItemSlugs() {
        seedApprovedCatalogItem(TENANT_A, "7zip");

        assertThatThrownBy(() -> bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("duplicate", List.of("7zip", "7zip"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Duplicate catalog item");
    }

    @Test
    void createBundleRejectsDraftCatalogItem() {
        catalogService.createCatalogItem(
                context(TENANT_A, SUBJECT_ALICE),
                catalogRequest("draft-only"));

        assertThatThrownBy(() -> bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("bad-bundle", List.of("draft-only"))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void createBundleRejectsRevokedCatalogItem() {
        seedApprovedCatalogItem(TENANT_A, "revoked-item");
        catalogService.revokeCatalogItem(
                context(TENANT_A, SUBJECT_BOB),
                "revoked-item",
                new AdminCatalogRevokeRequest("vendor pulled package"));

        assertThatThrownBy(() -> bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("bad-bundle", List.of("revoked-item"))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void approveBundleHappyPathFlipsEnabledAndEmitsAudit() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("pilot-tools", List.of("7zip")));

        AdminSoftwareBundleResponse approved = bundleService.approveBundle(
                context(TENANT_A, SUBJECT_BOB),
                "pilot-tools");

        assertThat(approved.status()).isEqualTo(SoftwareBundleStatus.APPROVED);
        assertThat(approved.enabled()).isTrue();
        assertThat(approved.approvedBySubject()).isEqualTo(SUBJECT_BOB);
        assertThat(approved.items()).hasSize(1);

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareBundleService.EVENT_APPROVED);
    }

    @Test
    void approveBundleSelfApprovalEmitsRejectAuditAndThrows() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("self-approve", List.of("7zip")));

        assertThatThrownBy(() -> bundleService.approveBundle(
                context(TENANT_A, SUBJECT_ALICE),
                "self-approve"))
                .isInstanceOf(SoftwareBundleMakerCheckerViolationException.class)
                .extracting(ex -> ((SoftwareBundleMakerCheckerViolationException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareBundleService
                        .EVENT_APPROVAL_REJECTED_MAKER_CHECKER);
        AdminSoftwareBundleResponse current = bundleService.getBundle(
                context(TENANT_A, SUBJECT_ALICE),
                "self-approve");
        assertThat(current.status()).isEqualTo(SoftwareBundleStatus.DRAFT);
        assertThat(current.enabled()).isFalse();
    }

    @Test
    void revokeBundleHappyPathDisablesAndEmitsAudit() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("retired-tools", List.of("7zip")));
        bundleService.approveBundle(context(TENANT_A, SUBJECT_BOB),
                "retired-tools");

        AdminSoftwareBundleResponse revoked = bundleService.revokeBundle(
                context(TENANT_A, SUBJECT_BOB),
                "retired-tools",
                new AdminSoftwareBundleRevokeRequest("superseded"));

        assertThat(revoked.status()).isEqualTo(SoftwareBundleStatus.REVOKED);
        assertThat(revoked.enabled()).isFalse();
        assertThat(revoked.revocationReason()).isEqualTo("superseded");

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareBundleService.EVENT_REVOKED);
    }

    @Test
    void getBundleAcrossTenantsReturnsNotFound() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("tenant-a-only", List.of("7zip")));

        assertThatThrownBy(() -> bundleService.getBundle(
                context(TENANT_B, SUBJECT_BOB),
                "tenant-a-only"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void listBundlesFiltersEnabledRowsOnly() {
        seedApprovedCatalogItem(TENANT_A, "7zip");
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("draft-bundle", List.of("7zip")));
        bundleService.createBundle(
                context(TENANT_A, SUBJECT_ALICE),
                bundleRequest("approved-bundle", List.of("7zip")));
        bundleService.approveBundle(context(TENANT_A, SUBJECT_BOB),
                "approved-bundle");

        var enabledOnly = bundleService.listBundles(
                context(TENANT_A, SUBJECT_BOB),
                null,
                true,
                PageRequest.of(0, 10));

        assertThat(enabledOnly.getTotalElements()).isEqualTo(1);
        assertThat(enabledOnly.getContent().get(0).bundleId())
                .isEqualTo("approved-bundle");
        assertThat(enabledOnly.getContent().get(0).itemCount()).isEqualTo(1);
    }

    private void seedApprovedCatalogItem(UUID tenantId, String slug) {
        catalogService.createCatalogItem(
                context(tenantId, SUBJECT_ALICE),
                catalogRequest(slug));
        catalogService.approveCatalogItem(
                context(tenantId, SUBJECT_BOB),
                slug);
    }

    private AdminSoftwareBundleRequest bundleRequest(String slug,
                                                     List<String> catalogIds) {
        return new AdminSoftwareBundleRequest(
                slug,
                "Pilot Tools",
                "Common pilot workstation tools",
                catalogIds);
    }

    private AdminCatalogItemRequest catalogRequest(String slug) {
        Map<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", slug + ".package");
        return new AdminCatalogItemRequest(
                slug,
                CatalogProvider.WINGET,
                CatalogSourceType.WINGET,
                "winget",
                CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                slug + ".package",
                slug,
                "Vendor",
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
}
