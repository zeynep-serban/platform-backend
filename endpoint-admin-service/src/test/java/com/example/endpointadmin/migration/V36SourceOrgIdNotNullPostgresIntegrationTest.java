package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

/**
 * Faz 21.1 Cleanup C1.5 regression guard — V36 source org_id NOT NULL
 * invariant flip (Codex 019e92a7 AGREE narrow + Option A). V36 makes the
 * legacy org_id-NULL row PHYSICALLY UNCONSTRUCTABLE on the 7 source tables
 * via CHECK (org_id IS NOT NULL) NOT VALID + VALIDATE. Combined with V30's
 * CHECK (org_id IS NULL OR org_id = tenant_id) it proves org_id = tenant_id
 * universally on the source side — the parent-side invariant C2b's cache FK
 * org-composite flip depends on.
 *
 * <p>This migration deliberately does NOT remove the effective-org OR-fallback
 * reads, does NOT add composite org_id mirror indexes, and does NOT touch
 * tenant_id — those ship together in C4/A5 (query canonicalize), where the
 * read-column switch's mandatory composite-index work lives. So this test
 * asserts ONLY the migration surface: the 7 CHECK constraints exist + are
 * VALIDATED, an explicit org_id-NULL write is rejected, and the V29 legacy
 * writer (tenant_id only) still succeeds via the compat trigger.
 *
 * <p>Asserts:
 * <ol>
 *   <li>all 7 source tables have a VALIDATED (convalidated=true) non-null
 *       CHECK whose definition is {@code CHECK (org_id IS NOT NULL)};</li>
 *   <li>a trigger-disabled explicit org_id NULL insert is REJECTED (23514
 *       check_violation) — the invariant actually bites (terminal assertion:
 *       a failed INSERT aborts the tx, so nothing runs after it);</li>
 *   <li>a V29 legacy writer (tenant_id set, org_id omitted/NULL) with the
 *       compat trigger ENABLED still SUCCEEDS and lands org_id = tenant_id —
 *       V36 does not break backward-compatible writers.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V36SourceOrgIdNotNullPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    /** {table, non-null-check} for the 7 source org_id-bearing tables. */
    private static final String[][] SOURCE = {
            {"endpoint_devices", "endpoint_devices_org_id_not_null"},
            {"endpoint_software_inventory_state_history", "endpoint_sw_inv_state_org_id_not_null"},
            {"endpoint_outdated_software_snapshots", "endpoint_outdated_sw_snap_org_id_not_null"},
            {"endpoint_outdated_software_packages", "endpoint_outdated_sw_pkg_org_id_not_null"},
            {"endpoint_install_audit", "endpoint_install_audit_org_id_not_null"},
            {"endpoint_compliance_evaluations", "endpoint_compliance_eval_org_id_not_null"},
            {"endpoint_app_control_snapshots", "endpoint_app_control_snap_org_id_not_null"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allSevenSourceTablesHaveValidatedNonNullCheck() {
        for (String[] s : SOURCE) {
            Boolean validated = jdbc.queryForObject(
                    "SELECT con.convalidated FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ? AND con.contype = 'c'",
                    Boolean.class, s[0], s[1]);
            assertThat(validated)
                    .as("%s must have VALIDATED non-null CHECK %s", s[0], s[1])
                    .isTrue();
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ?",
                    String.class, s[0], s[1]);
            // pg_get_constraintdef normalizes the predicate with extra parens
            // (e.g. "CHECK ((org_id IS NOT NULL))"); assert on the semantic
            // content, mirroring the V35 cache-CHECK test pattern.
            assertThat(def)
                    .as("%s CHECK %s definition", s[0], s[1])
                    .containsIgnoringCase("org_id IS NOT NULL");
        }
    }

    @Test
    void explicitNullOrgIdSourceInsert_isRejected_whenTriggerDisabled() {
        // Terminal assertion (Codex 019e92a7 transaction-hygiene): a failed
        // INSERT aborts the Postgres tx, so this runs nothing afterward.
        // Disable ONLY the V29 compat trigger so org_id stays the explicit
        // NULL we pass (rather than being filled from tenant_id), proving the
        // V36 CHECK — not a NOT NULL column default — is what rejects it.
        UUID tenant = UUID.randomUUID();
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices "
                + "DISABLE TRIGGER endpoint_devices_org_id_compat");
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                UUID.randomUUID(), tenant, "host-null", "fp-null", now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("explicit org_id NULL must be 23514 check_violation")
                        .isEqualTo("23514"));
    }

    @Test
    void legacyWriter_orgIdNull_passesViaV29Trigger_fillingOrgIdFromTenant() {
        // V29 BEFORE INSERT trigger fills org_id = tenant_id when the caller
        // leaves org_id NULL (legacy writer). With the trigger ENABLED
        // (default), V36's CHECK sees the filled value and passes — V36 does
        // not break backward-compatible writers.
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, "host-legacy", "fp-legacy", now, now);
        UUID orgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA + ".endpoint_devices WHERE id = ?",
                UUID.class, id);
        assertThat(orgId)
                .as("V29 trigger must fill org_id = tenant_id for a legacy writer")
                .isEqualTo(tenant);
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
