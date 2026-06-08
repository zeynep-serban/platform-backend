package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V58 Endpoint Display Policy schema/contract migration PG IT (Faz 22.5 #508;
 * Codex 019ea8be plan-time AGREE-after-REVISE).
 *
 * <p>Exercises the PG-specific durable invariants the JVM cannot: the
 * SET_DISPLAY_POLICY command_type CHECK widening (preserving V53 UPDATE_AGENT),
 * the append-only revision trigger, the CLEAR-is-empty CHECK, the active-policy
 * partial unique index, and the org-composite device FK cross-org rejection.
 */
@Testcontainers
class V58DisplayPolicyPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String HASH = "a".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    private JdbcTemplate migratedJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("58")
                .load()
                .migrate();
        return jdbc;
    }

    @Test
    void commandTypeCheckAcceptsSetDisplayPolicyAndPreservesUpdateAgent() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);

        insertCommand(jdbc, tenant, device, "SET_DISPLAY_POLICY");
        insertCommand(jdbc, tenant, device, "UPDATE_AGENT");
        insertCommand(jdbc, tenant, device, "INSTALL_SOFTWARE");

        assertThatThrownBy(() -> insertCommand(jdbc, tenant, device, "BOGUS_COMMAND"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revisionTableIsAppendOnly() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID rev = insertEnforceRevision(jdbc, tenant, device);

        // The append-only trigger RAISEs (SQLSTATE P0001) → Spring maps it to the
        // broader DataAccessException (not the 23xxx-only
        // DataIntegrityViolationException used by the FK/CHECK paths), mirroring
        // the V56 endpoint_device_lifecycle_audit append-only contract.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE " + SCHEMA + ".endpoint_display_policy_revisions SET reason = 'x' WHERE id = ?",
                rev))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM " + SCHEMA + ".endpoint_display_policy_revisions WHERE id = ?",
                rev))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void clearRevisionMustNotCarryDesiredStateSnapshot() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);

        // a CLEAR revision that illegally carries a screensaver field → CHECK fail
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + SCHEMA + ".endpoint_display_policy_revisions "
                        + "(id, tenant_id, device_id, scope_type, operation, "
                        + " screensaver_enabled, policy_hash_sha256, reason, "
                        + " created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'CLEAR', true, ?, 'r', 'maker', ?)",
                UUID.randomUUID(), tenant, device, HASH, Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneActivePolicyPerDeviceButClearedDoesNotCount() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID rev1 = insertEnforceRevision(jdbc, tenant, device);
        UUID rev2 = insertEnforceRevision(jdbc, tenant, device);

        insertActivePolicy(jdbc, tenant, device, rev1);

        // second ACTIVE policy for the same device → partial unique violation
        assertThatThrownBy(() -> insertActivePolicy(jdbc, tenant, device, rev2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // but a CLEARED row for the same device is allowed alongside the active one
        UUID clearRev = insertClearRevision(jdbc, tenant, device);
        insertClearedPolicy(jdbc, tenant, device, clearRev);

        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_display_policies "
                        + "WHERE device_id = ? AND cleared_at IS NULL", Integer.class, device);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void crossOrgDeviceFkRejected() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenantA = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenantA);
        UUID tenantB = UUID.randomUUID();

        // a revision under tenantB references tenantA's device → org-composite FK
        // (device_id, org_id) has no (device, tenantB) parent → FK violation
        assertThatThrownBy(() -> insertEnforceRevision(jdbc, tenantB, device))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revisionCommandIdCrossOrgRejected() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = insertDevice(jdbc, tenantA);
        UUID tenantB = UUID.randomUUID();
        UUID deviceB = insertDevice(jdbc, tenantB);
        UUID commandB = insertCommandReturningId(jdbc, tenantB, deviceB, "SET_DISPLAY_POLICY");

        // a revision under tenantA links a command owned by tenantB → composite
        // FK (command_id, org_id) has no (commandB, tenantA) parent → violation
        assertThatThrownBy(() -> insertEnforceRevisionWithCommand(jdbc, tenantA, deviceA, commandB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateCommandIdRejected() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID command = insertCommandReturningId(jdbc, tenant, device, "SET_DISPLAY_POLICY");

        insertEnforceRevisionWithCommand(jdbc, tenant, device, command);
        // a second revision linking the same command → ux_edpr_command violation
        assertThatThrownBy(() -> insertEnforceRevisionWithCommand(jdbc, tenant, device, command))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcePolicyWithClearedAtRejected() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID rev = insertEnforceRevision(jdbc, tenant, device);
        Timestamp now = Timestamp.from(Instant.now());

        // operation=ENFORCE with cleared_at set → ck_edp_operation_cleared_state
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + SCHEMA + ".endpoint_display_policies "
                        + "(id, tenant_id, device_id, scope_type, operation, current_revision_id, "
                        + " screensaver_enabled, policy_hash_sha256, cleared_at, cleared_by_subject, "
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', ?, true, ?, ?, 'checker', "
                        + " 'maker', ?, 'maker', ?)",
                UUID.randomUUID(), tenant, device, rev, HASH, now, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emptyEnforceRevisionRejected() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);

        // operation=ENFORCE with no desired-state snapshot → ck_edpr_enforce_not_empty
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + SCHEMA + ".endpoint_display_policy_revisions "
                        + "(id, tenant_id, device_id, scope_type, operation, "
                        + " policy_hash_sha256, reason, created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', ?, 'empty', 'maker', ?)",
                UUID.randomUUID(), tenant, device, HASH, Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lastEnforcementStatusDomainEnforced() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID rev = insertEnforceRevision(jdbc, tenant, device);

        // invalid status → ck_edp_last_enforcement_status reject
        assertThatThrownBy(() -> insertActivePolicyWithStatus(jdbc, tenant, device, rev, "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // a valid status persists
        insertActivePolicyWithStatus(jdbc, tenant, device, rev, "SUCCEEDED");
        Integer ok = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_display_policies "
                        + "WHERE device_id = ? AND last_enforcement_status = 'SUCCEEDED'",
                Integer.class, device);
        assertThat(ok).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // seed helpers — org_id filled by endpoint_org_id_compat_fill() trigger
    // ------------------------------------------------------------------

    private UUID insertDevice(JdbcTemplate jdbc, UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, last_seen_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'ONLINE', 'WINDOWS', ?, ?, ?, 0)",
                id, tenant, "host-" + id.toString().substring(0, 8), now, now, now);
        return id;
    }

    private void insertCommand(JdbcTemplate jdbc, UUID tenant, UUID device, String type) {
        insertCommandReturningId(jdbc, tenant, device, type);
    }

    private UUID insertCommandReturningId(JdbcTemplate jdbc, UUID tenant, UUID device, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_commands "
                        + "(id, tenant_id, device_id, command_type, idempotency_key, "
                        + " issued_by_subject) VALUES (?, ?, ?, ?, ?, 'maker')",
                id, tenant, device, type, "idem-" + UUID.randomUUID());
        return id;
    }

    private void insertEnforceRevisionWithCommand(JdbcTemplate jdbc, UUID tenant, UUID device, UUID commandId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policy_revisions "
                        + "(id, tenant_id, device_id, scope_type, operation, "
                        + " screensaver_enabled, policy_hash_sha256, reason, command_id, "
                        + " created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', true, ?, 'r', ?, 'maker', ?)",
                UUID.randomUUID(), tenant, device, HASH, commandId, Timestamp.from(Instant.now()));
    }

    private void insertActivePolicyWithStatus(JdbcTemplate jdbc, UUID tenant, UUID device,
                                              UUID revId, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policies "
                        + "(id, tenant_id, device_id, scope_type, operation, current_revision_id, "
                        + " screensaver_enabled, policy_hash_sha256, last_enforcement_status, "
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', ?, true, ?, ?, "
                        + " 'maker', ?, 'maker', ?)",
                UUID.randomUUID(), tenant, device, revId, HASH, status, now, now);
    }

    private UUID insertEnforceRevision(JdbcTemplate jdbc, UUID tenant, UUID device) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policy_revisions "
                        + "(id, tenant_id, device_id, scope_type, operation, "
                        + " screensaver_enabled, screensaver_timeout_seconds, screensaver_secure, "
                        + " wallpaper_enabled, wallpaper_style, wallpaper_user_cannot_change, "
                        + " policy_hash_sha256, reason, created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', true, 600, true, "
                        + " true, 'FILL', true, ?, 'kiosk lockdown', 'maker', ?)",
                id, tenant, device, HASH, Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertClearRevision(JdbcTemplate jdbc, UUID tenant, UUID device) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policy_revisions "
                        + "(id, tenant_id, device_id, scope_type, operation, "
                        + " policy_hash_sha256, reason, created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'CLEAR', ?, 'unmanage', 'maker', ?)",
                id, tenant, device, HASH, Timestamp.from(Instant.now()));
        return id;
    }

    private void insertActivePolicy(JdbcTemplate jdbc, UUID tenant, UUID device, UUID revId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policies "
                        + "(id, tenant_id, device_id, scope_type, operation, current_revision_id, "
                        + " screensaver_enabled, wallpaper_enabled, wallpaper_style, policy_hash_sha256, "
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'ENFORCE', ?, true, true, 'FILL', ?, "
                        + " 'maker', ?, 'maker', ?)",
                UUID.randomUUID(), tenant, device, revId, HASH, now, now);
    }

    private void insertClearedPolicy(JdbcTemplate jdbc, UUID tenant, UUID device, UUID revId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policies "
                        + "(id, tenant_id, device_id, scope_type, operation, current_revision_id, "
                        + " policy_hash_sha256, cleared_at, cleared_by_subject, "
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at) "
                        + "VALUES (?, ?, ?, 'DEVICE', 'CLEAR', ?, ?, ?, 'checker', "
                        + " 'maker', ?, 'maker', ?)",
                UUID.randomUUID(), tenant, device, revId, HASH, now, now, now);
    }
}
