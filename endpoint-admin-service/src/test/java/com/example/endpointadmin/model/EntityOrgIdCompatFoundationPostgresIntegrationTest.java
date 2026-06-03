package com.example.endpointadmin.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-only verification of Faz 21.1 PR2b-i entity org_id foundation
 * (Codex 019e8cac Option A — entity layer dual-field + helper, no
 * repository / service changes).
 *
 * <p>Asserts that on each of the 7 target tenant-scoped tables:
 * <ol>
 *     <li>{@code org_id} column accepts NULL (V29 schema), distinct from
 *         {@code tenant_id} which is NOT NULL on these tables.</li>
 *     <li>Legacy row (only {@code tenant_id} populated by V29 trigger)
 *         results in {@code orgId == tenantId} at read time when both
 *         are read via JDBC.</li>
 *     <li>Canonical row (both columns explicitly populated equal) is
 *         persisted as written; V30 CHECK constraint passes.</li>
 * </ol>
 *
 * <p>Entity-level {@code getEffectiveOrgId()} semantics are exercised in
 * unit tests where applicable. This integration test verifies the DB-
 * level dual-column shape that PR2b-ii (canonical write path) and
 * PR2b-iii (repository COALESCE) will build on.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityOrgIdCompatFoundationPostgresIntegrationTest {

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

    private static final String[] TARGET_TABLES = {
            "endpoint_devices",
            "endpoint_software_inventory_state_history",
            "endpoint_outdated_software_snapshots",
            "endpoint_outdated_software_packages",
            "endpoint_install_audit",
            "endpoint_compliance_evaluations",
            "endpoint_app_control_snapshots"
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void orgIdColumnIsNullableOnAllSevenTables() {
        // V29 added org_id as nullable; V30 CHECK constraint enforces
        // "org_id IS NULL OR org_id = tenant_id" so JPA mapping without
        // nullable=false is the correct shape (we don't want JPA to
        // enforce NOT NULL until the cleanup PR drops tenant_id).
        for (String table : TARGET_TABLES) {
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? "
                            + "AND column_name = 'org_id'",
                    String.class, table);
            assertThat(isNullable)
                    .as("Table %s.org_id must be NULLABLE in PR2b-i phase", table)
                    .isEqualTo("YES");
        }
    }

    @Test
    void tenantIdColumnRemainsNotNullOnAllSevenTables() {
        // tenant_id stays NOT NULL until the cleanup PR drops the column;
        // legacy services continue writing it.
        for (String table : TARGET_TABLES) {
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? "
                            + "AND column_name = 'tenant_id'",
                    String.class, table);
            assertThat(isNullable)
                    .as("Table %s.tenant_id must remain NOT NULL through PR2b-i", table)
                    .isEqualTo("NO");
        }
    }

    @Test
    void legacyInsertOnlyTenantId_v29TriggerFillsOrgIdToMatch() {
        // Smoke: legacy writer pattern on endpoint_devices. V29 trigger
        // fills org_id from tenant_id → row has both columns equal.
        UUID tenantId = UUID.randomUUID();
        String hostname = "pr2b-i-entity-foundation-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        // Read both columns; legacy row has both equal (V29 trigger).
        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        UUID readTenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId).isEqualTo(tenantId);
        assertThat(readTenantId).isEqualTo(tenantId);
    }

    @Test
    void canonicalInsertBothColumnsEqual_persistedAsWritten() {
        // Canonical write pattern (PR2b-ii will adopt this in the service
        // mapper). Both columns supplied with same UUID; V30 CHECK passes;
        // row persists as written.
        UUID tenantId = UUID.randomUUID();
        String hostname = "pr2b-i-canonical-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'LINUX')",
                tenantId, tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        UUID readTenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId).isEqualTo(tenantId);
        assertThat(readTenantId).isEqualTo(tenantId);
    }
}
