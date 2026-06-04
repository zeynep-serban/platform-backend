package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.d-B regression guard —
 * {@link EndpointOutdatedSoftwareSnapshotRepository} payload-hash
 * dedupe probe + fleet-wide latest-per-device methods migrated to the
 * canonical effective-org filter (Codex 019e8dc7 + 019e8dd6 B-D-B
 * sub-slice AGREE).
 *
 * <p>Canonical predicates:
 * <pre>
 *   findByOrgAndDeviceAndPayloadHash
 *       WHERE s.tenant_id = :orgId
 *         AND (s.org_id = :orgId OR s.org_id IS NULL)
 *         AND s.device_id = :deviceId
 *         AND s.payload_hash_sha256 = CAST(:payloadHash AS varchar)
 *       ORDER BY collected_at DESC, created_at DESC, id DESC
 *
 *   findLatestPerDeviceForOrg
 *       WHERE s.tenant_id = :orgId
 *         AND (s.org_id = :orgId OR s.org_id IS NULL)
 *         AND NOT EXISTS (
 *           SELECT newer.id
 *           FROM   endpoint_outdated_software_snapshots newer
 *           WHERE  newer.tenant_id = s.tenant_id
 *             AND  newer.device_id = s.device_id
 *             AND  (newer.collected_at, newer.created_at, newer.id) GREATER-THAN
 *                  (s.collected_at, s.created_at, s.id)
 *         )
 *       ORDER BY s.id   -- fleet-level deterministic LIMIT truncation; #1146 bulk consumer
 * </pre>
 *
 * <p>Critical: the inner correlated subquery does NOT add a
 * {@code (newer.org_id = s.org_id OR newer.org_id IS NULL)} branch.
 * Fixed {@code tenant_id = X} already pins the inner candidates to
 * the same effective-org bucket. The production @Query keeps the
 * OR-fallback so the older canonical-vs-canonical supersession case
 * remains correct — the
 * {@link #fleet_sameDeviceMixedRowAge_newerSupersedesOlder()} test
 * pins the newest-per-device truncation.
 *
 * <p>C1.5/V36 note: the prior "newer canonical supersedes older legacy
 * NULL" fixture is no longer constructable — CHECK org_id IS NOT NULL
 * makes {@code org_id IS NULL} physically unconstructable on the source
 * tables; a trigger-bypass org_id-NULL insert is rejected 23514. The
 * pure legacy-NULL visibility fixtures are converted to that invariant
 * proof; the supersession test keeps canonical-vs-canonical ordering
 * coverage. The OR-fallback read branch stays, dead until A5.
 *
 * <p>Method 5 also retains the BE-022Q regression guard — the
 * {@code lower(bytea)} grammar bug from Halildeu/platform-backend#330.
 * The {@code cast(:payloadHash as string)} JPQL line forces Hibernate
 * to bind a {@code varchar} parameter and the column itself is
 * {@code VARCHAR(64)}, so a non-matching call with a different hash
 * MUST NOT throw {@code SQLGrammarException}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointOutdatedSoftwareSnapshotPayloadHashAndFleetEffectiveOrgPostgresIntegrationTest {

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

    private static final String HASH_A =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String HASH_B =
            "2222222222222222222222222222222222222222222222222222222222222222";

    // ───────────────────────── Payload-hash assertion 1: canonical match ─────────────────────────

    @Test
    void payloadHash_canonicalRowMatchingHash_isReturned() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotCanonical(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_A, PageRequest.of(0, 1));

        assertThat(hits)
                .as("canonical row with matching hash is returned via effective-org branch")
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(snapshotId);
    }

    // ───────────────────────── Payload-hash assertion 2: legacy NULL insert REJECTED by V36 ─────────────────────────

    @Test
    void payloadHash_legacyNullOrgInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL payload-hash OR-fallback fixture is replaced
        // by its invariant proof — a trigger-bypass org_id-NULL insert is
        // rejected 23514. The OR-fallback branch in
        // findByOrgAndDeviceAndPayloadHash is left intact (provably dead until
        // A5 removes it with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        assertSnapshotLegacyNullOrgInsertRejectedByV36(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
    }

    // ───────────────────────── Payload-hash assertion 3: cross-org miss ─────────────────────────

    @Test
    void payloadHash_crossOrg_doesNotLeakOrgBsRowWithSameHash() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        UUID snapshotA = UUID.randomUUID();
        UUID snapshotB = UUID.randomUUID();
        insertSnapshotCanonical(snapshotA, orgA, deviceA, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(snapshotB, orgB, deviceB, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        // orgA querying for orgB's device + identical hash should return empty.
        List<EndpointOutdatedSoftwareSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceB, HASH_A, PageRequest.of(0, 1));

        assertThat(hits)
                .as("orgA must NOT see orgB's row even when the hash matches")
                .isEmpty();
    }

    // ───────────────────────── Payload-hash assertion 4: BE-022Q regression — non-matching hash does not throw ─────────────────────────

    @Test
    void payloadHash_nonMatchingHash_doesNotThrowLowerByteaRegression() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        insertSnapshotCanonical(UUID.randomUUID(), orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        // The original BE-022Q live bug surfaced via SQLGrammarException
        // on PG side because lower(bytea) does not exist. The
        // cast(:payloadHash as string) JPQL line forces a varchar bind
        // and the column is VARCHAR(64), so a different hash must
        // simply miss without throwing.
        assertThatCode(() -> repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_B, PageRequest.of(0, 1)))
                .doesNotThrowAnyException();

        List<EndpointOutdatedSoftwareSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_B, PageRequest.of(0, 1));
        assertThat(hits).isEmpty();
    }

    // ───────────────────────── Fleet assertion 1: canonical visible ─────────────────────────

    @Test
    void fleet_canonicalRow_visibleViaEffectiveOrgOuterFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotCanonical(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> fleet = repository
                .findLatestPerDeviceForOrg(orgA, PageRequest.of(0, 50));

        assertThat(fleet)
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(snapshotId);
    }

    // ───────────────────────── Fleet assertion 2: legacy NULL insert REJECTED by V36 ─────────────────────────

    @Test
    void fleet_legacyNullOrgInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL fleet OR-fallback fixture is replaced by its
        // invariant proof — a trigger-bypass org_id-NULL insert is rejected
        // 23514. The OR-fallback outer filter in findLatestPerDeviceForOrg is
        // left intact (provably dead until A5 removes it with composite
        // indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        assertSnapshotLegacyNullOrgInsertRejectedByV36(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
    }

    // ───────────────────────── Fleet assertion 3: cross-org isolation ─────────────────────────

    @Test
    void fleet_crossOrg_orgADoesNotIncludeOrgBsRow() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        UUID snapshotA = UUID.randomUUID();
        UUID snapshotB = UUID.randomUUID();
        insertSnapshotCanonical(snapshotA, orgA, deviceA, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(snapshotB, orgB, deviceB, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> fleetA = repository
                .findLatestPerDeviceForOrg(orgA, PageRequest.of(0, 50));

        assertThat(fleetA)
                .as("orgA fleet must only contain orgA's row")
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(snapshotA);
    }

    // ───────────────────────── Fleet assertion 4: newest-per-device truncation (same device, two canonical rows) ─────────────────────────

    @Test
    void fleet_sameDeviceMixedRowAge_newerSupersedesOlder() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        // Older + newer canonical rows, SAME (org, device). The "newer per
        // device" rule must surface ONLY the newer row. Under C1.5/V36 the
        // prior older legacy-NULL row is no longer constructable; its
        // rejection is pinned by fleet_legacyNullOrgInsert_isRejectedByV36.
        // Two canonical rows retain the inner-correlated NOT EXISTS
        // newest-per-device truncation coverage.
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        insertSnapshotCanonical(older, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));
        insertSnapshotCanonical(newer, orgA, deviceId, HASH_B,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointOutdatedSoftwareSnapshot> fleet = repository
                .findLatestPerDeviceForOrg(orgA, PageRequest.of(0, 50));

        assertThat(fleet)
                .as("fleet must return ONLY the newer row for the same "
                        + "(org, device) — proves the inner correlated NOT EXISTS "
                        + "subquery truncates to the newest per device")
                .extracting(EndpointOutdatedSoftwareSnapshot::getId)
                .containsExactly(newer);
    }

    // ───────────────────────── Fleet assertion 5: cap+1 overCap behavior preserved ─────────────────────────

    @Test
    void fleet_capPlusOne_overCapBehaviorPreserved() {
        UUID orgA = UUID.randomUUID();
        UUID d1 = ensureDevice(orgA);
        UUID d2 = ensureDevice(orgA);
        UUID d3 = ensureDevice(orgA);
        insertSnapshotCanonical(UUID.randomUUID(), orgA, d1, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(UUID.randomUUID(), orgA, d2, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(UUID.randomUUID(), orgA, d3, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        // Cap = 2; service uses PageRequest.of(0, cap + 1) and treats
        // size > cap as overCap. The repository contract here returns
        // up to cap+1 rows; the service-side overCap detection is unit
        // tested elsewhere. Here we pin that Pageable cap+1 honors the
        // truncation set.
        List<EndpointOutdatedSoftwareSnapshot> fleet = repository
                .findLatestPerDeviceForOrg(orgA, PageRequest.of(0, 3));
        assertThat(fleet)
                .as("3 distinct devices each with one snapshot → 3 rows under cap+1")
                .hasSize(3);
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
            String payloadHash, Instant collectedAt, Instant createdAt) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " supported, probe_complete, upgrade_count, "
                        + " upgrade_truncated, max_upgrade, source_used, "
                        + " payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, 0, false, 0, "
                        + "  'winget', ?, ?, ?)",
                id, org, org, deviceId, payloadHash,
                Timestamp.from(collectedAt),
                Timestamp.from(createdAt));
    }

    private void assertSnapshotLegacyNullOrgInsertRejectedByV36(UUID id, UUID tenant,
            UUID deviceId, String payloadHash, Instant collectedAt, Instant createdAt) {
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
                id, tenant, deviceId, payloadHash,
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
