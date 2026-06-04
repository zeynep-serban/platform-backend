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
 * Faz 21.1 Cleanup C4 step-9 regression guard — V49 uninstall family org
 * foundation + 7 FK flips + 2 partial-unique single-arbiter swaps. The last
 * hub-dependent consumer slice. Covers: validated org CHECKs, requests
 * UNIQUE(id, org_id), the 7 org FKs (validated + old dropped), request->command
 * DEFERRABLE preservation, command_id NULL tolerated, cross-org 23503 (request +
 * audit edges), append-only trigger still rejecting UPDATE post-V49, and the 2
 * org-keyed partial uniques (idempotency + one-inflight, incl. 23505 + TERMINAL
 * exemption + cross-org allowed).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V49UninstallFamilyOrgFkFlipPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String REQ = SCHEMA + ".endpoint_uninstall_requests";
    private static final String AUD = SCHEMA + ".endpoint_uninstall_audit";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

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

    private static final String[][] ORG_FKS = {
            {"uninstall_req_device_org_fk", "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id)"},
            {"uninstall_req_catalog_org_fk", "FOREIGN KEY (catalog_item_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_catalog_items(id, org_id)"},
            {"uninstall_req_command_org_fk", "FOREIGN KEY (command_id, org_id) REFERENCES " + SCHEMA + ".endpoint_commands(id, org_id) DEFERRABLE INITIALLY DEFERRED"},
            {"uninstall_audit_request_org_fk", "FOREIGN KEY (request_id, org_id) REFERENCES " + SCHEMA + ".endpoint_uninstall_requests(id, org_id)"},
            {"uninstall_audit_device_org_fk", "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id)"},
            {"uninstall_audit_catalog_org_fk", "FOREIGN KEY (catalog_item_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_catalog_items(id, org_id)"},
            {"uninstall_audit_command_org_fk", "FOREIGN KEY (command_id, org_id) REFERENCES " + SCHEMA + ".endpoint_commands(id, org_id)"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void all7FksFlippedToOrgComposite_andValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{
                "fk_endpoint_uninstall_requests_device", "fk_endpoint_uninstall_requests_catalog",
                "fk_endpoint_uninstall_requests_command", "fk_endpoint_uninstall_audit_request",
                "fk_endpoint_uninstall_audit_device", "fk_endpoint_uninstall_audit_catalog",
                "fk_endpoint_uninstall_audit_command"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void bothTablesHaveValidatedOrgChecks_andRequestsIdOrgUnique() {
        for (String con : new String[]{
                "endpoint_uninstall_requests_org_id_match", "endpoint_uninstall_requests_org_id_not_null",
                "endpoint_uninstall_audit_org_id_match", "endpoint_uninstall_audit_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_uninstall_requests_id_org_id_key' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void requestCommandFk_staysDeferrableInitiallyDeferred() {
        assertThat(jdbc.queryForObject(
                "SELECT condeferrable FROM pg_constraint WHERE conname='uninstall_req_command_org_fk'", Boolean.class))
                .as("condeferrable").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT condeferred FROM pg_constraint WHERE conname='uninstall_req_command_org_fk'", Boolean.class))
                .as("condeferred (INITIALLY DEFERRED)").isTrue();
    }

    @Test
    void partialUniques_swappedToOrgKeyed() {
        String idem = jdbc.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='uq_endpoint_uninstall_idempotency'", String.class);
        assertThat(idem).contains("org_id").doesNotContain("tenant_id");
        String inflight = jdbc.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='uq_endpoint_uninstall_one_inflight'", String.class);
        assertThat(inflight).contains("org_id").doesNotContain("tenant_id");
    }

    @Test
    void appendOnlyTrigger_stillRejectsUpdate_postV49() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), command = seedCommand(org, device), catalog = seedCatalog(org);
        UUID req = insertRequest(org, device, catalog, command, "TERMINAL", null);
        UUID audit = insertAudit(org, req, device, catalog, command);
        // append-only trigger must still RAISE on UPDATE after the V49 disable/enable
        // bracket. The plpgsql RAISE EXCEPTION (SQLSTATE P0001) surfaces as Spring's
        // UncategorizedSQLException, so assert on the explicit append-only message
        // (mirrors V32EndpointUninstallSurfacePostgresIntegrationTest).
        assertThatThrownBy(() -> jdbc.update("UPDATE " + AUD + " SET exit_code=1 WHERE id=?", audit))
                .hasMessageContaining("append-only");
    }

    @Test
    void legacyWriter_orgIdOmitted_filledByTriggers() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), catalog = seedCatalog(org);
        UUID req = insertRequest(org, device, catalog, null, "PENDING_APPROVAL", null);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + REQ + " WHERE id=?", UUID.class, req)).isEqualTo(org);
    }

    @Test
    void requestCommandIdNull_isAllowed() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), catalog = seedCatalog(org);
        UUID req = insertRequest(org, device, catalog, null, "PENDING_APPROVAL", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + REQ + " WHERE id=? AND command_id IS NULL", Long.class, req)).isEqualTo(1L);
    }

    @Test
    void crossOrgRequestInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA), catalog = seedCatalog(orgA);
        // tenant_id=orgB (trigger fills org_id=orgB) but device/catalog live in orgA.
        assertThatThrownBy(() -> insertRequest(orgB, device, catalog, null, "PENDING_APPROVAL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgAuditInsert_isRejectedByRequestOrgFk_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA), command = seedCommand(orgA, device), catalog = seedCatalog(orgA);
        UUID req = insertRequest(orgA, device, catalog, command, "TERMINAL", null);
        // audit tenant_id=orgB but request/device/catalog/command live in orgA.
        UUID deviceB = seedDevice(orgB), commandB = seedCommand(orgB, deviceB), catalogB = seedCatalog(orgB);
        assertThatThrownBy(() -> insertAuditExplicitParents(orgB, req, deviceB, catalogB, commandB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void sameOrgDuplicateIdempotency_isRejected_butCrossOrgAllowed() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID dA = seedDevice(orgA), cA = seedCatalog(orgA);
        insertRequest(orgA, dA, cA, null, "PENDING_APPROVAL", "idem-1");
        // cross-org same key: allowed (different org_id).
        UUID dB = seedDevice(orgB), cB = seedCatalog(orgB);
        insertRequest(orgB, dB, cB, null, "PENDING_APPROVAL", "idem-1");
        // same-org same key: rejected by org-keyed idempotency partial unique.
        UUID dA2 = seedDevice(orgA), cA2 = seedCatalog(orgA);
        assertThatThrownBy(() -> insertRequest(orgA, dA2, cA2, null, "PENDING_APPROVAL", "idem-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void sameOrgOpenInflight_isRejected_butTerminalExempt() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), catalog = seedCatalog(org);
        // A TERMINAL request does not occupy the in-flight slot.
        insertRequest(org, device, catalog, null, "TERMINAL", null);
        UUID open1 = insertRequest(org, device, catalog, null, "PENDING_APPROVAL", null);
        assertThat(open1).isNotNull();
        // A second OPEN request for the same (org, device, catalog) is rejected.
        assertThatThrownBy(() -> insertRequest(org, device, catalog, null, "QUEUED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private UUID seedDevice(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private UUID seedCommand(UUID org, UUID device) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_commands "
                        + "(id, tenant_id, device_id, command_type, idempotency_key, issued_by_subject, issued_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'UNINSTALL_SOFTWARE', ?, 'issuer', ?, ?, ?, 0)",
                id, org, device, "idem-" + id, now, now, now);
        return id;
    }

    private UUID seedCatalog(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type, source_name,"
                        + " source_trust, package_id, display_name, publisher, version_policy_type,"
                        + " detection_rule, risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, 'Publisher', 'LATEST',"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, org, "item-" + id, "pkg-" + id, "Display-" + id, now, now);
        return id;
    }

    /** org_id omitted — V49 trigger fills it from tenant_id. */
    private UUID insertRequest(UUID org, UUID device, UUID catalog, UUID command, String state, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + REQ + " "
                        + "(id, tenant_id, device_id, catalog_item_id, command_id, state, idempotency_key, created_by, created_at, state_updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'creator', ?, ?)",
                id, org, device, catalog, command, state, idempotencyKey, now, now);
        return id;
    }

    /** org_id omitted — V49 trigger fills it from tenant_id (= org here). */
    private UUID insertAudit(UUID org, UUID request, UUID device, UUID catalog, UUID command) {
        return insertAuditExplicitParents(org, request, device, catalog, command);
    }

    private UUID insertAuditExplicitParents(UUID org, UUID request, UUID device, UUID catalog, UUID command) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + AUD + " "
                        + "(id, request_id, tenant_id, device_id, catalog_item_id, command_id, result_status, verification, reported_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED_VERIFIED', 'ABSENT_VERIFIED', ?)",
                id, request, org, device, catalog, command, now);
        return id;
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) return sqlEx.getSQLState();
            cur = cur.getCause();
        }
        return null;
    }
}
