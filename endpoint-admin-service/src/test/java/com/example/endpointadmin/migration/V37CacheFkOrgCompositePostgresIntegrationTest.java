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
 * Faz 21.1 Cleanup C2b regression guard — V37 diff-cache FK org-composite flip
 * (Codex 019e9346 ready_for_impl). V37 recreates the 6 cache FOREIGN KEYs from
 * tenant-composite {@code (child_col, tenant_id) -> parent(id, tenant_id)} to
 * org-composite {@code (child_col, org_id) -> parent(id, org_id)} via an
 * add-(NOT VALID)+VALIDATE+drop atomic swap, ON DELETE CASCADE preserved. It
 * does NOT drop tenant_id (C4) and does NOT touch any repository @Query / grid
 * SQL / index (C4/A5).
 *
 * <p>Asserts:
 * <ol>
 *   <li>all 6 org-composite FKs exist AND are VALIDATED (convalidated=true)
 *       with the expected {@code (child_col, org_id) -> parent(id, org_id)}
 *       definition;</li>
 *   <li>no tenant-composite FK remains on either cache;</li>
 *   <li>every org-composite FK preserves {@code ON DELETE CASCADE};</li>
 *   <li>a cross-org cache insert (device under org A, cache row org_id = org B)
 *       is REJECTED 23503 by the device org-composite FK — the composite
 *       machine-enforces org isolation;</li>
 *   <li>the NO_HISTORY shape (null from_/to source ids) is still insertable —
 *       MATCH SIMPLE bypasses the source FKs when the source id is null;</li>
 *   <li>deleting the parent device CASCADES the dependent cache row away.</li>
 * </ol>
 *
 * <p>The device FK carries the behavioural coverage (it is the only
 * non-nullable source FK); the history/snapshot FK org-enforcement is covered
 * by the metadata assertions (#1: present + validated + correct parent).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V37CacheFkOrgCompositePostgresIntegrationTest {

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

    /** {cache table, fk name, expected org-composite def}. */
    private static final String[][] ORG_FKS = {
            {"endpoint_software_diff_cache", "swdc_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"},
            {"endpoint_software_diff_cache", "swdc_from_history_org_fk",
             "FOREIGN KEY (from_history_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_inventory_state_history(id, org_id) ON DELETE CASCADE"},
            {"endpoint_software_diff_cache", "swdc_to_history_org_fk",
             "FOREIGN KEY (to_history_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_inventory_state_history(id, org_id) ON DELETE CASCADE"},
            {"endpoint_outdated_software_diff_cache", "osdc_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"},
            {"endpoint_outdated_software_diff_cache", "osdc_from_snapshot_org_fk",
             "FOREIGN KEY (from_snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_outdated_software_snapshots(id, org_id) ON DELETE CASCADE"},
            {"endpoint_outdated_software_diff_cache", "osdc_to_snapshot_org_fk",
             "FOREIGN KEY (to_snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_outdated_software_snapshots(id, org_id) ON DELETE CASCADE"}
    };

    private static final String[] OLD_TENANT_FKS = {
            "swdc_device_fk", "swdc_from_history_fk", "swdc_to_history_fk",
            "osdc_device_fk", "osdc_from_snapshot_fk", "osdc_to_snapshot_fk"
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allSixOrgCompositeFksPresentAndValidated() {
        for (String[] fk : ORG_FKS) {
            Boolean validated = jdbc.queryForObject(
                    "SELECT con.convalidated FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ? AND con.contype = 'f'",
                    Boolean.class, fk[0], fk[1]);
            assertThat(validated)
                    .as("%s.%s must exist AND be VALIDATED", fk[0], fk[1])
                    .isTrue();
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ?",
                    String.class, fk[0], fk[1]);
            assertThat(def)
                    .as("%s.%s org-composite definition", fk[0], fk[1])
                    .isEqualTo(fk[2]);
        }
    }

    @Test
    void noTenantCompositeFkRemainsOnEitherCache() {
        for (String old : OLD_TENANT_FKS) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'f'",
                    Long.class, old);
            assertThat(n).as("old tenant FK %s must be DROPPED", old).isEqualTo(0L);
        }
        // belt-and-suspenders: no FK def on either cache still mentions tenant_id
        for (String cache : new String[]{"endpoint_software_diff_cache", "endpoint_outdated_software_diff_cache"}) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint con JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.contype = 'f' "
                            + "AND pg_get_constraintdef(con.oid) ILIKE '%tenant_id%'",
                    Long.class, cache);
            assertThat(n).as("%s must have no tenant-composite FK left", cache).isEqualTo(0L);
        }
    }

    @Test
    void everyOrgFkPreservesOnDeleteCascade() {
        for (String[] fk : ORG_FKS) {
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con "
                            + "JOIN pg_class t ON con.conrelid = t.oid "
                            + "WHERE t.relname = ? AND con.conname = ?",
                    String.class, fk[0], fk[1]);
            assertThat(def).as("%s.%s must keep ON DELETE CASCADE", fk[0], fk[1])
                    .contains("ON DELETE CASCADE");
        }
    }

    @Test
    void crossOrgCacheInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA);
        // cache row claims org B but points at org A's device → the
        // (device_id, org_id) = (deviceA, orgB) has no endpoint_devices(id, org_id)
        // parent → device org-composite FK rejects 23503.
        assertThatThrownBy(() -> insertNoHistoryCache(orgB, orgB, device))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("cross-org cache insert must be 23503 foreign_key_violation")
                        .isEqualTo("23503"));
    }

    @Test
    void noHistoryShapeWithNullSourceIds_isInsertable() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        // NO_HISTORY: from_/to_history_id null → source FKs bypassed (MATCH
        // SIMPLE); device FK (device, org) matches → insert succeeds.
        insertNoHistoryCache(org, org, device);
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_software_diff_cache WHERE device_id = ?",
                Long.class, device);
        assertThat(n).as("NO_HISTORY null-source cache row must be insertable").isEqualTo(1L);
    }

    @Test
    void deviceDelete_cascadesCacheRowAway() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        insertNoHistoryCache(org, org, device);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id = ?", device);
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_software_diff_cache WHERE device_id = ?",
                Long.class, device);
        assertThat(n).as("device delete must CASCADE the dependent cache row").isEqualTo(0L);
    }

    // ───────────────────────── helpers ─────────────────────────

    private UUID seedDevice(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private void insertNoHistoryCache(UUID tenant, UUID org, UUID device) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, status, added_count, "
                        + " removed_count, version_changed_count, source_captured_at, "
                        + " source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, org, device, now, now, UUID.randomUUID(), now);
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
