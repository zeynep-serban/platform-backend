package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import jakarta.persistence.EntityManager;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — PostgreSQL-only migration + tenant-integrity + dedupe integration
 * tests for {@code V20__endpoint_outdated_software.sql} (Faz 22.5, AG-036).
 * Mirrors {@code EndpointDeviceHealthPostgresIntegrationTest}.
 *
 * <p>The H2 {@code @DataJpaTest} slice cannot exercise parts that depend on
 * real Postgres semantics:
 * <ul>
 *   <li>Composite-FK enforcement on {@code (device_id, tenant_id)} +
 *       {@code (snapshot_id, tenant_id)} — H2 silently accepts cross-tenant
 *       inserts;</li>
 *   <li>Partial UNIQUE on {@code source_command_result_id};</li>
 *   <li>DB CHECK regex on {@code payload_hash_sha256} and {@code package_id},
 *       enum CHECK on {@code source_used}, range CHECKs on
 *       {@code upgrade_count} / {@code max_upgrade}, {@code jsonb_typeof}
 *       CHECKs;</li>
 *   <li>{@code ON DELETE CASCADE} / {@code ON DELETE SET NULL};</li>
 *   <li>the BE-022Q payload-hash dedupe query NOT emitting
 *       {@code lower(bytea)} under real PG semantics.</li>
 * </ul>
 *
 * <p>Mirrors the device-health PG test setup: PG 16 Testcontainer + Flyway
 * enabled + {@code ddl-auto=validate} + {@code public} schema.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointOutdatedSoftwarePostgresIntegrationTest {

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

    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private static final String VALID_HASH = "a".repeat(64);

    // ──────────────────────────────────────────────────────────────────
    // Flyway / schema validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void flywayLiftsSchemaAndHibernateValidatesAgainstIt() {
        // ddl-auto=validate context start is itself the assertion: every
        // column in the 2 outdated-software entities must line up with V20 or
        // Spring refuses to bring the test context up.
        assertThat(snapshotRepository).isNotNull();
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void v20RegistersExpectedConstraintsAndIndexes() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_outdated_software_snapshots'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_outdated_software_snapshots_hash_format",
                "ck_endpoint_outdated_software_snapshots_payload_shape",
                "ck_endpoint_outdated_software_snapshots_probe_errors_shape",
                "ck_endpoint_outdated_software_snapshots_schema_version_range",
                "ck_endpoint_outdated_software_snapshots_source_used",
                "ck_endpoint_outdated_software_snapshots_upgrade_count_range",
                "ck_endpoint_outdated_software_snapshots_max_upgrade_range");

        List<String> pkgChecks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_outdated_software_packages'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(pkgChecks).contains(
                "ck_endpoint_outdated_software_packages_package_id");

        List<String> partialUniques = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = 'endpoint_outdated_software_snapshots' "
                        + "AND indexname = 'uq_endpoint_outdated_software_snapshots_source_cmd_result'",
                String.class);
        assertThat(partialUniques).hasSize(1);

        List<String> latestIndex = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = 'endpoint_outdated_software_snapshots' "
                        + "AND indexname = 'idx_endpoint_outdated_software_snapshots_tenant_device_time'",
                String.class);
        assertThat(latestIndex).hasSize(1);
    }

    /** Every V20 identifier must be ≤ PostgreSQL's 63-byte limit — if PG had
     *  silently truncated any name the migration/entity/test names would
     *  diverge. This asserts the catalog stored each name verbatim. */
    @Test
    void allV20IdentifiersAreWithinPostgresLimit() {
        List<String> names = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                        + " WHERE conname LIKE '%outdated_software%' "
                        + "UNION ALL "
                        + "SELECT indexname FROM pg_indexes "
                        + " WHERE indexname LIKE '%outdated_software%' "
                        + "UNION ALL "
                        + "SELECT relname FROM pg_class "
                        + " WHERE relname LIKE 'endpoint_outdated_software%' AND relkind = 'r'",
                String.class);
        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(name.length())
                    .as("identifier '%s' (%d bytes) must be <= 63", name, name.length())
                    .isLessThanOrEqualTo(63);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DB CHECK constraint violations
    // ──────────────────────────────────────────────────────────────────

    @Test
    void invalidHashRegexRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        assertThatThrownBy(() -> insertSnapshotRaw(
                tenantId, deviceId, null, "NOT_A_VALID_SHA256_HEX_VALUE", "winget",
                2, 512, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_outdated_software_snapshots_hash_format");
    }

    @Test
    void invalidSourceUsedRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        // "apt" fits VARCHAR(8) (so the length is not what rejects it) but is
        // not in the {winget,none} enum, so the CHECK is what fires.
        assertThatThrownBy(() -> insertSnapshotRaw(
                tenantId, deviceId, null, VALID_HASH, "apt", 2, 512, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_outdated_software_snapshots_source_used");
    }

    @Test
    void upgradeCountAboveMaxRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        // upgrade_count (600) > max_upgrade (512) breaches the range CHECK.
        assertThatThrownBy(() -> insertSnapshotRaw(
                tenantId, deviceId, null, VALID_HASH, "winget", 600, 512, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_outdated_software_snapshots_upgrade_count_range");
    }

    @Test
    void negativeUpgradeCountRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        assertThatThrownBy(() -> insertSnapshotRaw(
                tenantId, deviceId, null, VALID_HASH, "winget", -1, 512, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_outdated_software_snapshots_upgrade_count_range");
    }

    @Test
    void packageIdWithWhitespaceRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_outdated_software_packages "
                        + "(id, snapshot_id, tenant_id, package_id, installed_version, "
                        + " available_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, tenantId,
                // packageId with a space — a display-name leak — trips ^\S+$.
                "7-Zip File Manager", "24.09", "25.01",
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_outdated_software_packages_package_id");
    }

    // ──────────────────────────────────────────────────────────────────
    // Composite FK tenant-mismatch rejection
    // ──────────────────────────────────────────────────────────────────

    @Test
    void packageTenantMismatchRejectedByCompositeFk() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);
        UUID wrongTenant = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_outdated_software_packages "
                        + "(id, snapshot_id, tenant_id, package_id, installed_version, "
                        + " available_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, wrongTenant,
                "7zip.7zip", "24.09", "25.01",
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_endpoint_outdated_software_packages_snapshot");
    }

    // ──────────────────────────────────────────────────────────────────
    // Partial UNIQUE on source_command_result_id
    // ──────────────────────────────────────────────────────────────────

    @Test
    void duplicateSourceCommandResultIdRejectedByPartialUnique() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID resultId = insertCommandResult(tenantId, deviceId);

        insertValidSnapshot(tenantId, deviceId, resultId);

        assertThatThrownBy(() -> insertSnapshotRaw(
                tenantId, deviceId, resultId, VALID_HASH, "winget", 2, 512, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_endpoint_outdated_software_snapshots_source_cmd_result");
    }

    @Test
    void partialUniqueAllowsMultipleNullSourceCommandResultIds() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        insertValidSnapshot(tenantId, deviceId, null);
        insertValidSnapshot(tenantId, deviceId, null);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_outdated_software_snapshots "
                        + "WHERE tenant_id = ? AND device_id = ?",
                Long.class, tenantId, deviceId);
        assertThat(count).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // ON DELETE CASCADE / SET NULL
    // ──────────────────────────────────────────────────────────────────

    @Test
    void deviceDeleteCascadesSnapshotAndPackages() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);
        insertValidPackage(tenantId, snapshotId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_outdated_software_snapshots", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_outdated_software_packages", Long.class)).isEqualTo(1L);

        jdbc.update("DELETE FROM endpoint_devices WHERE id = ?", deviceId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_outdated_software_snapshots", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_outdated_software_packages", Long.class)).isZero();
    }

    @Test
    void commandResultDeleteSetsSourceCommandResultIdToNull() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID resultId = insertCommandResult(tenantId, deviceId);
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, resultId);

        jdbc.update("DELETE FROM endpoint_command_results WHERE id = ?", resultId);

        UUID postDeleteRef = jdbc.queryForObject(
                "SELECT source_command_result_id FROM endpoint_outdated_software_snapshots "
                        + "WHERE id = ?",
                UUID.class, snapshotId);
        assertThat(postDeleteRef).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // BE-022Q payload-hash deep-equality dedupe — NO lower(bytea)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void payloadHashProbe_nonMatchingHash_doesNotThrowLowerBytea() {
        UUID tenant = persistTenantAndDevice();
        UUID device = singleDeviceId();
        insertSnapshotWithHash(tenant, device, "a".repeat(64));

        assertThatCode(() -> {
            List<EndpointOutdatedSoftwareSnapshot> found =
                    snapshotRepository.findByOrgAndDeviceAndPayloadHash(
                            tenant, device, "b".repeat(64), PageRequest.of(0, 1));
            assertThat(found).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void payloadHashProbe_matchingHash_returnsSnapshot() {
        UUID tenant = persistTenantAndDevice();
        UUID device = singleDeviceId();
        String hash = "c".repeat(64);
        UUID snapshotId = insertSnapshotWithHash(tenant, device, hash);

        List<EndpointOutdatedSoftwareSnapshot> found =
                snapshotRepository.findByOrgAndDeviceAndPayloadHash(
                        tenant, device, hash, PageRequest.of(0, 1));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(snapshotId);
        assertThat(found.get(0).getPayloadHashSha256()).isEqualTo(hash);
    }

    @Test
    void payloadHashProbe_multipleIdenticalHashes_returnsMostRecentFirst() {
        UUID tenant = persistTenantAndDevice();
        UUID device = singleDeviceId();
        String hash = "e".repeat(64);

        Instant older = Instant.now().minusSeconds(3600);
        Instant newer = Instant.now().minusSeconds(60);
        insertSnapshotWithHashAt(tenant, device, hash, older);
        UUID newest = insertSnapshotWithHashAt(tenant, device, hash, newer);

        List<EndpointOutdatedSoftwareSnapshot> found =
                snapshotRepository.findByOrgAndDeviceAndPayloadHash(
                        tenant, device, hash, PageRequest.of(0, 1));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(newest);
    }

    @Test
    void payloadHashProbe_isTenantAndDeviceScoped() {
        UUID tenant = persistTenantAndDevice();
        UUID device = singleDeviceId();
        String hash = "f".repeat(64);
        insertSnapshotWithHash(tenant, device, hash);

        assertThat(snapshotRepository.findByOrgAndDeviceAndPayloadHash(
                UUID.randomUUID(), device, hash, PageRequest.of(0, 1))).isEmpty();
        assertThat(snapshotRepository.findByOrgAndDeviceAndPayloadHash(
                tenant, UUID.randomUUID(), hash, PageRequest.of(0, 1))).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private UUID tenantId;
    private UUID deviceId;

    private UUID persistTenantAndDevice() {
        if (tenantId != null) return tenantId;
        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, last_seen_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deviceId, tenantId,
                "PG-TEST-HOST", "ONLINE", "WINDOWS",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);
        return tenantId;
    }

    private UUID singleDeviceId() {
        return deviceId;
    }

    private UUID insertCommandResult(UUID tenantId, UUID deviceId) {
        UUID commandId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_commands "
                        + "(id, tenant_id, device_id, command_type, status, "
                        + " idempotency_key, issued_by_subject, issued_at, "
                        + " attempt_count, max_attempts, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                commandId, tenantId, deviceId,
                "COLLECT_INVENTORY", "SUCCEEDED",
                "pg-test-" + UUID.randomUUID(),
                "test-admin@example.com",
                Timestamp.from(Instant.now()),
                0, 3,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);

        UUID resultId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_command_results "
                        + "(id, tenant_id, command_id, device_id, result_status, "
                        + " result_payload, reported_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                resultId, tenantId, commandId, deviceId,
                "SUCCEEDED",
                "{}",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return resultId;
    }

    private UUID insertValidSnapshot(UUID tenantId, UUID deviceId, UUID resultIdOrNull) {
        return insertSnapshotRaw(tenantId, deviceId, resultIdOrNull, VALID_HASH,
                "winget", 2, 512, 1);
    }

    /** Insert a snapshot row with arbitrary hash / source_used / upgrade_count
     *  / max_upgrade so the CHECK-violation tests can target a single column. */
    private UUID insertSnapshotRaw(UUID tenantId, UUID deviceId, UUID resultIdOrNull,
                                   String hash, String sourceUsed,
                                   int upgradeCount, int maxUpgrade, int schemaVersion) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, source_command_result_id, "
                        + " schema_version, supported, probe_complete, "
                        + " upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, probe_duration_ms, payload_hash_sha256, "
                        + " redacted_payload, probe_errors, collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + " ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                snapshotId, tenantId, deviceId, resultIdOrNull,
                (short) schemaVersion, true, true,
                upgradeCount, false, maxUpgrade,
                sourceUsed, 12, hash,
                "{}", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);
        return snapshotId;
    }

    private UUID insertSnapshotWithHash(UUID tenantId, UUID deviceId, String hash) {
        return insertSnapshotWithHashAt(tenantId, deviceId, hash, Instant.now());
    }

    private UUID insertSnapshotWithHashAt(UUID tenantId, UUID deviceId, String hash,
                                          Instant collectedAt) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, payload_hash_sha256, redacted_payload, probe_errors, "
                        + " collected_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                snapshotId, tenantId, deviceId,
                (short) 1, true, true, 0, false, 512, "winget",
                hash, "{}", "[]",
                Timestamp.from(collectedAt),
                Timestamp.from(collectedAt),
                Timestamp.from(collectedAt),
                0L);
        return snapshotId;
    }

    private void insertValidPackage(UUID tenantId, UUID snapshotId) {
        jdbc.update(
                "INSERT INTO endpoint_outdated_software_packages "
                        + "(id, snapshot_id, tenant_id, package_id, installed_version, "
                        + " available_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, tenantId,
                "7zip.7zip", "24.09", "25.01",
                Timestamp.from(Instant.now()));
    }
}
