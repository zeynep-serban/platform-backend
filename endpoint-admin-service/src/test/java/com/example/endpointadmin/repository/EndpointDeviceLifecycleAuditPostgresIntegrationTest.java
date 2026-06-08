package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.DeviceLifecycleAction;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDeviceLifecycleAudit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V56 migration contract — {@code endpoint_device_lifecycle_audit} (device
 * DECOMMISSION/REACTIVATE lifecycle audit). Codex 019ea789 migration-test
 * minimums:
 * <ol>
 *   <li>audit persists + {@code findTopByDeviceIdAndOrgIdAndActionOrderBy
 *       CreatedAtDesc} returns the latest DECOMMISSION (reactivate-target rule
 *       source);</li>
 *   <li>append-only — UPDATE and DELETE are rejected by the V56 trigger;</li>
 *   <li>org-composite FK — an audit referencing a device under a DIFFERENT org
 *       is rejected (no cross-org parent);</li>
 *   <li>reason CHECK — a blank reason is rejected by {@code btrim(reason) <> ''}.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceLifecycleAuditPostgresIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private EndpointDeviceLifecycleAuditRepository auditRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void decommissionAudit_persists_andIsReturnedByLatestQuery() {
        UUID org = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, org, "dev-1");

        auditRepository.saveAndFlush(buildAudit(org, deviceId,
                DeviceLifecycleAction.DECOMMISSION, DeviceStatus.ONLINE,
                DeviceStatus.DECOMMISSIONED, "ops cleanup"));

        Optional<EndpointDeviceLifecycleAudit> latest = auditRepository
                .findTopByDeviceIdAndOrgIdAndActionOrderByCreatedAtDesc(
                        deviceId, org, DeviceLifecycleAction.DECOMMISSION);
        assertThat(latest).isPresent().hasValueSatisfying(a -> {
            assertThat(a.getFromStatus()).isEqualTo(DeviceStatus.ONLINE);
            assertThat(a.getToStatus()).isEqualTo(DeviceStatus.DECOMMISSIONED);
            assertThat(a.getReason()).isEqualTo("ops cleanup");
            assertThat(a.getEffectiveOrgId()).isEqualTo(org);
        });
    }

    @Test
    void auditRow_isAppendOnly_updateAndDeleteRejected() {
        UUID org = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, org, "dev-2");
        EndpointDeviceLifecycleAudit audit = buildAudit(org, deviceId,
                DeviceLifecycleAction.DECOMMISSION, DeviceStatus.ONLINE,
                DeviceStatus.DECOMMISSIONED, "retire");
        auditRepository.saveAndFlush(audit);
        UUID auditId = audit.getId();

        // The V56 append-only trigger RAISEs (SQLSTATE P0001) → Spring maps it
        // to the broader DataAccessException (not the 23xxx-only
        // DataIntegrityViolationException used by the FK/CHECK paths below).
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA
                + ".endpoint_device_lifecycle_audit SET reason = 'tampered' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA
                + ".endpoint_device_lifecycle_audit WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditFk_crossOrgDevice_isRejected() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "dev-a");

        // Audit references deviceA but under orgB (tenant_id=orgB → compat
        // trigger fills org_id=orgB). The composite FK (device_id, org_id) ->
        // endpoint_devices(id, org_id) has no (deviceA, orgB) parent → reject.
        assertThatThrownBy(() -> insertAuditRaw(orgB, deviceA, "cross-org"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditReason_blank_isRejectedByCheck() {
        UUID org = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, org, "dev-3");

        assertThatThrownBy(() -> insertAuditRaw(org, deviceId, "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────────────────────── helpers ─────────────────────────

    private EndpointDeviceLifecycleAudit buildAudit(UUID org, UUID deviceId,
            DeviceLifecycleAction action, DeviceStatus from, DeviceStatus to, String reason) {
        EndpointDeviceLifecycleAudit a = new EndpointDeviceLifecycleAudit();
        a.setTenantId(org);
        a.setOrgId(org);
        a.setDeviceId(deviceId);
        a.setAction(action);
        a.setFromStatus(from);
        a.setToStatus(to);
        a.setActorSubject("admin-subject");
        a.setReason(reason);
        return a;
    }

    private void insertDeviceCanonical(UUID id, UUID org, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-08T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, now, now);
    }

    private void insertAuditRaw(UUID tenant, UUID deviceId, String reason) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-08T10:05:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_device_lifecycle_audit "
                        + "(id, tenant_id, org_id, device_id, action, from_status, to_status, "
                        + " actor_subject, reason, cancelled_commands, revoked_tokens, "
                        + " finalized_uninstalls, created_at) "
                        + "VALUES (?, ?, NULL, ?, 'DECOMMISSION', 'ONLINE', 'DECOMMISSIONED', "
                        + " 'admin', ?, 0, 0, 0, ?)",
                UUID.randomUUID(), tenant, deviceId, reason, now);
    }
}
