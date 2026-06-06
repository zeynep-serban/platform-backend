package com.example.endpointadmin.repository;

import com.example.endpointadmin.dto.v1.admin.AdminDeviceHealthLatestEntry;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareLatestEntry;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.service.BulkLatestSnapshots;
import com.example.endpointadmin.service.EndpointDeviceHealthService;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE — shared latest-per-device bulk-query behaviour for #1146, run on
 * BOTH H2 ({@link BulkLatestSnapshotsRepositoryH2Test}) and a real
 * PostgreSQL Testcontainer
 * ({@link BulkLatestSnapshotsRepositoryPostgresIntegrationTest}). The
 * window function ({@code ROW_NUMBER() OVER (PARTITION BY ...)}), the
 * {@code LIMIT :limit} binding, and the scalar-only no-N+1 mapping must
 * behave identically on both engines — exactly the H2-vs-PG divergence
 * class that bit BE-020I ({@code lower(bytea)}) — so the suite is
 * dual-tiered by construction.
 *
 * <p>Each snapshot service is constructed with the autowired REAL
 * repository (so the test exercises the real window query + cap + chunk
 * loop together) and a no-op event publisher (unused by
 * {@code findLatestPerDevice}).
 *
 * <p>Snapshots are seeded via the JPA repository so the JSONB columns
 * ({@code redacted_payload}/{@code probe_errors}) go through the entity's
 * type mapper (a raw JdbcTemplate insert is not portable for JSONB across
 * H2 and PG); the parent {@code endpoint_devices} row is a plain raw
 * insert. The no-child-access tests call {@link EntityManager#clear()}
 * after seeding so {@code findAllById} re-loads detached entities with a
 * LAZY child collection — the precondition for the
 * {@code collectionFetchCount == 0} assertion (Codex {@code 019e7db8}
 * must-fix).
 */
abstract class AbstractBulkLatestSnapshotsRepositoryTest {

    @Autowired
    EndpointDeviceHealthSnapshotRepository healthRepo;
    @Autowired
    EndpointOutdatedSoftwareSnapshotRepository outdatedRepo;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager entityManager;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private EndpointDeviceHealthService healthService;
    private EndpointOutdatedSoftwareService outdatedService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        healthService = new EndpointDeviceHealthService(healthRepo, event -> { });
        outdatedService = new EndpointOutdatedSoftwareService(outdatedRepo, event -> { });
        tenantId = UUID.randomUUID();
    }

    // ── device-health ────────────────────────────────────────────────

    @Test
    void deviceHealth_returnsNewestSnapshotPerDevice() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedHealthSnapshot(tenantId, device, t.minus(1, ChronoUnit.HOURS), 10);
        seedHealthSnapshot(tenantId, device, t, 90);

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(tenantId, 100);

        assertThat(result.truncated()).isFalse();
        assertThat(result.snapshots()).hasSize(1);
        assertThat(result.snapshots().get(0).getMemoryUsedPercent()).isEqualTo((short) 90);
    }

    @Test
    void deviceHealth_isolatesByTenant() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedHealthSnapshot(tenantId, device, t, 50);

        UUID otherTenant = UUID.randomUUID();
        UUID otherDevice = seedDevice(otherTenant);
        seedHealthSnapshot(otherTenant, otherDevice, t, 77);

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(tenantId, 100);

        assertThat(result.snapshots()).hasSize(1);
        assertThat(result.snapshots().get(0).getDeviceId()).isEqualTo(device);
    }

    @Test
    void deviceHealth_tieBreaksByCreatedAtWhenCollectedAtEqual() {
        UUID device = seedDevice(tenantId);
        Instant collected = Instant.parse("2026-05-30T10:00:00Z");
        UUID older = seedHealthSnapshot(tenantId, device, collected, 11);
        UUID newer = seedHealthSnapshot(tenantId, device, collected, 88);
        // device-health exposes no createdAt setter (set by @PrePersist);
        // pin both via raw UPDATE so the tiebreak (same collected_at →
        // created_at DESC) is deterministic rather than relying on the
        // microsecond gap between two @PrePersist now() calls.
        setHealthCreatedAt(older, collected.minus(5, ChronoUnit.MINUTES));
        setHealthCreatedAt(newer, collected);

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(tenantId, 100);

        assertThat(result.snapshots()).hasSize(1);
        assertThat(result.snapshots().get(0).getMemoryUsedPercent()).isEqualTo((short) 88);
    }

    @Test
    void deviceHealth_overCapTruncatesToEmpty() {
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            UUID device = seedDevice(tenantId);
            seedHealthSnapshot(tenantId, device, t, 40 + i);
        }

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(tenantId, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.snapshots()).isEmpty();
    }

    @Test
    void deviceHealth_emptyTenantIsEmptyNotTruncated() {
        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(UUID.randomUUID(), 100);
        assertThat(result.truncated()).isFalse();
        assertThat(result.snapshots()).isEmpty();
    }

    @Test
    void deviceHealth_scalarMappingDoesNotFetchChildDisks() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedHealthSnapshot(tenantId, device, t, 60);
        // Detach so findAllById re-loads with a LAZY disks proxy — any
        // getDisks() in the mapper would then issue a fetch (count > 0).
        entityManager.clear();
        Statistics stats = freshStatistics();

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> result =
                healthService.findLatestPerDevice(tenantId, 100);
        List<AdminDeviceHealthLatestEntry> entries = result.snapshots().stream()
                .map(AdminDeviceHealthLatestEntry::from)
                .toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).memoryUsedPercent()).isEqualTo((short) 60);
        assertThat(stats.getCollectionFetchCount())
                .as("scalar-only mapping must NOT walk the lazy disks collection")
                .isZero();
    }

    // ── outdated-software (parity for the load-bearing cases) ─────────

    @Test
    void outdated_returnsNewestSnapshotPerDevice() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedOutdatedSnapshot(tenantId, device, t.minus(1, ChronoUnit.HOURS), 1);
        seedOutdatedSnapshot(tenantId, device, t, 7);

        BulkLatestSnapshots<EndpointOutdatedSoftwareSnapshot> result =
                outdatedService.findLatestPerDevice(tenantId, 100);

        assertThat(result.truncated()).isFalse();
        assertThat(result.snapshots()).hasSize(1);
        assertThat(result.snapshots().get(0).getUpgradeCount()).isEqualTo(7);
    }

    @Test
    void outdated_isolatesByTenant() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedOutdatedSnapshot(tenantId, device, t, 4);

        UUID otherTenant = UUID.randomUUID();
        UUID otherDevice = seedDevice(otherTenant);
        seedOutdatedSnapshot(otherTenant, otherDevice, t, 9);

        BulkLatestSnapshots<EndpointOutdatedSoftwareSnapshot> result =
                outdatedService.findLatestPerDevice(tenantId, 100);

        assertThat(result.snapshots()).hasSize(1);
        assertThat(result.snapshots().get(0).getDeviceId()).isEqualTo(device);
    }

    @Test
    void outdated_overCapTruncatesToEmpty() {
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            UUID device = seedDevice(tenantId);
            seedOutdatedSnapshot(tenantId, device, t, i);
        }

        BulkLatestSnapshots<EndpointOutdatedSoftwareSnapshot> result =
                outdatedService.findLatestPerDevice(tenantId, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.snapshots()).isEmpty();
    }

    @Test
    void outdated_scalarMappingDoesNotFetchChildPackages() {
        UUID device = seedDevice(tenantId);
        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        seedOutdatedSnapshot(tenantId, device, t, 3);
        entityManager.clear();
        Statistics stats = freshStatistics();

        BulkLatestSnapshots<EndpointOutdatedSoftwareSnapshot> result =
                outdatedService.findLatestPerDevice(tenantId, 100);
        List<AdminOutdatedSoftwareLatestEntry> entries = result.snapshots().stream()
                .map(AdminOutdatedSoftwareLatestEntry::from)
                .toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).upgradeCount()).isEqualTo(3);
        assertThat(entries.get(0).possiblyTruncated()).isFalse();
        assertThat(stats.getCollectionFetchCount())
                .as("scalar-only mapping must NOT walk the lazy packages collection")
                .isZero();
    }

    // ── seeding helpers ───────────────────────────────────────────────

    /** Enable + clear Hibernate statistics immediately before a measured op. */
    private Statistics freshStatistics() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private UUID seedDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-05-30T09:00:00Z"));
        String deviceTagsExpression = isPostgres() ? "?::jsonb" : "?";
        jdbc.update("""
                INSERT INTO endpoint_devices
                  (id, tenant_id, hostname, os_type, status, deployment_ring, device_tags, created_at, updated_at, version)
                VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', 'PILOT', %s, ?, ?, 0)
                """.formatted(deviceTagsExpression),
                id, tenant, "host-" + id.toString().substring(0, 8), "[]", now, now);
        return id;
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName()
                        .toLowerCase(Locale.ROOT)
                        .contains("postgres")));
    }

    private UUID seedHealthSnapshot(UUID tenant, UUID device, Instant collectedAt, int memoryUsedPercent) {
        EndpointDeviceHealthSnapshot snap = new EndpointDeviceHealthSnapshot();
        // No setId: @Id is @GeneratedValue(UUID), so the repository must see
        // a null id to INSERT (a manual id routes save() → merge → "detached
        // entity, uninitialized version"). The id is populated after flush.
        snap.setTenantId(tenant);
        snap.setDeviceId(device);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setAnyLowDisk(false);
        snap.setFixedDiskCount(1);
        snap.setFixedDisksTruncated(false);
        snap.setMaxFixedDisks(64);
        snap.setMemoryUsedPercent((short) memoryUsedPercent);
        snap.setMemoryHighPressure(false);
        snap.setUptimeDays(10);
        snap.setLongUptimeWarning(false);
        snap.setSourceUsed("win32");
        snap.setPayloadHashSha256(PAYLOAD_HASH);
        snap.setCollectedAt(collectedAt);
        healthRepo.saveAndFlush(snap);
        return snap.getId();
    }

    private void setHealthCreatedAt(UUID id, Instant createdAt) {
        jdbc.update("UPDATE endpoint_device_health_snapshots SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), id);
    }

    private UUID seedOutdatedSnapshot(UUID tenant, UUID device, Instant collectedAt, int upgradeCount) {
        EndpointOutdatedSoftwareSnapshot snap = new EndpointOutdatedSoftwareSnapshot();
        // No setId — see seedHealthSnapshot (generated UUID, INSERT path).
        snap.setTenantId(tenant);
        snap.setDeviceId(device);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setUpgradeCount(upgradeCount);
        snap.setUpgradeTruncated(false);
        snap.setMaxUpgrade(512);
        snap.setSourceUsed("winget");
        snap.setPayloadHashSha256(PAYLOAD_HASH);
        snap.setCollectedAt(collectedAt);
        outdatedRepo.saveAndFlush(snap);
        return snap.getId();
    }

    /** 64 lowercase hex chars satisfying the payload_hash_sha256 CHECK
     *  ({@code ^[a-f0-9]{64}$}); no uniqueness constraint, so a shared
     *  constant is fine across seeds. */
    private static final String PAYLOAD_HASH = "a".repeat(64);
}
