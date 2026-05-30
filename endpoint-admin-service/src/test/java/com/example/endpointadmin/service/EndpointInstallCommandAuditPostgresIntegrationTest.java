package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.AuditIntegrityVerifier;
import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-021 — PostgreSQL-only regression test for the install-dispatch
 * append-only UPDATE bug (issue #331).
 *
 * <p><strong>Bug</strong>: {@code POST
 * /api/v1/admin/endpoint-devices/{deviceId}/installs}
 * (INSTALL_SOFTWARE dispatch) returned HTTP 500 on real Postgres. During
 * {@link EndpointAdminCommandService#createInstall}, Hibernate flushed a
 * spurious {@code UPDATE} on a row in the append-only
 * {@code endpoint_audit_events} table (the chain tail that
 * {@link EndpointAuditService#record} reads to compute the prev hash). The
 * BE-016 {@code trg_endpoint_audit_events_append_only} trigger
 * (migration {@code V4}) rejected the UPDATE with
 * {@code endpoint_audit_events is append-only ... UPDATE rejected} →
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p><strong>Why it was masked for so long</strong>: every existing
 * {@code createInstall} test uses the H2 {@code @IsolatedH2DataJpaTest}
 * slice (the PL/pgSQL append-only trigger is never created on H2) AND the
 * {@code NoOpAuditChainLock} (the real {@code SELECT
 * pg_advisory_xact_lock} is never issued). This test runs the REAL
 * {@link PgAdvisoryAuditChainLock} against a real Postgres engine with
 * Flyway applying {@code V4}, so the trigger is live.
 *
 * <p><strong>Faithful sequence</strong>: each scenario runs
 * {@code createInstall} (and, for the no-regression assertion,
 * {@code createCommand}) inside its OWN {@link TransactionTemplate}
 * transaction so the {@code @Transactional} boundary COMMITS — which is
 * the flush point that surfaces the spurious UPDATE in production (an
 * HTTP request). The test class itself is {@code NOT_SUPPORTED} so the
 * default {@code @DataJpaTest} rollback-only transaction does not wrap
 * (and defer) the flush.
 *
 * <p>Mirrors {@code AuditHashChainPostgresIntegrationTest}: PG 16
 * Testcontainer + Flyway enabled + {@code ddl-auto=validate} + {@code
 * public} schema pinned + the genuine
 * {@link PgAdvisoryAuditChainLock} (NOT the H2 no-op) imported alongside
 * the full {@code createInstall} service stack.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointAdminCommandService.class,
        EndpointAuditService.class,
        EndpointInstallPreflightService.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class EndpointInstallCommandAuditPostgresIntegrationTest {

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
        // Pin the exact INSERT/UPDATE sequence so the repro mechanism is
        // visible in the build log (Hibernate SQL + bind params).
        registry.add("spring.jpa.show-sql", () -> "true");
        registry.add("spring.jpa.properties.hibernate.format_sql",
                () -> "true");
        registry.add("logging.level.org.hibernate.SQL", () -> "DEBUG");
    }

    private static final UUID TENANT = UUID.fromString(
            "aaaaaaaa-be21-be21-be21-aaaaaaaaaaaa");
    private static final String SUBJECT = "admin@example.com";
    // AG-026A only probes the pilot package; a PASS preflight requires it.
    private static final String PILOT_PACKAGE = "7zip.7zip";
    private static final String CATALOG_SLUG = "seven-zip";

    @Autowired
    private EndpointAdminCommandService commandService;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, SUBJECT);
    }

    // ──────────────────────────────────────────────────────────────────
    // The reproduction: createInstall must complete (PASS preflight) and
    // append exactly one audit event — NOT emit a forbidden UPDATE on the
    // append-only chain tail. Before the fix this throws
    // DataIntegrityViolationException("... is append-only ... UPDATE
    // rejected") at commit-time flush.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createInstallAppendsAuditEventWithoutAppendOnlyUpdateRejection() {
        UUID deviceId = seedOnlineWindowsDevice();
        seedPilotCatalogItem();
        seedPassPreflightSnapshot(deviceId);

        // Seed a GENESIS audit row first so record()'s tail-read returns a
        // managed row — the exact precondition for the spurious UPDATE.
        // (createCommand uses the SAME record() path; on the real append-
        // only trigger it must also be INSERT-only.)
        TransactionTemplate tx = new TransactionTemplate(txManager);
        EndpointCommandDto inventory = tx.execute(status ->
                commandService.createCommand(context(), deviceId, new CreateEndpointCommandRequest(
                        CommandType.COLLECT_INVENTORY,
                        "inv-" + UUID.randomUUID(),
                        "seed inventory",
                        null, 100, 3, null, null, null)));
        assertThat(inventory).isNotNull();

        long beforeInstall = countAuditEvents();

        // The bug surfaces here: the @Transactional createInstall commits,
        // Hibernate auto-flushes, and the managed chain tail loaded inside
        // record() is flushed as UPDATE → trigger reject → 500.
        EndpointCommandDto install = tx.execute(status ->
                commandService.createInstall(context(), deviceId, new CreateInstallRequest(
                        CATALOG_SLUG, null, "pilot install")));

        assertThat(install).isNotNull();
        assertThat(install.type()).isEqualTo(CommandType.INSTALL_SOFTWARE);

        // Exactly one new audit event appended by the install path.
        long afterInstall = countAuditEvents();
        assertThat(afterInstall - beforeInstall)
                .as("createInstall must append exactly one audit event")
                .isEqualTo(1L);

        // The newest event is the install-created event and is hash-linked
        // to the prior tail (BE-016 chain intact, prev hash from real tail).
        List<EndpointAuditEvent> events =
                auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT);
        EndpointAuditEvent newest = events.get(0);
        assertThat(newest.getEventType()).isEqualTo("ENDPOINT_INSTALL_COMMAND_CREATED");
        assertThat(newest.getEventHash()).isNotBlank();
        assertThat(newest.getPrevEventHash())
                .as("install event must chain off the prior tail")
                .isNotBlank();

        // Hash-chain verifier confirms the chain is linear + intact across
        // the inventory-created + install-created rows.
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo((int) afterInstall);
    }

    // ──────────────────────────────────────────────────────────────────
    // seed helpers (direct JDBC so the persistence context starts clean —
    // matches the EndpointInstallAuditPostgresIntegrationTest approach)
    // ──────────────────────────────────────────────────────────────────

    private UUID seedOnlineWindowsDevice() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, machine_fingerprint, status, "
                        + " os_type, os_version, agent_version, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'ONLINE', 'WINDOWS', 'Windows 11', '1.0.0', ?, ?, 0)",
                id, TENANT, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private void seedPilotCatalogItem() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        // APPROVED + enabled + WINGET/WINGET/WINGET_SILENT + pilot
        // packageId 7zip.7zip → preflight catalog/installer gates pass.
        jdbc.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, version_policy_value, installer_type,"
                        + " silent_args_policy, sha256, provenance, detection_rule,"
                        + " risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, 'Igor Pavlov', 'LATEST', NULL,"
                        + " 'WINGET_SILENT', 'DEFAULT', NULL, NULL,"
                        + " '{\"type\":\"WINGET_PACKAGE\",\"wingetPackageId\":\"7zip.7zip\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, TENANT, CATALOG_SLUG, PILOT_PACKAGE, "7-Zip", now, now);
    }

    private void seedPassPreflightSnapshot(UUID deviceId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        // supported + wingetReady + wingetEgress(supported=true,
        // schemaVersion=1, packageQuery.found=true) → WinGet gate passes
        // for the pilot package, yielding a PASS preflight.
        String egress = "{"
                + "\"supported\": true,"
                + "\"packageQuery\": {\"found\": true},"
                + "\"egress\": {"
                + "  \"dns\": [{\"ok\": true}],"
                + "  \"tcp\": [{\"ok\": true}],"
                + "  \"https\": [{\"ok\": true}]"
                + "}}";
        jdbc.update(
                "INSERT INTO endpoint_software_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, truncated, "
                        + " apps_available, apps_collected_at, winget_ready, "
                        + " winget_egress, winget_egress_collected_at, "
                        + " winget_egress_schema_version, summary_collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 1, true, false, true, ?, true, "
                        + " ?::jsonb, ?, 1, ?, ?, ?, 0)",
                id, TENANT, deviceId, now, egress, now, now, now, now);
    }

    private long countAuditEvents() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_audit_events WHERE tenant_id = ?",
                Long.class, TENANT);
        return count == null ? 0L : count;
    }
}
