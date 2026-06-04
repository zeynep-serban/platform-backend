package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iii regression guard — the device-grid {@code /query}
 * native SQL MUST resolve both <em>canonical</em> rows (org_id populated
 * post-PR2b-ii) and <em>legacy</em> rows (org_id NULL with tenant_id
 * still set — possible if V29 trigger has not yet filled, defensive read
 * path).
 *
 * <p>Codex thread 019e8cd4 plan-time AGREE — the canonical effective-org
 * filter is the parenthesized OR:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 * </pre>
 *
 * <p>This test pins six behavioural assertions:
 * <ol>
 *   <li>Canonical row read — both columns equal, page query returns it.</li>
 *   <li>Legacy NULL row read — V29 trigger temporarily disabled to seed
 *       a row with {@code org_id IS NULL AND tenant_id = orgA}; page
 *       query returns it via the legacy fallback branch of the OR.</li>
 *   <li>Pre-assert NULL fixture — the seed actually persisted with
 *       {@code org_id IS NULL} (trigger DISABLE worked, no silent fill).</li>
 *   <li>Cross-tenant negative — orgA filter returns ZERO orgB rows.</li>
 *   <li>LEFT JOIN cache miss — device cache absent → row still returned,
 *       cache columns are NULL (authoritative "not yet computed").</li>
 *   <li>LEFT JOIN cache wrong-attach negative — a cache row belonging to
 *       orgB's device is never attached to orgA's device row.</li>
 * </ol>
 *
 * <p>The dedicated PG IT closes Codex 019e8cd4's "AGREE conditional on
 * PR2b-ii deploy + live evidence" gate — the runtime behaviour assertion
 * cannot be done with the unit-level {@code DeviceGridQueryBuilderTest}
 * (which only verifies the emitted SQL string shape, not its actual
 * semantics against a live Postgres engine).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceGridCanonicalOrgIdFilterPostgresIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbc;

    private DeviceGridQueryService service() {
        // @DataJpaTest does not load @Service/@Component beans; wire the
        // grid query stack manually against the live (non-public-schema)
        // DataSource. Schema pinned to the canonical live value.
        DeviceGridQueryBuilder builder =
                new DeviceGridQueryBuilder(SCHEMA, 200, 200, 200);
        return new DeviceGridQueryService(new NamedParameterJdbcTemplate(jdbc.getDataSource()), builder);
    }

    private DeviceGridQueryRequest emptyReq() {
        return new DeviceGridQueryRequest(0, 50, null, null, null);
    }

    // ───────────────────────── Assertion 1: canonical row read ─────────────────────────

    @Test
    void canonicalRow_orgIdEqualsTenantId_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceCanon = UUID.randomUUID();
        // Canonical write: BOTH org_id and tenant_id populated equally
        // (PR2b-ii service-layer pattern). V29 trigger is a no-op here
        // because NEW.org_id IS NOT NULL.
        insertDeviceCanonical(deviceCanon, orgA, "canonical-device");

        DeviceGridQueryResponse resp = service().query(orgA, emptyReq());

        assertThat(resp.rows())
                .as("orgA effective-org filter returns the canonical row")
                .anyMatch(r -> deviceCanon.equals(r.get("device_id")));
    }

    // ───────────────────────── Assertion 2: legacy NULL fixture rejected by V36 ─────────────────────────

    @Test
    void legacyNullRow_orgIdNullInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on endpoint_devices (CHECK org_id IS NOT NULL). The
        // pre-V36 legacy-NULL grid-visibility fixture is replaced by its
        // invariant proof — a trigger-bypass org_id-NULL insert is rejected
        // 23514. The grid's OR-fallback source filter stays intact (provably
        // dead until A5 removes it with composite org_id mirror indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceLegacy = UUID.randomUUID();
        assertDeviceLegacyNullOrgInsertRejectedByV36(deviceLegacy, orgA, "legacy-null-device");
    }

    // ───────────────────────── Assertion 4: cross-tenant negative ─────────────────────────

    @Test
    void crossTenant_orgAFilter_doesNotReturnOrgBRows() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "orga-device");
        insertDeviceCanonical(deviceB, orgB, "orgb-device");

        DeviceGridQueryResponse resp = service().query(orgA, emptyReq());

        assertThat(resp.rows())
                .as("orgA filter contains its own row")
                .anyMatch(r -> deviceA.equals(r.get("device_id")));
        assertThat(resp.rows())
                .as("orgA filter MUST NOT contain orgB rows (cross-tenant leak)")
                .noneMatch(r -> deviceB.equals(r.get("device_id")));
    }

    // ───────────────────────── Assertion 5: LEFT JOIN cache miss ─────────────────────────

    @Test
    void leftJoinCacheMiss_deviceWithoutCacheRow_isReturned_withNullCacheColumns() {
        UUID orgA = UUID.randomUUID();
        UUID deviceNoCache = UUID.randomUUID();
        insertDeviceCanonical(deviceNoCache, orgA, "no-cache-device");
        // Intentionally NO INSERT into endpoint_software_diff_cache /
        // endpoint_outdated_software_diff_cache for deviceNoCache.

        DeviceGridQueryResponse resp = service().query(orgA, emptyReq());

        Map<String, Object> row = resp.rows().stream()
                .filter(r -> deviceNoCache.equals(r.get("device_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no-cache device row missing from effective-org page"));
        assertThat(row.get("software_diff_added_count"))
                .as("cache miss → software_diff_added_count is null "
                        + "(authoritative 'not yet computed', NOT 0)")
                .isNull();
        assertThat(row.get("outdated_diff_added_count"))
                .as("cache miss → outdated_diff_added_count is null")
                .isNull();
        assertThat(row.get("software_diff_status"))
                .as("cache miss → software_diff_status is null")
                .isNull();
    }

    // ───────────────────────── Assertion 6: cache wrong-attach negative ─────────────────────────

    @Test
    void leftJoinCacheWrongAttach_orgBCacheRow_neverAttachesToOrgADevice() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "orga-cached-device");
        insertDeviceCanonical(deviceB, orgB, "orgb-cached-device");
        // Seed orgB's cache row only — orgA's deviceA has NO cache row.
        // The LEFT JOIN ON-clause "sdc.tenant_id = d.tenant_id AND
        // sdc.device_id = d.id" must NOT attach orgB's cache to deviceA.
        // Use status=NO_HISTORY (V27 invariant — counts must all be zero,
        // both source ids NULL); the contamination signal we look for is
        // status leaking from orgB→orgA, not the count values.
        insertSoftwareDiffCacheNoHistory(deviceB, orgB);

        DeviceGridQueryResponse resp = service().query(orgA, emptyReq());

        Map<String, Object> rowA = resp.rows().stream()
                .filter(r -> deviceA.equals(r.get("device_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "orgA deviceA row missing from page query"));
        assertThat(rowA.get("software_diff_status"))
                .as("orgB cache row MUST NOT bleed status to orgA device "
                        + "(cross-tenant LEFT JOIN ON-clause integrity); "
                        + "if this leaks then the multi-key ON guard "
                        + "'sdc.tenant_id = d.tenant_id' is broken")
                .isNull();
        assertThat(rowA.get("software_diff_added_count"))
                .as("orgB cache row added_count MUST NOT bleed to orgA "
                        + "(NULL = cache miss; bleeding would surface as 0)")
                .isNull();
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDeviceCanonical(UUID id, UUID org, String hostname) {
        // PR2b-ii canonical pattern: both org_id and tenant_id = same UUID.
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, now, now);
    }

    private void assertDeviceLegacyNullOrgInsertRejectedByV36(UUID id, UUID tenant, String hostname) {
        // Disable the V29 compat trigger so org_id stays the explicit NULL we
        // pass (proving the V36 CHECK, not the trigger, rejects it). Terminal
        // assertion (Codex 019e92a7 transaction-hygiene): the failed INSERT
        // aborts the tx, so there is no ENABLE-TRIGGER finally and nothing runs
        // after — the @DataJpaTest rollback re-enables the trigger.
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("source org_id NULL must be 23514 check_violation")
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

    private void insertSoftwareDiffCacheNoHistory(UUID deviceId, UUID org) {
        // V27 + V28 endpoint_software_diff_cache canonical schema:
        //   id, tenant_id, device_id, from_history_id NULL, to_history_id NULL,
        //   status NOT NULL (OK/NO_CHANGE/INSUFFICIENT_HISTORY/NO_HISTORY),
        //   added_count + removed_count + version_changed_count NOT NULL DEFAULT 0,
        //   computed_at NOT NULL, created_at NOT NULL DEFAULT now(),
        //   source_captured_at + source_created_at + source_row_id NOT NULL (V28).
        // Status shape invariant: NO_HISTORY → counts zero + both source ids NULL +
        // V28 source-order tuple uses epoch/zero-UUID sentinel ("always older").
        // org_id NOT on cache table per Codex 019e8cd4 §4 (cache canonicalization
        // is PR2c scope, separately).
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T11:00:00Z"));
        Timestamp epoch = Timestamp.from(Instant.parse("1970-01-01T00:00:00Z"));
        UUID zeroUuid = UUID.fromString("00000000-0000-0000-0000-000000000000");
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, "
                        + " from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " computed_at, "
                        + " source_captured_at, source_created_at, source_row_id) "
                        + "VALUES (?, ?, ?, NULL, NULL, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), org, deviceId, now, epoch, epoch, zeroUuid);
    }
}
