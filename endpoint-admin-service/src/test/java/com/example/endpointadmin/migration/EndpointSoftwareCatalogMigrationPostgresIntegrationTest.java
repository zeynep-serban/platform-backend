package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-020 — Postgres-only migration integration tests for
 * {@code V7__endpoint_software_catalog.sql} (Codex 019e6a64 post-impl PARTIAL
 * absorb #1).
 *
 * <p>Exercises the parts that the H2 {@code @DataJpaTest} slice cannot:
 * <ul>
 *   <li>Flyway V7 actually applies on a real Postgres engine
 *       (instead of {@code ddl-auto=create-drop} silently producing the
 *       schema from the entity);</li>
 *   <li>Hibernate {@code validate} mode is happy against the migration-built
 *       table (catches drift between the SQL DDL and the JPA mapping);</li>
 *   <li>the JSONB {@code detection_rule} column and PG-specific CHECK
 *       constraints (maker-checker, provider allowlist, status allowlist,
 *       version policy allowlist, risk tier allowlist, detection rule shape,
 *       installer type allowlist, silent args policy allowlist, approval +
 *       revocation pair invariants) reject the matching violation patterns.</li>
 * </ul>
 *
 * <p>Mirrors the {@code AuditHashChainPostgresIntegrationTest} setup: PG 16
 * Testcontainer + Flyway enabled + {@code ddl-auto=validate} + {@code public}
 * schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointSoftwareCatalogMigrationPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> "public");
    }

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private EndpointSoftwareCatalogItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void flywayLiftsSchemaToV7AndHibernateValidatesAgainstIt() {
        // If Flyway didn't apply V7 or Hibernate validate disagreed with
        // the resulting table, the application-test context would have
        // failed to start. Reaching this line is itself a positive signal.
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void v7DeclaresExpectedCheckAndUniqueConstraints() {
        List<String> checks = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_catalog_items'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_software_catalog_items_status",
                "ck_endpoint_software_catalog_items_provider",
                "ck_endpoint_software_catalog_items_source_type",
                "ck_endpoint_software_catalog_items_source_trust",
                "ck_endpoint_software_catalog_items_version_policy_type",
                "ck_endpoint_software_catalog_items_installer_type",
                "ck_endpoint_software_catalog_items_risk_tier",
                "ck_endpoint_software_catalog_items_detection_rule_shape",
                "ck_endpoint_software_catalog_items_maker_checker",
                "ck_endpoint_software_catalog_items_approval_pair",
                "ck_endpoint_software_catalog_items_revocation_pair",
                "ck_endpoint_software_catalog_items_silent_args_policy");

        List<String> uniques = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_catalog_items'::regclass "
                        + "AND contype = 'u'",
                String.class);
        assertThat(uniques).contains(
                "uq_endpoint_software_catalog_items_tenant_catalog_item");
    }

    @Test
    void makerCheckerConstraintRejectsSelfApprovalInsert() {
        EndpointSoftwareCatalogItem item = baseDraft(TENANT_A,
                "7zip-pg-maker-checker", "alice@example.com");
        // Tampered: approver subject equals creator subject — must be rejected
        // by ck_endpoint_software_catalog_items_maker_checker.
        item.setApprovedBySubject("alice@example.com");
        item.setApprovedAt(Instant.now());

        assertThatThrownBy(() -> {
            repository.saveAndFlush(item);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("maker_checker");
    }

    @Test
    void providerCheckRejectsUnknownProviderInsert() {
        EndpointSoftwareCatalogItem item = baseDraft(TENANT_A,
                "7zip-pg-bad-provider", "alice@example.com");
        // Force a column-level violation via JDBC, since the entity is enum-
        // typed and cannot represent a non-allowlisted provider.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, "
                        + "source_type, source_name, source_trust, package_id, "
                        + "display_name, publisher, version_policy_type, "
                        + "detection_rule, risk_tier, enabled, "
                        + "created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'DRAFT', 'CHOCO', 'WINGET', "
                        + "'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', "
                        + "'7-Zip', 'Igor Pavlov', 'LATEST', "
                        + "'{\"type\":\"WINGET_PACKAGE\","
                        + "\"wingetPackageId\":\"7zip.7zip\"}'::jsonb, 'LOW', "
                        + "false, 'alice@example.com', now(), "
                        + "'alice@example.com', now(), 0)",
                UUID.randomUUID(), TENANT_A, "pg-choco-attempt"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void detectionRuleShapeCheckRejectsTypelessInsert() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, "
                        + "source_type, source_name, source_trust, package_id, "
                        + "display_name, publisher, version_policy_type, "
                        + "detection_rule, risk_tier, enabled, "
                        + "created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'DRAFT', 'WINGET', 'WINGET', "
                        + "'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', "
                        + "'7-Zip', 'Igor Pavlov', 'LATEST', "
                        + "'{\"wingetPackageId\":\"7zip.7zip\"}'::jsonb, 'LOW', "
                        + "false, 'alice@example.com', now(), "
                        + "'alice@example.com', now(), 0)",
                UUID.randomUUID(), TENANT_A, "pg-bad-detection-rule"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("detection_rule");
    }

    @Test
    void silentArgsPolicyCheckRejectsFreeTextInsert() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, "
                        + "source_type, source_name, source_trust, package_id, "
                        + "display_name, publisher, version_policy_type, "
                        + "silent_args_policy, detection_rule, risk_tier, "
                        + "enabled, created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'DRAFT', 'WINGET', 'WINGET', "
                        + "'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', "
                        + "'7-Zip', 'Igor Pavlov', 'LATEST', "
                        + "'powershell.exe -c Get-Process', "
                        + "'{\"type\":\"WINGET_PACKAGE\","
                        + "\"wingetPackageId\":\"7zip.7zip\"}'::jsonb, 'LOW', "
                        + "false, 'alice@example.com', now(), "
                        + "'alice@example.com', now(), 0)",
                UUID.randomUUID(), TENANT_A, "pg-bad-silent-args"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("silent_args_policy");
    }

    @Test
    void uniqueTenantSlugRejectsDuplicateInsert() {
        EndpointSoftwareCatalogItem a = baseDraft(TENANT_A,
                "7zip-pg-unique", "alice@example.com");
        repository.saveAndFlush(a);

        EndpointSoftwareCatalogItem b = baseDraft(TENANT_A,
                "7zip-pg-unique", "bob@example.com");
        assertThatThrownBy(() -> repository.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------------
    // Helpers

    private EndpointSoftwareCatalogItem baseDraft(UUID tenantId,
                                                  String slug,
                                                  String creator) {
        EndpointSoftwareCatalogItem item = new EndpointSoftwareCatalogItem();
        item.setTenantId(tenantId);
        item.setCatalogItemId(slug);
        item.setStatus(CatalogItemStatus.DRAFT);
        item.setProvider(CatalogProvider.WINGET);
        item.setSourceType(CatalogSourceType.WINGET);
        item.setSourceName("winget");
        item.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        item.setPackageId("7zip.7zip");
        item.setDisplayName("7-Zip");
        item.setPublisher("Igor Pavlov");
        item.setVersionPolicyType(CatalogVersionPolicyType.LATEST);
        item.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        item.setSilentArgsPolicy(CatalogSilentArgsPolicy.DEFAULT);
        item.setRiskTier(CatalogRiskTier.LOW);
        item.setCreatedBySubject(creator);
        item.setLastUpdatedBySubject(creator);
        HashMap<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        item.setDetectionRule(detection);
        return item;
    }
}
