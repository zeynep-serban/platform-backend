package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * Faz 21.1 PR2b-iv.d-A regression guard —
 * {@link EndpointOutdatedSoftwareSnapshotRepository} per-device reads
 * migrated from derived {@code findByTenantIdAndDeviceId*} to explicit
 * {@code @Query} with the canonical effective-org filter (Codex
 * 019e8dc7 B-D-A sub-slice AGREE, Option A revised) in index-friendly
 * form (slice c iter-1 lesson — keep {@code tenant_id = :orgId}
 * explicit so the composite index remains usable; V30 invariant
 * guarantees semantic equivalence).
 *
 * <p>Canonical predicate:
 * <pre>
 *   WHERE s.tenant_id = :orgId
 *     AND (s.org_id = :orgId OR s.org_id IS NULL)
 *     AND s.device_id = :deviceId
 * </pre>
 *
 * <p>Six assertions covering the B-D-A read-path correctness modes:
 * <ol>
 *   <li>Canonical row read (both methods + default wrapper).</li>
 *   <li>Legacy NULL row REJECTED by V36 (C1.5) — CHECK org_id IS NOT NULL
 *       makes it unconstructable; a trigger-bypass org_id-NULL insert is
 *       rejected 23514. The OR-fallback read branch (both methods) stays,
 *       dead until A5.</li>
 *   <li>Cross-org negative — orgA must not see orgB's snapshot.</li>
 *   <li>id DESC tiebreaker honored on identical collected_at +
 *       created_at.</li>
 *   <li>Page count + sort over canonical rows: caller-supplied Pageable
 *       Sort applied to content side; newer first (HISTORY_SORT).</li>
 *   <li>Default wrapper picks the newest canonical row.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointOutdatedSoftwareSnapshotEffectiveOrgPostgresIntegrationTest {

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
    private EndpointOutdatedSoftwareSnapshotRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("collectedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private static final String VALID_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    // ───────────────────────── Assertion 1: canonical row (both methods + wrapper) ─────────────────────────

    @Test
    void canonicalRow_bothColumnsEqual_visibleViaBothReadsAndWrapper() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotCanonical(snapshotId, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 2));
        Optional<EndpointOutdatedSoftwareSnapshot> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId);
        Page<EndpointOutdatedSoftwareSnapshot> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(head)
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(latest)
                .as("portable LIMIT 1 default wrapper returns the canonical row")
                .map(EndpointOutdatedSoftwareSnapshot::getId)
                .contains(snapshotId);
        assertThat(page.getContent())
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    // ───────────────────────── Assertion 2: legacy NULL insert REJECTED by V36 ─────────────────────────

    @Test
    void legacyNullRow_orgIdNullInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL visibility fixture (both reads + wrapper +
        // Page) is replaced by its invariant proof — a trigger-bypass
        // org_id-NULL insert is rejected 23514. The OR-fallback read branches
        // are left intact (provably dead until A5 removes them with composite
        // indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        assertSnapshotLegacyNullOrgInsertRejectedByV36(snapshotId, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
    }

    // ───────────────────────── Assertion 3: cross-org negative ─────────────────────────

    @Test
    void crossOrg_doesNotLeakOrgBsSnapshot() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        UUID snapshotA = UUID.randomUUID();
        UUID snapshotB = UUID.randomUUID();
        insertSnapshotCanonical(snapshotA, orgA, deviceA,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(snapshotB, orgB, deviceB,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> hitAforDeviceB = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceB, PageRequest.of(0, 10));
        Optional<EndpointOutdatedSoftwareSnapshot> latestAforDeviceB = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceB);
        Page<EndpointOutdatedSoftwareSnapshot> pageAforDeviceB = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceB, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(hitAforDeviceB)
                .as("orgA must NOT see orgB's snapshot")
                .isEmpty();
        assertThat(latestAforDeviceB)
                .as("default wrapper must respect cross-org isolation")
                .isEmpty();
        assertThat(pageAforDeviceB.getTotalElements())
                .as("countQuery must respect cross-org isolation too")
                .isEqualTo(0L);
    }

    // ───────────────────────── Assertion 4: deterministic ordering tiebreaker ─────────────────────────

    @Test
    void latestThenHistory_idDescTiebreaker_isHonoredOnIdenticalCollectedAndCreated() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        Instant collectedAt = Instant.parse("2026-06-03T10:00:00Z");
        Instant createdAt = Instant.parse("2026-06-03T10:00:01Z");

        UUID idLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idMid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID idHigh = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        insertSnapshotCanonical(idLow, orgA, deviceId, collectedAt, createdAt);
        insertSnapshotCanonical(idMid, orgA, deviceId, collectedAt, createdAt);
        insertSnapshotCanonical(idHigh, orgA, deviceId, collectedAt, createdAt);

        List<EndpointOutdatedSoftwareSnapshot> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 1));

        assertThat(head)
                .as("ORDER BY id DESC tiebreaker → head is the largest UUID")
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(idHigh);
    }

    // ───────────────────────── Assertion 5: page count + sort over canonical rows ─────────────────────────

    @Test
    void pageHistory_countQuery_sumsCanonicalRowsForSameOrg() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        // Disjoint timestamps so the page test asserts both the count AND
        // that the caller-supplied Pageable Sort is applied to the content
        // side (Codex 019e8dbb slice c iter-1 REVISE #3 hardening). Under
        // C1.5/V36 the prior legacy-NULL second row is no longer
        // constructable; its rejection is pinned by
        // legacyNullRow_orgIdNullInsert_isRejectedByV36. Two canonical rows
        // (newer + older) retain the count + content-sort coverage.
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        insertSnapshotCanonical(newer, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(older, orgA, deviceId,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));

        Page<EndpointOutdatedSoftwareSnapshot> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(page.getContent())
                .as("page returns rows in HISTORY_SORT order: newer canonical "
                        + "first, older canonical second — proves the "
                        + "Pageable Sort is applied to the content side")
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(newer, older);
        assertThat(page.getTotalElements())
                .as("countQuery sibling totals over the effective-org predicate")
                .isEqualTo(2L);
    }

    // ───────────────────────── Assertion 6: default wrapper picks the newest canonical row ─────────────────────────

    @Test
    void defaultWrapper_pickNewestCanonicalRow() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        // Under C1.5/V36 the prior older legacy-NULL coexisting row is no
        // longer constructable; its rejection is pinned by
        // legacyNullRow_orgIdNullInsert_isRejectedByV36. Two canonical rows
        // (older + newer) retain the newest-wins wrapper coverage.
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        insertSnapshotCanonical(older, orgA, deviceId,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));
        insertSnapshotCanonical(newer, orgA, deviceId,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        Optional<EndpointOutdatedSoftwareSnapshot> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId);

        assertThat(latest)
                .as("default wrapper picks the newest canonical row")
                .map(EndpointOutdatedSoftwareSnapshot::getId)
                .contains(newer);
    }

    // ───────────────────────── Seed helpers ─────────────────────────

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

    private void insertSnapshotCanonical(UUID id, UUID org, UUID deviceId,
            Instant collectedAt, Instant createdAt) {
        // V20 NOT NULL columns include probe_complete, max_upgrade,
        // source_used (CHECK 'winget' | 'none'); the canonical SUCCEED
        // shape is supported=true, probe_complete=true, source_used='winget'.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " supported, probe_complete, upgrade_count, "
                        + " upgrade_truncated, max_upgrade, source_used, "
                        + " payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, 0, false, 0, "
                        + "  'winget', ?, ?, ?)",
                id, org, org, deviceId, VALID_HASH,
                Timestamp.from(collectedAt),
                Timestamp.from(createdAt));
    }

    private void assertSnapshotLegacyNullOrgInsertRejectedByV36(UUID id, UUID tenant,
            UUID deviceId, Instant collectedAt, Instant createdAt) {
        // Disable the V29 compat trigger so org_id stays the explicit NULL we
        // pass (proving the V36 CHECK, not the trigger, rejects it). Terminal
        // assertion (Codex 019e92a7 transaction-hygiene): the failed INSERT
        // aborts the tx, so there is no ENABLE-TRIGGER finally and nothing runs
        // after — the @DataJpaTest rollback re-enables the trigger.
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".endpoint_outdated_software_snapshots DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " supported, probe_complete, upgrade_count, "
                        + " upgrade_truncated, max_upgrade, source_used, "
                        + " payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, NULL, ?, 1, true, true, 0, false, 0, "
                        + "  'winget', ?, ?, ?)",
                id, tenant, deviceId, VALID_HASH,
                Timestamp.from(collectedAt),
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
