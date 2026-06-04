package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.c regression guard —
 * {@link EndpointSoftwareInventoryStateHistoryRepository}
 * latest-then-history (capped) and page-history reads migrated from
 * derived {@code findByTenantIdAndDeviceId*} to explicit {@code @Query}
 * with the canonical effective-org filter (Codex 019e8d1d B-C sub-slice
 * AGREE + 019e8dbb post-impl REVISE #1 absorb — index-friendly form
 * that keeps the (tenant_id, device_id, captured_at, created_at, id)
 * composite index usable; V30 CHECK guarantees semantic equivalence
 * with the prior parenthesized form).
 *
 * <p>Canonical predicates:
 * <pre>
 *   findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc
 *       WHERE tenant_id = :orgId
 *         AND (org_id = :orgId OR org_id IS NULL)
 *         AND device_id = :deviceId
 *       ORDER BY captured_at DESC, created_at DESC, id DESC
 *
 *   findVisibleToOrgAndDeviceId  (Page, ordering supplied by caller Pageable)
 *       WHERE tenant_id = :orgId
 *         AND (org_id = :orgId OR org_id IS NULL)
 *         AND device_id = :deviceId
 * </pre>
 *
 * <p>Five assertions on the PR2b-iv.c read-path correctness modes:
 * <ol>
 *   <li>Canonical row read (both methods): {@code org_id = tenant_id}
 *       row returned by the effective-org filter for both the LIMIT-2
 *       latest-then-history method and the paged history method.</li>
 *   <li>Legacy NULL row REJECTED by V36 (C1.5) — CHECK org_id IS NOT NULL
 *       makes it unconstructable; a trigger-bypass org_id-NULL insert is
 *       rejected 23514. The OR-fallback read branch (both methods) stays,
 *       dead until A5.</li>
 *   <li>Cross-org negative: orgB's row is never returned for orgA query,
 *       even when both orgs have a row for the same device id (cross-org
 *       device id collision is rare in production but the predicate must
 *       hold defensively).</li>
 *   <li>Latest-then-history ordering: captured_at DESC, created_at DESC,
 *       id DESC tiebreaker is honored — verified by inserting three rows
 *       with identical captured_at + created_at; the head row must be the
 *       lexicographically largest UUID.</li>
 *   <li>Page count over the effective-org predicate: countQuery sibling
 *       computes total over the predicate. Verified with two canonical
 *       rows (the prior legacy-NULL second row is unconstructable under
 *       C1.5/V36); total must be the sum.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointSoftwareInventoryStateHistoryEffectiveOrgPostgresIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> SCHEMA);
    }

    @Autowired
    private EndpointSoftwareInventoryStateHistoryRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("capturedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    // ───────────────────────── Assertion 1: canonical row read (both methods) ─────────────────────────

    @Test
    void canonicalRow_bothColumnsEqual_visibleViaBothReads() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID historyId = UUID.randomUUID();
        insertHistoryCanonical(historyId, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointSoftwareInventoryStateHistory> latestTwo = repository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 2));
        Page<EndpointSoftwareInventoryStateHistory> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(latestTwo)
                .as("canonical row visible to the LIMIT-2 latest-then-history read")
                .extracting(EndpointSoftwareInventoryStateHistory::getId)
                .containsExactly(historyId);
        assertThat(page.getContent())
                .as("canonical row visible to the Page-history read")
                .extracting(EndpointSoftwareInventoryStateHistory::getId)
                .containsExactly(historyId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    // ───────────────────────── Assertion 2: legacy NULL insert REJECTED by V36 ─────────────────────────

    @Test
    void legacyNullRow_orgIdNullInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL visibility fixture (latest-then-history +
        // Page + countQuery OR-fallback reads) is replaced by its invariant
        // proof — a trigger-bypass org_id-NULL insert is rejected 23514. The
        // OR-fallback read branches are left intact (provably dead until A5
        // removes them with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID historyId = UUID.randomUUID();
        assertHistoryLegacyNullOrgInsertRejectedByV36(historyId, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
    }

    // ───────────────────────── Assertion 3: cross-org negative ─────────────────────────

    @Test
    void crossOrg_sameDeviceId_doesNotLeakOrgBsHistory() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        UUID historyA = UUID.randomUUID();
        UUID historyB = UUID.randomUUID();
        insertHistoryCanonical(historyA, orgA, deviceA,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertHistoryCanonical(historyB, orgB, deviceB,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointSoftwareInventoryStateHistory> hitA = repository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        orgA, deviceA, PageRequest.of(0, 10));
        List<EndpointSoftwareInventoryStateHistory> hitAforDeviceB = repository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        orgA, deviceB, PageRequest.of(0, 10));
        Page<EndpointSoftwareInventoryStateHistory> pageAforDeviceB = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceB, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(hitA)
                .as("orgA sees its own row for its own device")
                .extracting(EndpointSoftwareInventoryStateHistory::getId)
                .containsExactly(historyA);
        assertThat(hitAforDeviceB)
                .as("orgA must NOT see orgB's row when asking by orgB's device id")
                .isEmpty();
        assertThat(pageAforDeviceB.getTotalElements())
                .as("countQuery must respect cross-org isolation too")
                .isEqualTo(0L);
    }

    // ───────────────────────── Assertion 4: deterministic ordering tiebreaker ─────────────────────────

    @Test
    void latestThenHistory_idDescTiebreaker_isHonoredOnIdenticalCapturedAndCreated() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        Instant capturedAt = Instant.parse("2026-06-03T10:00:00Z");
        Instant createdAt = Instant.parse("2026-06-03T10:00:01Z");

        // Three UUIDs known to compare; the head must be the lexicographically
        // largest one because the index ordering ends in id DESC.
        UUID idLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idMid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID idHigh = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        insertHistoryCanonical(idLow, orgA, deviceId, capturedAt, createdAt);
        insertHistoryCanonical(idMid, orgA, deviceId, capturedAt, createdAt);
        insertHistoryCanonical(idHigh, orgA, deviceId, capturedAt, createdAt);

        List<EndpointSoftwareInventoryStateHistory> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 1));

        assertThat(head)
                .as("ORDER BY id DESC tiebreaker → head is the largest UUID")
                .extracting(EndpointSoftwareInventoryStateHistory::getId)
                .containsExactly(idHigh);
    }

    // ───────────────────────── Assertion 5: page total + content sort over canonical rows ─────────────────────────

    @Test
    void pageHistory_countQuery_sumsCanonicalRowsForSameOrg() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        // Codex 019e8dbb post-impl REVISE absorb #3 — disjoint timestamps so
        // the page test asserts not only the count but also that the
        // caller-supplied Pageable Sort (captured_at DESC, created_at DESC,
        // id DESC) is applied to the content side of the Page. Under C1.5/V36
        // the prior legacy-NULL second row is no longer constructable; its
        // rejection is pinned by legacyNullRow_orgIdNullInsert_isRejectedByV36.
        // Two canonical rows (newer + older) retain the count + content-sort
        // coverage.
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        insertHistoryCanonical(newer, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertHistoryCanonical(older, orgA, deviceId,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));

        Page<EndpointSoftwareInventoryStateHistory> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(page.getContent())
                .as("page returns rows in HISTORY_SORT order: newer canonical "
                        + "first, older canonical second — proves the Pageable "
                        + "Sort is applied to the content side and is not "
                        + "silently dropped by the countQuery sibling")
                .extracting(EndpointSoftwareInventoryStateHistory::getId)
                .containsExactly(newer, older);
        assertThat(page.getTotalElements())
                .as("countQuery sibling totals over the effective-org predicate")
                .isEqualTo(2L);
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    /**
     * The history row has a composite FK (device_id, tenant_id) →
     * endpoint_devices (id, tenant_id), so we must seed a device first;
     * the V29 trigger keeps the device canonical.
     */
    private UUID ensureDevice(UUID tenant) {
        UUID deviceId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T09:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                deviceId, tenant, tenant,
                "host-" + deviceId,
                "fp-" + deviceId,
                now, now);
        return deviceId;
    }

    private void insertHistoryCanonical(UUID id, UUID org, UUID deviceId,
            Instant capturedAt, Instant createdAt) {
        jdbc.update("INSERT INTO " + SCHEMA
                        + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, 0, "
                        + "  '0000000000000000000000000000000000000000000000000000000000000000', "
                        + "  CAST('[]' AS jsonb), ?, ?)",
                id, org, org, deviceId,
                Timestamp.from(capturedAt),
                Timestamp.from(createdAt));
    }

    private void assertHistoryLegacyNullOrgInsertRejectedByV36(UUID id, UUID tenant,
            UUID deviceId, Instant capturedAt, Instant createdAt) {
        // Disable the V29 compat trigger so org_id stays the explicit NULL we
        // pass (proving the V36 CHECK, not the trigger, rejects it). Terminal
        // assertion (Codex 019e92a7 transaction-hygiene): the failed INSERT
        // aborts the tx, so there is no ENABLE-TRIGGER finally and nothing runs
        // after — the @DataJpaTest rollback re-enables the trigger.
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".endpoint_software_inventory_state_history DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA
                        + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, NULL, ?, 1, 0, "
                        + "  '0000000000000000000000000000000000000000000000000000000000000000', "
                        + "  CAST('[]' AS jsonb), ?, ?)",
                id, tenant, deviceId,
                Timestamp.from(capturedAt),
                Timestamp.from(createdAt)))
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
}
