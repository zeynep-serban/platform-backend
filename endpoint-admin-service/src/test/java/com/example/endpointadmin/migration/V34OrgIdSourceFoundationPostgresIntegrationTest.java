package com.example.endpointadmin.migration;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres-only verification of V34 (Faz 21.1 Cleanup C1 — Source Org-Key
 * Foundation, <b>B-only</b>), the bounded migration Codex 019e919e approved.
 *
 * <p>V34 does exactly ONE additive thing: it adds {@code UNIQUE (id, org_id)}
 * on the 3 cache parents so C2 can recreate cache FKs against {@code (id,
 * org_id)}. It does NOT add a non-null CHECK, rewrite FKs, change read paths,
 * or drop tenant_id.
 *
 * <p>Why V34 itself adds no non-null CHECK: a {@code CHECK (org_id IS NOT
 * NULL)} would make the legacy org_id-NULL row unconstructable. V34 stays
 * additive and defers the non-null flip. That flip later landed in <b>V36
 * (C1.5)</b>. Because Testcontainers applies the FULL chain (V1..V36), the
 * cumulative schema under test now carries V36's CHECK — so the trigger-bypass
 * {@code org_id = NULL} insert below is REJECTED 23514 (the "flips to a
 * rejection test in the same PR" the original V34 guard anticipated; V36 is
 * that PR). The V34-additive assertions (UNIQUE / PK intact / canonical
 * insert) remain unchanged and continue to prove V34's own bounded scope.
 *
 * <p>Assertions:
 * <ol>
 *     <li>The 3 cache parents carry {@code UNIQUE (id, org_id)}.</li>
 *     <li>Pre-existing {@code PK(id)} + {@code UNIQUE(id, tenant_id)} on
 *         endpoint_devices are intact (V34 is purely additive).</li>
 *     <li>A canonical insert (trigger fills org_id) still succeeds.</li>
 *     <li>A trigger-disabled {@code org_id = NULL} insert is REJECTED 23514
 *         by the cumulative V36 CHECK (org_id IS NOT NULL).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V34OrgIdSourceFoundationPostgresIntegrationTest {

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

    /** The 3 cache parents + their V34 UNIQUE (id, org_id) constraint name. */
    private static final String[][] UNIQUE_PARENTS = {
            {"endpoint_devices", "endpoint_devices_id_org_id_key"},
            {"endpoint_software_inventory_state_history", "endpoint_sw_inv_state_id_org_id_key"},
            {"endpoint_outdated_software_snapshots", "endpoint_outdated_sw_snap_id_org_id_key"}
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void threeCacheParentsHaveIdOrgIdUniqueConstraint() {
        // C2 cache org-key flip recreates FKs (child_col, org_id) ->
        // parent(id, org_id); that requires a UNIQUE on exactly (id, org_id).
        for (String[] row : UNIQUE_PARENTS) {
            String table = row[0];
            String constraint = row[1];
            String definition = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ? AND c.contype = 'u'",
                    String.class, table, constraint);
            assertThat(definition)
                    .as("Table %s must have UNIQUE (id, org_id) constraint %s", table, constraint)
                    .isNotNull()
                    .containsIgnoringCase("UNIQUE")
                    .containsIgnoringCase("id")
                    .containsIgnoringCase("org_id");
        }
    }

    @Test
    void preExistingDeviceConstraints_areIntact_v34IsAdditive() {
        // V34 must not disturb the pre-existing PK(id) + UNIQUE(id, tenant_id)
        // on endpoint_devices; the cleanup is additive only.
        Long pkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "WHERE t.relname = 'endpoint_devices' AND c.contype = 'p' "
                        + "AND pg_get_constraintdef(c.oid) = 'PRIMARY KEY (id)'",
                Long.class);
        assertThat(pkCount)
                .as("endpoint_devices PRIMARY KEY (id) must still exist after V34")
                .isEqualTo(1L);

        Long uniqueIdTenantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "WHERE t.relname = 'endpoint_devices' AND c.contype = 'u' "
                        + "AND pg_get_constraintdef(c.oid) = 'UNIQUE (id, tenant_id)'",
                Long.class);
        assertThat(uniqueIdTenantCount)
                .as("endpoint_devices UNIQUE (id, tenant_id) must still exist after V34")
                .isEqualTo(1L);
    }

    @Test
    void canonicalInsert_stillSucceeds_v34IsNonBreaking() {
        // Legacy writer (tenant_id only): V29 trigger fills org_id; V30
        // (org_id = tenant_id) CHECK passes; V34 added no new write barrier.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v34-canonical-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId)
                .as("canonical insert must still succeed and org_id = tenant_id (non-breaking)")
                .isEqualTo(tenantId);
    }

    @Test
    void legacyNullOrgIdInsert_nowRejectedByCumulativeV36Check() {
        // The original V34 guard (Codex 019e919e) proved V34 itself did NOT
        // add a non-null CHECK, asserting this trigger-bypass org_id = NULL
        // insert STILL SUCCEEDED — and explicitly anticipated: "If a future PR
        // flips the invariant, THIS test flips to a rejection test in the same
        // PR." V36 (C1.5) is that PR. Testcontainers applies the FULL chain
        // (V1..V36), so the cumulative schema now carries V36's
        // CHECK (org_id IS NOT NULL); the trigger-bypass NULL insert is
        // REJECTED 23514. Terminal assertion (Codex 019e92a7
        // transaction-hygiene): the failed INSERT aborts the tx, so nothing
        // runs after it; @DataJpaTest rollback re-enables the trigger.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v34-legacy-null-" + tenantId;

        jdbcTemplate.execute(
                "ALTER TABLE endpoint_devices DISABLE TRIGGER endpoint_devices_org_id_compat");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, NULL, ?, 'LINUX')",
                tenantId, hostname))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("source org_id NULL must be 23514 check_violation (cumulative V36)")
                        .isEqualTo("23514"));
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
