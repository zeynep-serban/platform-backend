package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.endpointadmin.model.EndpointAppControlSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
 * Faz 21.1 PR2b-iv.f regression guard —
 * {@link EndpointAppControlSnapshotRepository} per-device + payload-hash
 * reads migrated to the canonical effective-org filter (Codex 019e8dec
 * single-PR AGREE; index-friendly form from slice c iter-1 lesson).
 *
 * <p>Canonical predicate:
 * <pre>
 *   WHERE s.tenant_id = :orgId
 *     AND (s.org_id = :orgId OR s.org_id IS NULL)
 *     AND s.device_id = :deviceId
 *     [+ s.payload_hash_sha256 = CAST(:payloadHash AS varchar) for hash variant]
 * </pre>
 *
 * <p>Six per-device assertions mirroring slice d-A, plus four
 * payload-hash assertions (Codex note: V26
 * {@code (tenant_id, device_id, payload_hash_sha256)} UNIQUE prevents
 * duplicate-hash same-(tenant, device) fixture so BE-022Q
 * non-matching/empty regression test plus canonical/legacy/cross-org
 * coverage are sufficient).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointAppControlSnapshotEffectiveOrgPostgresIntegrationTest {

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
    private EndpointAppControlSnapshotRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("collectedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private static final String HASH_A =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String HASH_B =
            "2222222222222222222222222222222222222222222222222222222222222222";

    // ───────────────────────── Per-device 1: canonical row (both reads + wrapper) ─────────────────────────

    @Test
    void canonicalRow_visibleViaBothReadsAndWrapper() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotCanonical(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointAppControlSnapshot> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 2));
        Optional<EndpointAppControlSnapshot> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId);
        Page<EndpointAppControlSnapshot> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(head)
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(latest)
                .map(EndpointAppControlSnapshot::getId)
                .contains(snapshotId);
        assertThat(page.getContent())
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    // ───────────────────────── Per-device 2: legacy NULL row ─────────────────────────

    @Test
    void legacyNullRow_visibleViaTenantIdFallback_bothReads() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotLegacyNullOrg(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA
                        + ".endpoint_app_control_snapshots WHERE id = ?",
                Boolean.class, snapshotId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: V29 trigger bypass held")
                .isTrue();

        List<EndpointAppControlSnapshot> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 2));
        Optional<EndpointAppControlSnapshot> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId);
        Page<EndpointAppControlSnapshot> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(head)
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(latest)
                .map(EndpointAppControlSnapshot::getId)
                .contains(snapshotId);
        assertThat(page.getContent())
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    // ───────────────────────── Per-device 3: cross-org negative ─────────────────────────

    @Test
    void crossOrg_doesNotLeakOrgBsSnapshot() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        insertSnapshotCanonical(UUID.randomUUID(), orgA, deviceA, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(UUID.randomUUID(), orgB, deviceB, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointAppControlSnapshot> hitAforDeviceB = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceB, PageRequest.of(0, 10));
        Optional<EndpointAppControlSnapshot> latestAforDeviceB = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceB);

        assertThat(hitAforDeviceB).isEmpty();
        assertThat(latestAforDeviceB).isEmpty();
    }

    // ───────────────────────── Per-device 4: id DESC tiebreaker ─────────────────────────

    @Test
    void idDescTiebreaker_isHonoredOnIdenticalCollectedAndCreated() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        Instant collectedAt = Instant.parse("2026-06-03T10:00:00Z");
        Instant createdAt = Instant.parse("2026-06-03T10:00:01Z");

        // V26 (tenant_id, device_id, payload_hash_sha256) UNIQUE means
        // identical hashes for the same (tenant, device) cannot coexist;
        // use three distinct hashes to seed three distinct rows.
        UUID idLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idMid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID idHigh = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        insertSnapshotCanonical(idLow, orgA, deviceId,
                "a".repeat(64), collectedAt, createdAt);
        insertSnapshotCanonical(idMid, orgA, deviceId,
                "b".repeat(64), collectedAt, createdAt);
        insertSnapshotCanonical(idHigh, orgA, deviceId,
                "c".repeat(64), collectedAt, createdAt);

        List<EndpointAppControlSnapshot> head = repository
                .findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId, PageRequest.of(0, 1));

        assertThat(head)
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(idHigh);
    }

    // ───────────────────────── Per-device 5: page count + sort over OR-fallback ─────────────────────────

    @Test
    void pageHistory_countQuery_sumsBothCanonicalAndLegacyRowsAndContentSorted() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        UUID canonical = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        insertSnapshotCanonical(canonical, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotLegacyNullOrg(legacy, orgA, deviceId, HASH_B,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));

        Page<EndpointAppControlSnapshot> page = repository
                .findVisibleToOrgAndDeviceId(
                        orgA, deviceId, PageRequest.of(0, 10, HISTORY_SORT));

        assertThat(page.getContent())
                .as("Pageable Sort applied to content side; canonical (newer) first")
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(canonical, legacy);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    // ───────────────────────── Per-device 6: wrapper picks newest mixed row ─────────────────────────

    @Test
    void defaultWrapper_picksNewestRow_evenWhenLegacyNullCoexistsWithCanonical() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);

        UUID legacyOlder = UUID.randomUUID();
        UUID canonicalNewer = UUID.randomUUID();
        insertSnapshotLegacyNullOrg(legacyOlder, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:01Z"));
        insertSnapshotCanonical(canonicalNewer, orgA, deviceId, HASH_B,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        Optional<EndpointAppControlSnapshot> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgA, deviceId);

        assertThat(latest)
                .map(EndpointAppControlSnapshot::getId)
                .contains(canonicalNewer);
    }

    // ───────────────────────── Payload-hash 1: canonical match (List + wrapper) ─────────────────────────

    @Test
    void payloadHash_canonicalRowMatchingHash_isReturned() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotCanonical(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointAppControlSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_A, PageRequest.of(0, 1));
        Optional<EndpointAppControlSnapshot> wrapper = repository
                .findFirstByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_A);

        assertThat(hits)
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(wrapper)
                .map(EndpointAppControlSnapshot::getId)
                .contains(snapshotId);
    }

    // ───────────────────────── Payload-hash 2: legacy NULL match (List + wrapper) ─────────────────────────

    @Test
    void payloadHash_legacyNullRowMatchingHash_isReturnedViaOrFallback() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        UUID snapshotId = UUID.randomUUID();
        insertSnapshotLegacyNullOrg(snapshotId, orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointAppControlSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_A, PageRequest.of(0, 1));
        Optional<EndpointAppControlSnapshot> wrapper = repository
                .findFirstByOrgAndDeviceAndPayloadHash(
                        orgA, deviceId, HASH_A);

        assertThat(hits)
                .extracting(EndpointAppControlSnapshot::getId)
                .containsExactly(snapshotId);
        assertThat(wrapper)
                .map(EndpointAppControlSnapshot::getId)
                .contains(snapshotId);
    }

    // ───────────────────────── Payload-hash 3: cross-org miss ─────────────────────────

    @Test
    void payloadHash_crossOrg_doesNotLeakOrgBsRowWithSameHash() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = ensureDevice(orgA);
        UUID deviceB = ensureDevice(orgB);
        insertSnapshotCanonical(UUID.randomUUID(), orgA, deviceA, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));
        insertSnapshotCanonical(UUID.randomUUID(), orgB, deviceB, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        List<EndpointAppControlSnapshot> hits = repository
                .findByOrgAndDeviceAndPayloadHash(
                        orgA, deviceB, HASH_A, PageRequest.of(0, 1));
        Optional<EndpointAppControlSnapshot> wrapper = repository
                .findFirstByOrgAndDeviceAndPayloadHash(
                        orgA, deviceB, HASH_A);

        assertThat(hits).isEmpty();
        assertThat(wrapper).isEmpty();
    }

    // ───────────────────────── Payload-hash 4: BE-022Q regression — non-matching hash does not throw ─────────────────────────

    @Test
    void payloadHash_nonMatchingHash_doesNotThrowLowerByteaRegression() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = ensureDevice(orgA);
        insertSnapshotCanonical(UUID.randomUUID(), orgA, deviceId, HASH_A,
                Instant.parse("2026-06-03T10:00:00Z"),
                Instant.parse("2026-06-03T10:00:01Z"));

        assertThatCode(() -> {
            List<EndpointAppControlSnapshot> hits = repository
                    .findByOrgAndDeviceAndPayloadHash(
                            orgA, deviceId, HASH_B, PageRequest.of(0, 1));
            assertThat(hits).isEmpty();
        }).doesNotThrowAnyException();

        Optional<EndpointAppControlSnapshot> wrapper = repository
                .findFirstByOrgAndDeviceAndPayloadHash(orgA, deviceId, HASH_B);
        assertThat(wrapper).isEmpty();
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
        // V26 NOT NULL columns: schema_version, supported, probe_complete,
        // wdac_queryable, app_locker_queryable, wdac_mode (CHECK enum),
        // app_locker_*_rule (CHECK enum), app_locker_app_id_svc_state +
        // _startup (CHECK enum), probe_duration_ms.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_app_control_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, "
                        + " supported, probe_complete, "
                        + " wdac_queryable, app_locker_queryable, "
                        + " wdac_mode, app_locker_exe_rule, app_locker_dll_rule, "
                        + " app_locker_script_rule, app_locker_msi_rule, "
                        + " app_locker_appx_rule, app_locker_app_id_svc_state, "
                        + " app_locker_app_id_svc_startup, probe_duration_ms, "
                        + " payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, "
                        + "  true, true, "
                        + "  'UNKNOWN', 'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                        + "  'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                        + "  'NOT_CONFIGURED', 'UNKNOWN', "
                        + "  'AUTO', 0, "
                        + "  ?, ?, ?)",
                id, org, org, deviceId, payloadHash,
                Timestamp.from(collectedAt),
                Timestamp.from(createdAt));
    }

    private void insertSnapshotLegacyNullOrg(UUID id, UUID tenant, UUID deviceId,
            String payloadHash, Instant collectedAt, Instant createdAt) {
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".endpoint_app_control_snapshots DISABLE TRIGGER USER");
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_app_control_snapshots "
                            + "(id, tenant_id, org_id, device_id, schema_version, "
                            + " supported, probe_complete, "
                            + " wdac_queryable, app_locker_queryable, "
                            + " wdac_mode, app_locker_exe_rule, app_locker_dll_rule, "
                            + " app_locker_script_rule, app_locker_msi_rule, "
                            + " app_locker_appx_rule, app_locker_app_id_svc_state, "
                            + " app_locker_app_id_svc_startup, probe_duration_ms, "
                            + " payload_hash_sha256, collected_at, created_at) "
                            + "VALUES (?, ?, NULL, ?, 1, true, true, "
                            + "  true, true, "
                            + "  'UNKNOWN', 'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                            + "  'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                            + "  'NOT_CONFIGURED', 'UNKNOWN', "
                            + "  'AUTO', 0, "
                            + "  ?, ?, ?)",
                    id, tenant, deviceId, payloadHash,
                    Timestamp.from(collectedAt),
                    Timestamp.from(createdAt));
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA
                    + ".endpoint_app_control_snapshots ENABLE TRIGGER USER");
        }
    }
}
