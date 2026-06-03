package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — PostgreSQL-only atomicity tests for the outdated-software snapshot
 * native insert (Faz 22.5, AG-036; reuses the BE-024 atomicity pattern,
 * Codex 019e75fe CRITICAL). Proves the {@code ON CONFLICT
 * (source_command_result_id) WHERE source_command_result_id IS NOT NULL
 * DO NOTHING} write path against the REAL V20 partial-unique index:
 *
 * <ul>
 *   <li><b>(a) duplicate is a clean no-op</b> — at the repository layer a
 *       second insert with the same {@code source_command_result_id} returns
 *       {@code null} (no inserted id), throws nothing, and leaves a single row;
 *       at the service layer a duplicate re-ingest of the same command-result
 *       does NOT double-write the snapshot.</li>
 *   <li><b>(b) a non-duplicate violation propagates + rolls back the whole
 *       transaction</b> — a snapshot insert that breaches a NON-duplicate V20
 *       constraint (the {@code upgrade_count <= max_upgrade} range CHECK)
 *       throws a {@code DataIntegrityViolationException} (NOT swallowed, NOT
 *       mis-classified as a duplicate) and rolls back a companion write made
 *       in the same transaction. A second breach class (the
 *       {@code payload_hash_sha256} regex CHECK) defends against a future
 *       change that narrows the propagated surface back to "duplicate
 *       only".</li>
 *   <li>The child package rows commit / roll back together with the parent
 *       snapshot row (single native transaction).</li>
 * </ul>
 *
 * <p>The H2 {@code @DataJpaTest} slice ({@code EndpointOutdatedSoftwareServiceTest})
 * cannot exercise either path: H2 has neither the partial unique index nor the
 * {@code ON CONFLICT ... DO NOTHING} grammar, and {@code ddl-auto=validate}
 * vs the real CHECK constraints only exist on Postgres. PG 16 Testcontainer +
 * Flyway + {@code public} schema (same setup as the V13 / V17 / V18 / V20 PG
 * tests). Runs in CI only — the local {@code -Dtest='!*PostgresIntegrationTest'}
 * filter skips it (Docker may be unavailable locally).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointOutdatedSoftwareService.class
})
class EndpointOutdatedSoftwareAtomicityPostgresIntegrationTest {

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

    private static final UUID TENANT =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_HASH = "a".repeat(64);

    @Autowired
    private EndpointOutdatedSoftwareService service;

    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository repository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    // ──────────────────────────────────────────────────────────────────
    // (a) duplicate source_command_result_id is a clean no-op
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repositoryDuplicateSourceCommandResultIsNoOpNotException() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());
        UUID resultId = f.resultId;

        // First insert lands and returns the assigned id.
        UUID first = tx.execute(s ->
                repository.insertIfNewSourceCommandResult(
                        snapshotEntity(f.deviceId, resultId, 2, VALID_HASH, packages())));
        assertThat(first).isNotNull();

        // Second insert with the SAME source_command_result_id hits the V20
        // partial-UNIQUE index. ON CONFLICT DO NOTHING makes it a clean no-op:
        // returns null, throws NOTHING (the transaction is NOT marked
        // rollback-only).
        UUID second = tx.execute(s ->
                repository.insertIfNewSourceCommandResult(
                        snapshotEntity(f.deviceId, resultId, 2, VALID_HASH, packages())));
        assertThat(second).as("duplicate must be a no-op, not an insert").isNull();

        // Exactly one row for THIS source_command_result_id survived.
        boolean present = tx.execute(s ->
                repository.findBySourceCommandResultId(resultId).isPresent());
        assertThat(present).isTrue();
        long forDevice = tx.execute(s -> repository
                .findVisibleToOrgAndDeviceId(TENANT, f.deviceId, PageRequest.of(0, 10))
                .getTotalElements());
        assertThat(forDevice).as("no-op duplicate must not add a second row")
                .isEqualTo(1L);

        // And the no-op did NOT write a second set of package rows.
        long pkgCount = tx.execute(s -> countPackagesForDevice(f.deviceId));
        assertThat(pkgCount).as("no-op duplicate must not append child packages")
                .isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serviceDuplicateIngestDoesNotDoubleWriteSnapshot() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());

        Map<String, Object> details = wrap(goldenWithUpgrades());

        // First ingest appends one snapshot row + two package rows.
        tx.executeWithoutResult(s -> service.ingest(
                reload(f.deviceId), command(f.commandId), result(f.resultId), details));
        // Re-ingest the SAME command-result (idempotent agent re-delivery):
        // must no-op (pre-probe fast-path + ON CONFLICT), not append a
        // duplicate and not raise.
        tx.executeWithoutResult(s -> service.ingest(
                reload(f.deviceId), command(f.commandId), result(f.resultId), details));

        long rows = tx.execute(s -> repository
                .findVisibleToOrgAndDeviceId(TENANT, f.deviceId, PageRequest.of(0, 10))
                .getTotalElements());
        assertThat(rows).as("idempotent re-ingest must not double-write").isEqualTo(1L);
        long pkgCount = tx.execute(s -> countPackagesForDevice(f.deviceId));
        assertThat(pkgCount).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // (b) a NON-duplicate violation propagates + rolls back the whole tx
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void nonDuplicateViolationPropagatesAndRollsBackCompanionWrite() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());

        // One transaction: (1) a companion snapshot write succeeds, then (2) a
        // snapshot insert that breaches the NON-duplicate
        // ck_..._upgrade_count_range CHECK (upgrade_count=600 > max_upgrade=512).
        // The whole tx must roll back together — proving the violation is NOT
        // swallowed and does NOT leave the companion committed on its own.
        UUID companionResultId = tx.execute(s -> newDeviceCommandResult().resultId);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            repository.insertIfNewSourceCommandResult(
                    snapshotEntity(f.deviceId, companionResultId, 1, VALID_HASH, List.of()));
            // Sanity: the companion is visible inside the tx before the failing
            // insert.
            assertThat(repository.findBySourceCommandResultId(companionResultId))
                    .isPresent();
            // upgrade_count (600) > max_upgrade (512) → range CHECK breach.
            EndpointOutdatedSoftwareSnapshot bad =
                    snapshotEntity(f.deviceId, f.resultId, 600, VALID_HASH, List.of());
            bad.setUpgradeCount(600);
            bad.setMaxUpgrade(512);
            repository.insertIfNewSourceCommandResult(bad);
        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                // The real CHECK name surfaces — proving the violation is NOT
                // mis-classified/hidden as a "duplicate source_command_result".
                .hasMessageContaining(
                        "ck_endpoint_outdated_software_snapshots_upgrade_count_range");

        // After rollback: the companion snapshot for THIS tx is gone — the tx
        // rolled back atomically.
        TransactionTemplate verifyTx = new TransactionTemplate(txManager);
        boolean companionPresent = verifyTx.execute(s ->
                repository.findBySourceCommandResultId(companionResultId).isPresent());
        assertThat(companionPresent)
                .as("companion snapshot must roll back with the failed insert")
                .isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void badHashViolationPropagatesNotSwallowed() {
        // A second non-duplicate breach class (the payload_hash_sha256 regex
        // CHECK) — defends against a future change that narrows the propagated
        // surface back to "duplicate only".
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Fixture f = tx.execute(s -> newDeviceCommandResult());

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                repository.insertIfNewSourceCommandResult(
                        snapshotEntity(f.deviceId, f.resultId, 0, "NOT_A_HASH", List.of()))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_outdated_software_snapshots_hash_format");

        // No row for THIS result was committed.
        boolean present = tx.execute(s ->
                repository.findBySourceCommandResultId(f.resultId).isPresent());
        assertThat(present)
                .as("rejected insert must leave no row for the result")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private record Fixture(UUID deviceId, UUID commandId, UUID resultId) {
    }

    private Fixture newDeviceCommandResult() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT);
        device.setHostname("PG-ATOMIC-" + UUID.randomUUID());
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        device = deviceRepository.saveAndFlush(device);

        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(TENANT);
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.COLLECT_INVENTORY);
        cmd.setIdempotencyKey("inv-" + UUID.randomUUID());
        cmd.setPayload(Map.of());
        cmd.setStatus(CommandStatus.SUCCEEDED);
        cmd.setIssuedBySubject("admin@example.com");
        cmd.setIssuedAt(Instant.now());
        cmd = commandRepository.saveAndFlush(cmd);

        EndpointCommandResult res = new EndpointCommandResult();
        res.setTenantId(TENANT);
        res.setCommand(cmd);
        res.setDevice(device);
        res.setResultStatus(CommandResultStatus.SUCCEEDED);
        res.setResultPayload(Map.of());
        res.setReportedAt(Instant.now());
        res = resultRepository.saveAndFlush(res);

        return new Fixture(device.getId(), cmd.getId(), res.getId());
    }

    private EndpointDevice reload(UUID deviceId) {
        return deviceRepository.findById(deviceId).orElseThrow();
    }

    private EndpointCommand command(UUID commandId) {
        return commandRepository.findById(commandId).orElseThrow();
    }

    private EndpointCommandResult result(UUID resultId) {
        return resultRepository.findById(resultId).orElseThrow();
    }

    private long countPackagesForDevice(UUID deviceId) {
        // Count package rows whose snapshot belongs to this device, via the
        // snapshot list (the test container is shared across NOT_SUPPORTED
        // tests, so scope by device).
        long total = 0;
        for (EndpointOutdatedSoftwareSnapshot snap : repository
                .findVisibleToOrgAndDeviceId(TENANT, deviceId, PageRequest.of(0, 50))) {
            total += snap.getPackages().size();
        }
        return total;
    }

    private EndpointOutdatedSoftwareSnapshot snapshotEntity(
            UUID deviceId, UUID sourceResultId, int upgradeCount, String hash,
            List<EndpointOutdatedSoftwarePackage> packages) {
        EndpointOutdatedSoftwareSnapshot snap = new EndpointOutdatedSoftwareSnapshot();
        snap.setTenantId(TENANT);
        snap.setDeviceId(deviceId);
        snap.setSourceCommandResultId(sourceResultId);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setUpgradeCount(upgradeCount);
        snap.setUpgradeTruncated(false);
        snap.setMaxUpgrade(512);
        snap.setSourceUsed("winget");
        snap.setProbeDurationMs(12);
        snap.setPayloadHashSha256(hash);
        snap.setCollectedAt(Instant.now());
        for (EndpointOutdatedSoftwarePackage pkg : packages) {
            pkg.setSnapshot(snap);
            snap.getPackages().add(pkg);
        }
        return snap;
    }

    private List<EndpointOutdatedSoftwarePackage> packages() {
        List<EndpointOutdatedSoftwarePackage> list = new ArrayList<>();
        list.add(packageEntity("7zip.7zip", "24.09", "25.01"));
        list.add(packageEntity("Microsoft.VisualStudioCode", "1.89.0", "1.91.1"));
        return list;
    }

    private EndpointOutdatedSoftwarePackage packageEntity(
            String id, String installed, String available) {
        EndpointOutdatedSoftwarePackage pkg = new EndpointOutdatedSoftwarePackage();
        pkg.setPackageId(id);
        pkg.setInstalledVersion(installed);
        pkg.setAvailableVersion(available);
        return pkg;
    }

    private Map<String, Object> wrap(Map<String, Object> outdatedSoftware) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("outdatedSoftware", outdatedSoftware);
        details.put("inventory", inventory);
        return details;
    }

    private Map<String, Object> goldenWithUpgrades() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 2);
        List<Map<String, Object>> upgrade = new ArrayList<>();
        upgrade.add(pkg("7zip.7zip", "24.09", "25.01"));
        upgrade.add(pkg("Microsoft.VisualStudioCode", "1.89.0", "1.91.1"));
        os.put("upgrade", upgrade);
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 45);
        return os;
    }

    private static Map<String, Object> pkg(String id, String installed, String available) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("packageId", id);
        p.put("installedVersion", installed);
        p.put("availableVersion", available);
        return p;
    }
}
