package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointComplianceEvaluationRepository;
import com.example.endpointadmin.repository.EndpointDeviceComplianceStateRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCompliancePolicyItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-023 — PostgreSQL-only migration + tenant-integrity integration
 * tests for {@code V10__endpoint_compliance_state.sql} (Faz 22.5).
 *
 * <p>Codex 019e6bbf iter-3 critical_finding #2: cross-tenant catalog
 * reference must be physically rejected by the DB layer, not the
 * service layer. This test class encodes that contract.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointComplianceStatePostgresIntegrationTest {

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

    @Autowired
    private EndpointSoftwareCompliancePolicyItemRepository policyRepository;
    @Autowired
    private EndpointComplianceEvaluationRepository evaluationRepository;
    @Autowired
    private EndpointDeviceComplianceStateRepository stateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayLiftsSchemaToV10AndHibernateValidatesAgainstIt() {
        // Context start with ddl-auto=validate is the primary
        // assertion: every entity column must line up with the V10
        // schema or the application context refuses to load.
        assertThat(policyRepository.count()).isZero();
        assertThat(evaluationRepository.count()).isZero();
        assertThat(stateRepository.count()).isZero();
    }

    @Test
    void v10DeclaresCompositeFkOnPolicyToCatalog() {
        List<String> foreignKeys = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_compliance_policy_items'::regclass "
                        + "AND contype = 'f'",
                String.class);
        assertThat(foreignKeys).contains(
                "fk_endpoint_software_compliance_policy_items_catalog");

        // The composite FK targets two columns. pg_constraint.confkey
        // is the array of column ordinals on the target relation.
        List<Object> confKeys = jdbcTemplate.queryForList(
                "SELECT confkey FROM pg_catalog.pg_constraint "
                        + "WHERE conname = 'fk_endpoint_software_compliance_policy_items_catalog'",
                Object.class);
        assertThat(confKeys).hasSize(1);
        // Sanity: column ordinal array length == 2 (id + tenant_id pair).
        assertThat(confKeys.get(0).toString()).matches("\\{\\d+,\\d+\\}");
    }

    @Test
    void v10DeclaresExpectedCheckAndUniqueConstraints() {
        List<String> checks = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid IN ("
                        + "'public.endpoint_software_compliance_policy_items'::regclass,"
                        + "'public.endpoint_compliance_evaluations'::regclass,"
                        + "'public.endpoint_device_compliance_states'::regclass) "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_software_compliance_policy_items_mode",
                "ck_endpoint_compliance_evaluations_decision",
                "ck_endpoint_device_compliance_states_decision");

        List<String> uniques = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid IN ("
                        + "'public.endpoint_software_compliance_policy_items'::regclass,"
                        + "'public.endpoint_software_catalog_items'::regclass) "
                        + "AND contype = 'u'",
                String.class);
        assertThat(uniques).contains(
                "uq_endpoint_software_catalog_items_id_tenant",
                "uq_endpoint_software_compliance_policy_items_tenant_catalog");
    }

    @Test
    void compositeFkRejectsCrossTenantCatalogReference() {
        // Postgres aborts the @DataJpaTest-managed transaction as soon
        // as a constraint violation fires (SQL state 25P02 — "current
        // transaction is aborted, commands ignored until end of
        // transaction block"). The reject side and the same-tenant
        // happy-path are therefore split into two separate @Test
        // methods so each runs in its own transaction.
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Seed a catalog row owned by tenant A.
        UUID catalogA = UUID.randomUUID();
        insertCatalog(tenantA, catalogA, "tenantA.app");

        // Attempt to insert a policy row in tenant B that references
        // the catalog row of tenant A. The composite FK
        // (catalog_item_id, tenant_id) -> endpoint_software_catalog_items
        // (id, tenant_id) must reject the INSERT.
        assertThatThrownBy(() -> insertPolicy(tenantB, catalogA))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void policyAcceptsSameTenantCatalogReference() {
        UUID tenantA = UUID.randomUUID();
        UUID catalogA = UUID.randomUUID();
        insertCatalog(tenantA, catalogA, "tenantA.app");

        UUID validPolicyId = insertPolicy(tenantA, catalogA);
        assertThat(validPolicyId).isNotNull();

        // Verify the row landed via the FK pair, not just by primary key.
        Long countMatching = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM endpoint_software_compliance_policy_items "
                        + "WHERE id = ? AND tenant_id = ? AND catalog_item_id = ?",
                Long.class, validPolicyId, tenantA, catalogA);
        assertThat(countMatching).isEqualTo(1L);
    }

    @Test
    void advisoryLockCanBeAcquiredAndReleased() {
        // Smoke test: pg_try_advisory_xact_lock is available on this
        // Postgres container. The real concurrency contention is
        // exercised by the application service under load; here we
        // just prove the SQL surface the service depends on is
        // present.
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class, 0xBE23000000000000L);
        assertThat(acquired).isTrue();
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private UUID seedDevice(UUID tenantId) {
        UUID id = UUID.randomUUID();
        // PG JDBC driver cannot infer the SQL type for java.time.Instant
        // via JdbcTemplate setObject; convert to java.sql.Timestamp so
        // the prepared-statement binding has an explicit Types.TIMESTAMP.
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, machine_fingerprint, status, "
                        + " os_version, agent_version, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, "host-" + id, "fp-" + id, "ONLINE",
                "Windows 11", "1.0.0", now, now, 0L);
        return id;
    }

    private void insertCatalog(UUID tenantId, UUID catalogId, String packageId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, version_policy_value, installer_type,"
                        + " silent_args_policy, sha256, provenance, detection_rule,"
                        + " risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, ?, 'LATEST', NULL,"
                        + " 'WINGET_SILENT', 'DEFAULT', NULL, NULL,"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                catalogId, tenantId, packageId, packageId, "DisplayName", "Publisher",
                now, now);
    }

    private UUID insertPolicy(UUID tenantId, UUID catalogId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO endpoint_software_compliance_policy_items "
                        + "(id, tenant_id, catalog_item_id, enforcement_mode, enabled, "
                        + " created_by_subject, created_at, last_updated_by_subject, "
                        + " last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'REQUIRED', true, 'creator', ?, 'creator', ?, 0)",
                id, tenantId, catalogId, now, now);
        return id;
    }
}
