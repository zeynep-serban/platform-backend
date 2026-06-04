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
 * Faz 21.1 Cleanup C2a regression guard — V35 diff-cache org-key IDENTITY
 * (Codex 019e919e REVISE → AGREE). V35 flips the cache UPSERT identity from
 * tenant-keyed to org-keyed and adds the cache non-null org_id evidence the
 * org identity requires, WITHOUT recreating the cache FKs as org-composite
 * (deferred to C2b). It DOES drop the old tenant-keyed UNIQUE via an atomic
 * swap — a single (org_id, device_id) arbiter is required for concurrent
 * ON CONFLICT (two redundant uniques trip the non-arbiter under a race).
 *
 * <p>Asserts (both cache tables):
 * <ol>
 *   <li>cache non-null CHECK exists AND is VALIDATED (convalidated=true);</li>
 *   <li>new UNIQUE(org_id, device_id) exists;</li>
 *   <li>old UNIQUE(tenant_id, device_id) is DROPPED (single ON CONFLICT arbiter);</li>
 *   <li>the tenant-composite cache FKs are GONE — C2b/V37 flipped them to
 *       org-composite, and the cumulative schema (V1..V37) under test reflects
 *       that (the 6 org-composite FKs are asserted by V37's own IT);</li>
 *   <li>duplicate (org_id, device_id) is REJECTED (23505 unique_violation);</li>
 *   <li>a trigger-disabled org_id NULL insert is REJECTED (23514) — the
 *       cache non-null invariant actually bites.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V35DiffCacheOrgKeyIdentityPostgresIntegrationTest {

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

    /** {table, non-null-check, new-org-unique} per cache table. */
    private static final String[][] CACHE = {
            {"endpoint_software_diff_cache", "swdc_org_id_not_null", "swdc_org_id_device_id_key"},
            {"endpoint_outdated_software_diff_cache", "osdc_org_id_not_null", "osdc_org_id_device_id_key"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bothCachesHaveValidatedNonNullCheck() {
        for (String[] c : CACHE) {
            Boolean validated = jdbc.queryForObject(
                    "SELECT con.convalidated FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ? AND con.contype = 'c'",
                    Boolean.class, c[0], c[1]);
            assertThat(validated)
                    .as("%s must have VALIDATED non-null CHECK %s", c[0], c[1])
                    .isTrue();
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ?",
                    String.class, c[0], c[1]);
            assertThat(def).containsIgnoringCase("org_id IS NOT NULL");
        }
    }

    @Test
    void bothCachesHaveOrgIdDeviceIdUnique() {
        for (String[] c : CACHE) {
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ? AND con.contype = 'u'",
                    String.class, c[0], c[2]);
            assertThat(def)
                    .as("%s must have UNIQUE(org_id, device_id) %s", c[0], c[2])
                    .isEqualTo("UNIQUE (org_id, device_id)");
        }
    }

    @Test
    void bothCachesNoLongerHaveOldTenantUnique_atomicSwap() {
        // Codex 019e919e final: the old UNIQUE(tenant_id, device_id) is
        // DROPPED (not kept) — two redundant uniques break concurrent
        // ON CONFLICT on the non-arbiter index. (org_id, device_id) is the
        // sole arbiter. Rollback target must be >= V35-aware digest.
        for (String[] c : CACHE) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.contype = 'u' "
                            + "AND pg_get_constraintdef(con.oid) = 'UNIQUE (tenant_id, device_id)'",
                    Long.class, c[0]);
            assertThat(n)
                    .as("%s old UNIQUE(tenant_id, device_id) must be DROPPED (single arbiter)", c[0])
                    .isEqualTo(0L);
        }
    }

    @Test
    void bothCachesTenantCompositeForeignKeys_flippedToOrgByV37C2b() {
        // C2a/V35 kept the cache FKs tenant-composite; C2b/V37 later recreated
        // all 6 as org-composite (child_col, org_id) -> parent(id, org_id).
        // Testcontainers applies the FULL chain (V1..V37), so on the cumulative
        // schema NO tenant-composite FK remains on either cache. (The 6
        // org-composite FKs + their VALIDATE + cross-org rejection are asserted
        // by V37Source... — V37's own migration IT.)
        for (String[] c : CACHE) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.contype = 'f' "
                            + "AND pg_get_constraintdef(con.oid) ILIKE '%tenant_id%'",
                    Long.class, c[0]);
            assertThat(n)
                    .as("%s tenant-composite FKs must be GONE (flipped to org-composite by V37/C2b)", c[0])
                    .isEqualTo(0L);
        }
    }

    @Test
    void duplicateOrgIdDeviceId_isRejectedByNewUnique() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDevice(tenant);
        insertSoftwareCache(tenant, tenant, device);
        // Second row, same (org_id, device_id), different id → UNIQUE violation.
        assertThatThrownBy(() -> insertSoftwareCache(tenant, tenant, device))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("duplicate (org_id, device_id) must be 23505 unique_violation")
                        .isEqualTo("23505"));
    }

    @Test
    void nullOrgIdCacheInsert_isRejected_whenTriggerDisabled() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDevice(tenant);
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_software_diff_cache "
                + "DISABLE TRIGGER endpoint_swdc_org_id_compat");
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA
                        + ".endpoint_software_diff_cache (id, tenant_id, org_id, device_id, "
                        + "status, added_count, removed_count, version_changed_count, "
                        + "source_captured_at, source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, NULL, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, device, now, now, UUID.randomUUID(), now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("cache org_id NULL must be 23514 check_violation")
                        .isEqualTo("23514"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private void insertSoftwareCache(UUID tenant, UUID org, UUID device) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, status, added_count, "
                        + " removed_count, version_changed_count, source_captured_at, "
                        + " source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, org, device, now, now, UUID.randomUUID(), now);
    }

    private UUID seedDevice(UUID tenant) {
        UUID deviceId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                deviceId, tenant, tenant, "host-" + deviceId, "fp-" + deviceId, now, now);
        return deviceId;
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
