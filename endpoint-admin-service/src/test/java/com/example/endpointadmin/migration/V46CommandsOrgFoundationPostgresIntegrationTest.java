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
 * Faz 21.1 Cleanup C4 step-6 regression guard — V46 endpoint_commands HUB org
 * foundation. Asserts the full org machinery (trigger-fill + match/non-null
 * CHECK), the UNIQUE(id, org_id) FK-target enabler the consumer flips need
 * (V48 install_audit / V49 uninstall family), and the idempotency UNIQUE
 * single-arbiter swap: (tenant_id, idempotency_key) is dropped and
 * (org_id, idempotency_key) is the sole arbiter (V35 single-arbiter lesson).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V46CommandsOrgFoundationPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String CMD = SCHEMA + ".endpoint_commands";

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void commandsHaveValidatedOrgChecks_andIdOrgUnique() {
        for (String con : new String[]{"endpoint_commands_org_id_match", "endpoint_commands_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_commands_id_org_id_key' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void idempotencyArbiterSwapped_orgUniquePresent_tenantUniqueDropped() {
        // New single arbiter present + correct shape.
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='uq_endpoint_commands_org_idempotency' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (org_id, idempotency_key)");
        // Legacy tenant-keyed arbiter dropped (single arbiter — V35 lesson).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='uq_endpoint_commands_tenant_idempotency'", Long.class))
                .as("old tenant idempotency unique dropped").isEqualTo(0L);
        // The (id, tenant_id) unique stays (A6 deferred; reads tenant-keyed).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='uq_endpoint_commands_id_tenant' AND contype='u'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void legacyWriter_commandOrgIdOmitted_filledByTriggerToTenant() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID cmdId = UUID.randomUUID();
        insertCommandNoOrg(cmdId, org, device, "idem-" + cmdId);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + CMD + " WHERE id=?", UUID.class, cmdId)).isEqualTo(org);
    }

    @Test
    void duplicateOrgIdempotency_isRejectedByOrgArbiter_23505() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        String key = "dup-key";
        insertCommandNoOrg(UUID.randomUUID(), org, device, key);
        // Same (tenant_id -> org_id, idempotency_key): the org-keyed arbiter rejects it (23505).
        assertThatThrownBy(() -> insertCommandNoOrg(UUID.randomUUID(), org, device, key))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void explicitCommandOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID org = UUID.randomUUID(), tenant = UUID.randomUUID();
        UUID device = seedDevice(tenant);
        // Disable the compat trigger so the explicit mismatched org_id is not auto-filled.
        jdbc.execute("ALTER TABLE " + CMD + " DISABLE TRIGGER USER");
        // tenant<>org: match CHECK (org_id = tenant_id) fires 23514.
        assertThatThrownBy(() -> insertCommandExplicitOrg(UUID.randomUUID(), tenant, org, device, "idem-mismatch"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23514"));
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

    /** Legacy writer: org_id omitted — the V46 trigger fills it from tenant_id. */
    private void insertCommandNoOrg(UUID id, UUID tenant, UUID device, String idempotencyKey) {
        jdbc.update("INSERT INTO " + CMD + " "
                        + "(id, tenant_id, device_id, command_type, idempotency_key, issued_by_subject) "
                        + "VALUES (?, ?, ?, 'COLLECT_INVENTORY', ?, 'tester')",
                id, tenant, device, idempotencyKey);
    }

    /** Explicit org_id (used after trigger disabled) to exercise the match CHECK. */
    private void insertCommandExplicitOrg(UUID id, UUID tenant, UUID org, UUID device, String idempotencyKey) {
        jdbc.update("INSERT INTO " + CMD + " "
                        + "(id, tenant_id, org_id, device_id, command_type, idempotency_key, issued_by_subject) "
                        + "VALUES (?, ?, ?, ?, 'COLLECT_INVENTORY', ?, 'tester')",
                id, tenant, org, device, idempotencyKey);
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
