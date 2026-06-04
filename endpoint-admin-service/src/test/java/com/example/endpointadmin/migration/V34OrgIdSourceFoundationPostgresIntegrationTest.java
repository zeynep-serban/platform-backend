package com.example.endpointadmin.migration;

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
 * Postgres-only verification of V34 (Faz 21.1 Cleanup C1 — Source Org-Key
 * Foundation, <b>B-only</b>), the bounded migration Codex 019e919e approved.
 *
 * <p>V34 does exactly ONE additive thing: it adds {@code UNIQUE (id, org_id)}
 * on the 3 cache parents so C2 can recreate cache FKs against {@code (id,
 * org_id)}. It does NOT add a non-null CHECK, rewrite FKs, change read paths,
 * or drop tenant_id.
 *
 * <p>Why no non-null CHECK (CI-driven correction): a {@code CHECK (org_id IS
 * NOT NULL)} would make the legacy org_id-NULL row unconstructable and break
 * the PR2b-iv {@code *EffectiveOrgPostgresIntegrationTest} suite. The non-null
 * CHECK and the OR-fallback read removal are one coupled invariant flip and
 * ship together later. This test therefore <b>machine-proves V34 did NOT flip
 * that invariant</b>: a trigger-disabled {@code org_id = NULL} insert still
 * SUCCEEDS.
 *
 * <p>Assertions:
 * <ol>
 *     <li>The 3 cache parents carry {@code UNIQUE (id, org_id)}.</li>
 *     <li>Pre-existing {@code PK(id)} + {@code UNIQUE(id, tenant_id)} on
 *         endpoint_devices are intact (V34 is purely additive).</li>
 *     <li>A canonical insert (trigger fills org_id) still succeeds.</li>
 *     <li>A trigger-disabled {@code org_id = NULL} insert still SUCCEEDS —
 *         V34 did not prematurely flip the non-null invariant.</li>
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
    void legacyNullOrgIdInsert_stillSucceeds_v34DidNotFlipNonNullInvariant() {
        // The critical guard (Codex 019e919e): V34 must NOT add a non-null
        // CHECK. Disable the V29 compat trigger and insert org_id = NULL; it
        // must STILL SUCCEED (the legacy/compat scenario the OR-fallback read
        // path + the *EffectiveOrg test suite still depend on). If a future
        // PR flips the invariant, THIS test flips to a rejection test in the
        // same PR. Own @Test so the disabled trigger is restored by
        // @DataJpaTest method-end rollback.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v34-legacy-null-" + tenantId;

        jdbcTemplate.execute(
                "ALTER TABLE endpoint_devices DISABLE TRIGGER endpoint_devices_org_id_compat");

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, NULL, ?, 'LINUX')",
                tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId)
                .as("V34 must NOT forbid org_id NULL — legacy/compat insert must still succeed")
                .isNull();
    }
}
