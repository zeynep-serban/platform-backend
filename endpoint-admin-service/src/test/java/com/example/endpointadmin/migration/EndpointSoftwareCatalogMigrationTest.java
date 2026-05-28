package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-020 — V7 migration sanity test (Codex 019e6a3e iter-2 acceptance #1).
 *
 * <p>Verifies that Flyway lifts the schema to V7 cleanly, Hibernate
 * {@code validate} mode is happy against the resulting table, and a
 * round-trip persist works.
 *
 * <p>Note on test runtime: this slice runs on the embedded H2 database (the
 * default for {@code @DataJpaTest}); the deeper PostgreSQL-specific CHECK
 * constraint enforcement is exercised functionally by
 * {@code EndpointSoftwareCatalogServiceTest} (raw command rule reject, provider
 * mismatch reject, etc.) and on real PG by {@code *IntegrationTest} suites.
 * Asserting the catalog of {@code pg_catalog.pg_constraint} would couple this
 * test to PostgreSQL and break the H2-backed test slice contract.
 */
@IsolatedH2DataJpaTest
class EndpointSoftwareCatalogMigrationTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointSoftwareCatalogItemRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void v7MigrationContextStartsAndEntityRepositoryWires() {
        // If Hibernate's `validate` mode disagrees with the V7 layout, the
        // application-test context would have failed to start; reaching this
        // line is itself a positive signal that the schema + entity mapping
        // agree.
        //
        // The fresh-table assertion below also doubles as behavioural evidence
        // that the per-context H2 isolation introduced by
        // @IsolatedH2DataJpaTest holds: a previous BE-020I regression saw
        // EndpointSoftwareCatalogServiceTest's NOT_SUPPORTED rollback leak a
        // TENANT_DURABILITY catalog row onto this class via the shared
        // jdbc:h2:mem:endpointadmin instance, which made repository.count()
        // non-zero in CI runs whose JVM-hashed test order placed that suite
        // first. Each distinct Spring context boot under
        // @IsolatedH2DataJpaTest now gets its own UUID-named H2 instance, so
        // no sibling class can land rows here.
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void v7TableAcceptsValidDraftRoundTrip() {
        EndpointSoftwareCatalogItem item = new EndpointSoftwareCatalogItem();
        item.setTenantId(TENANT_A);
        item.setCatalogItemId("7zip-7zip-winget-stable");
        item.setStatus(CatalogItemStatus.DRAFT);
        item.setProvider(CatalogProvider.WINGET);
        item.setSourceType(CatalogSourceType.WINGET);
        item.setSourceName("winget");
        item.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        item.setPackageId("7zip.7zip");
        item.setDisplayName("7-Zip");
        item.setPublisher("Igor Pavlov");
        item.setVersionPolicyType(CatalogVersionPolicyType.LATEST);
        item.setRiskTier(CatalogRiskTier.LOW);
        item.setCreatedBySubject("alice@example.com");
        item.setLastUpdatedBySubject("alice@example.com");
        HashMap<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        item.setDetectionRule(detection);

        EndpointSoftwareCatalogItem saved = repository.saveAndFlush(item);

        entityManager.clear();
        EndpointSoftwareCatalogItem reloaded = repository
                .findByTenantIdAndCatalogItemId(TENANT_A,
                        "7zip-7zip-winget-stable")
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CatalogItemStatus.DRAFT);
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getLastUpdatedAt()).isNotNull();
        assertThat(reloaded.getDetectionRule())
                .containsEntry("type", "WINGET_PACKAGE")
                .containsEntry("wingetPackageId", "7zip.7zip");
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(saved.getId()).isEqualTo(reloaded.getId());
    }

    @Test
    void v7TableAcceptsApprovedTransitionWithDistinctMakerChecker() {
        EndpointSoftwareCatalogItem item = new EndpointSoftwareCatalogItem();
        item.setTenantId(TENANT_A);
        item.setCatalogItemId("7zip-approved-roundtrip");
        item.setStatus(CatalogItemStatus.APPROVED);
        item.setProvider(CatalogProvider.WINGET);
        item.setSourceType(CatalogSourceType.WINGET);
        item.setSourceName("winget");
        item.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        item.setPackageId("7zip.7zip");
        item.setDisplayName("7-Zip");
        item.setPublisher("Igor Pavlov");
        item.setVersionPolicyType(CatalogVersionPolicyType.LATEST);
        item.setRiskTier(CatalogRiskTier.LOW);
        item.setCreatedBySubject("alice@example.com");
        item.setLastUpdatedBySubject("bob@example.com");
        item.setApprovedBySubject("bob@example.com");
        item.setApprovedAt(Instant.now());
        item.setEnabled(true);
        HashMap<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        item.setDetectionRule(detection);

        EndpointSoftwareCatalogItem saved = repository.saveAndFlush(item);

        assertThat(saved.getStatus()).isEqualTo(CatalogItemStatus.APPROVED);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getApprovedBySubject()).isEqualTo("bob@example.com");
    }
}
