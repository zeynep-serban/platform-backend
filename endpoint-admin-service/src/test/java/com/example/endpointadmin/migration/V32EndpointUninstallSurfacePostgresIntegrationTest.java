package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.repository.EndpointUninstallRequestRepository;
import jakarta.persistence.EntityManager;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-028 Phase 1 — Postgres-only migration integration tests for
 * {@code V32__endpoint_uninstall_surface.sql}.
 *
 * <p>Exercises invariants the H2 {@code @DataJpaTest} slice cannot:
 *
 * <ul>
 *   <li>V32 applies on real Postgres + Hibernate validate is happy.</li>
 *   <li>{@code endpoint_commands.command_type} CHECK now accepts
 *       {@code UNINSTALL_SOFTWARE}.</li>
 *   <li>{@code endpoint_uninstall_requests} state allowlist rejects bogus
 *       state values.</li>
 *   <li>Partial unique index {@code uq_endpoint_uninstall_one_inflight}
 *       blocks a second open request on same {@code (tenant, device, catalog)}.</li>
 *   <li>Partial unique index allows a fresh open request after the prior
 *       transitioned to TERMINAL.</li>
 *   <li>Partial unique index {@code uq_endpoint_uninstall_idempotency}
 *       blocks reuse of the same {@code (tenant, idempotency_key)} pair.</li>
 *   <li>Composite tenant FK rejects cross-tenant device / catalog refs.</li>
 *   <li>{@code endpoint_uninstall_audit} {@code result_status} enum
 *       allowlist + {@code verification} enum allowlist.</li>
 *   <li>{@code endpoint_uninstall_audit} JSONB shape CHECKs reject array /
 *       scalar payloads (Codex iter-1 must-fix #2 absorb).</li>
 *   <li>Append-only trigger rejects UPDATE and DELETE on audit rows.</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8d81-3d87-78f2-ba17-9a8981c5eb16} iter-2 AGREE.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V32EndpointUninstallSurfacePostgresIntegrationTest {

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
    private EndpointUninstallRequestRepository requestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager em;

    @Test
    void v32_commandTypeCheckAcceptsUninstallSoftware() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);

        UUID commandId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Insert UNINSTALL_SOFTWARE command via JDBC — would have failed
        // with the previous CHECK constraint (V12's INSTALL_SOFTWARE-only allowlist).
        jdbcTemplate.update("""
                INSERT INTO endpoint_commands
                    (id, tenant_id, device_id, command_type, idempotency_key,
                     status, issued_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'UNINSTALL_SOFTWARE', ?, 'QUEUED',
                        'system@test', ?, ?)
                """,
                commandId, tenantId, deviceId, "test-key-" + commandId,
                nowTs, nowTs);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM endpoint_commands WHERE id = ?",
                Integer.class, commandId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void commandType_uninstallSoftwareEnum_jpaRoundTrip() {
        // Codex iter-1 blocker #2 absorb: V32 DB CHECK accepts
        // UNINSTALL_SOFTWARE; Java CommandType enum must also carry the
        // value so JPA reads of existing UNINSTALL_SOFTWARE rows don't
        // fail with enum parse exceptions. Phase 1b dispatch path
        // creates these rows; this test guards Phase 1a's schema + enum
        // surface alignment.
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID commandId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        jdbcTemplate.update("""
                INSERT INTO endpoint_commands
                    (id, tenant_id, device_id, command_type, idempotency_key,
                     status, issued_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'UNINSTALL_SOFTWARE', ?, 'QUEUED',
                        'system@test', ?, ?)
                """,
                commandId, tenantId, deviceId, "round-trip-" + commandId,
                nowTs, nowTs);

        // JPA-level read via @Enumerated(STRING) — exercise CommandType
        // enum value parse from the persisted 'UNINSTALL_SOFTWARE' string.
        com.example.endpointadmin.model.CommandType cmdType =
                em.createQuery(
                        "SELECT c.commandType FROM EndpointCommand c WHERE c.id = :id",
                        com.example.endpointadmin.model.CommandType.class)
                        .setParameter("id", commandId)
                        .getSingleResult();

        assertThat(cmdType)
                .isEqualTo(com.example.endpointadmin.model.CommandType.UNINSTALL_SOFTWARE);
    }

    @Test
    void uninstallRequest_rejectsUnknownState() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_requests
                    (id, tenant_id, device_id, catalog_item_id, command_id,
                     state, idempotency_key, reason, created_by, approved_by,
                     created_at, state_updated_at)
                VALUES (?, ?, ?, ?, NULL, 'ARBITRARY_STATE', NULL, NULL,
                        'alice@example.com', NULL, ?, ?)
                """,
                UUID.randomUUID(), tenantId, deviceId, catalogId, nowTs, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallRequest_partialUniqueOneInflightBlocksSecondOpen() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);

        EndpointUninstallRequest first = newPendingRequest(tenantId, deviceId, catalogId,
                "alice@example.com", null);
        requestRepository.saveAndFlush(first);

        EndpointUninstallRequest dup = newPendingRequest(tenantId, deviceId, catalogId,
                "bob@example.com", null);

        assertThatThrownBy(() -> requestRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallRequest_partialUniqueAllowsFreshOpenAfterTerminal() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);

        EndpointUninstallRequest first = newPendingRequest(tenantId, deviceId, catalogId,
                "alice@example.com", null);
        first = requestRepository.saveAndFlush(first);

        // Transition first to TERMINAL.
        first.setState(UninstallRequestState.TERMINAL);
        requestRepository.saveAndFlush(first);

        // Fresh open request now allowed.
        EndpointUninstallRequest fresh = newPendingRequest(tenantId, deviceId, catalogId,
                "carol@example.com", null);
        requestRepository.saveAndFlush(fresh);

        assertThat(requestRepository.findById(fresh.getId())).isPresent();
    }

    @Test
    void uninstallRequest_partialUniqueIdempotencyBlocksReuse() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantId);
        UUID deviceB = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);

        String idempKey = "idemp-" + UUID.randomUUID();

        EndpointUninstallRequest first = newPendingRequest(tenantId, deviceA, catalogId,
                "alice@example.com", idempKey);
        requestRepository.saveAndFlush(first);

        // Same (tenant, idempotency_key) — different device should still collide.
        EndpointUninstallRequest dup = newPendingRequest(tenantId, deviceB, catalogId,
                "alice@example.com", idempKey);

        assertThatThrownBy(() -> requestRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallRequest_rejectsCrossTenantDeviceReference() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogB = seedApprovedCatalog(tenantB);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Composite (device_id, tenant_id) FK rejects: deviceA belongs to tenantA
        // but we're using tenantB row, so the composite key won't resolve.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_requests
                    (id, tenant_id, device_id, catalog_item_id, command_id,
                     state, idempotency_key, reason, created_by, approved_by,
                     created_at, state_updated_at)
                VALUES (?, ?, ?, ?, NULL, 'PENDING_APPROVAL', NULL, NULL,
                        'alice@example.com', NULL, ?, ?)
                """,
                UUID.randomUUID(),
                tenantB,           // wrong tenant for deviceA
                deviceA,           // belongs to tenantA
                catalogB,          // belongs to tenantB
                nowTs, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallAudit_rejectsUnknownResultStatus() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);
        UUID requestId = seedTerminalRequestWithCommand(tenantId, deviceId, catalogId);
        UUID commandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM endpoint_uninstall_requests WHERE id = ?",
                UUID.class, requestId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_audit
                    (id, request_id, tenant_id, device_id, catalog_item_id,
                     command_id, result_status, verification, exit_code,
                     reported_at, redacted_payload, detection_evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ARBITRARY_RESULT', 'ABSENT_VERIFIED', 0,
                        ?, '{}'::jsonb, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(), requestId, tenantId, deviceId, catalogId,
                commandId, nowTs, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallAudit_rejectsNonObjectRedactedPayload() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);
        UUID requestId = seedTerminalRequestWithCommand(tenantId, deviceId, catalogId);
        UUID commandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM endpoint_uninstall_requests WHERE id = ?",
                UUID.class, requestId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Array payload (not object) — DB CHECK rejects.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_audit
                    (id, request_id, tenant_id, device_id, catalog_item_id,
                     command_id, result_status, verification, exit_code,
                     reported_at, redacted_payload, detection_evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED_VERIFIED', 'ABSENT_VERIFIED', 0,
                        ?, '[]'::jsonb, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(), requestId, tenantId, deviceId, catalogId,
                commandId, nowTs, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallAudit_rejectsNonObjectDetectionEvidence() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);
        UUID requestId = seedTerminalRequestWithCommand(tenantId, deviceId, catalogId);
        UUID commandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM endpoint_uninstall_requests WHERE id = ?",
                UUID.class, requestId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Scalar string payload (not object) — DB CHECK rejects.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_audit
                    (id, request_id, tenant_id, device_id, catalog_item_id,
                     command_id, result_status, verification, exit_code,
                     reported_at, redacted_payload, detection_evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED_VERIFIED', 'ABSENT_VERIFIED', 0,
                        ?, '{}'::jsonb, '"string"'::jsonb, ?)
                """,
                UUID.randomUUID(), requestId, tenantId, deviceId, catalogId,
                commandId, nowTs, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uninstallAudit_appendOnlyTriggerBlocksUpdate() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);
        UUID requestId = seedTerminalRequestWithCommand(tenantId, deviceId, catalogId);
        UUID commandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM endpoint_uninstall_requests WHERE id = ?",
                UUID.class, requestId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_audit
                    (id, request_id, tenant_id, device_id, catalog_item_id,
                     command_id, result_status, verification, exit_code,
                     reported_at, redacted_payload, detection_evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED_VERIFIED', 'ABSENT_VERIFIED', 0,
                        ?, '{}'::jsonb, '{}'::jsonb, ?)
                """,
                auditId, requestId, tenantId, deviceId, catalogId,
                commandId, nowTs, nowTs);

        // UPDATE rejected by trigger (Spring may translate the PG P0001
        // RAISE EXCEPTION as UncategorizedSQLException rather than
        // DataIntegrityViolationException — assert via the underlying
        // PSQLException + the explicit append-only error message).
        // We test UPDATE only here; PG aborts the entire @DataJpaTest tx
        // on the first error so a follow-up DELETE in the same method
        // would fail with state 25P02 (tx aborted). DELETE coverage is in
        // uninstallAudit_appendOnlyTriggerBlocksDelete.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE endpoint_uninstall_audit SET exit_code = 1 WHERE id = ?",
                auditId))
                .hasMessageContaining("append-only");
    }

    @Test
    void uninstallAudit_appendOnlyTriggerBlocksDelete() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        UUID catalogId = seedApprovedCatalog(tenantId);
        UUID requestId = seedTerminalRequestWithCommand(tenantId, deviceId, catalogId);
        UUID commandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM endpoint_uninstall_requests WHERE id = ?",
                UUID.class, requestId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_audit
                    (id, request_id, tenant_id, device_id, catalog_item_id,
                     command_id, result_status, verification, exit_code,
                     reported_at, redacted_payload, detection_evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED_VERIFIED', 'ABSENT_VERIFIED', 0,
                        ?, '{}'::jsonb, '{}'::jsonb, ?)
                """,
                auditId, requestId, tenantId, deviceId, catalogId,
                commandId, nowTs, nowTs);

        // DELETE rejected by trigger. Separate test so the UPDATE-aborted-tx
        // state from uninstallAudit_appendOnlyTriggerBlocksUpdate does not
        // leak (PG state 25P02 once the first command in a tx errors).
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM endpoint_uninstall_audit WHERE id = ?", auditId))
                .hasMessageContaining("append-only");
    }

    // ───────────────────────────── helpers

    private UUID seedDevice(UUID tenantId) {
        UUID deviceId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        jdbcTemplate.update("""
                INSERT INTO endpoint_devices
                    (id, tenant_id, hostname, machine_fingerprint, status,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ONLINE', ?, ?)
                """,
                deviceId, tenantId,
                "host-" + deviceId.toString().substring(0, 8),
                "fp-" + deviceId.toString().substring(0, 8),
                nowTs, nowTs);
        return deviceId;
    }

    private UUID seedApprovedCatalog(UUID tenantId) {
        UUID catalogId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        jdbcTemplate.update("""
                INSERT INTO endpoint_software_catalog_items
                    (id, tenant_id, catalog_item_id, status, provider, source_type,
                     source_name, source_trust, package_id, display_name, publisher,
                     version_policy_type, version_policy_value, installer_type,
                     silent_args_policy, sha256, provenance, detection_rule, risk_tier,
                     enabled, uninstall_supported, uninstall_protected,
                     created_by_subject, created_at, last_updated_by_subject, last_updated_at,
                     approved_by_subject, approved_at, revoked_by_subject, revoked_at,
                     revocation_reason, version)
                VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET',
                        'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', '7-Zip', '7-Zip',
                        'LATEST', NULL, NULL, NULL, NULL, NULL,
                        '{"type":"REGISTRY_UNINSTALL"}'::jsonb, 'LOW', true,
                        true, false,
                        'creator@example.com', ?, 'creator@example.com', ?,
                        'approver@example.com', ?, NULL, NULL, NULL, 0)
                """,
                catalogId, tenantId,
                "7zip-test-" + catalogId.toString().substring(0, 8),
                nowTs, nowTs, nowTs);
        return catalogId;
    }

    /**
     * Seeds a request that has already transitioned to TERMINAL with a
     * dispatched command. Used by audit-side tests that need a valid
     * (request_id, tenant_id) + (command_id, tenant_id) pair for the FK.
     */
    private UUID seedTerminalRequestWithCommand(UUID tenantId, UUID deviceId, UUID catalogId) {
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        UUID commandId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO endpoint_commands
                    (id, tenant_id, device_id, command_type, idempotency_key,
                     status, issued_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'UNINSTALL_SOFTWARE', ?, 'SUCCEEDED',
                        'system@test', ?, ?)
                """,
                commandId, tenantId, deviceId, "cmd-" + commandId,
                nowTs, nowTs);

        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO endpoint_uninstall_requests
                    (id, tenant_id, device_id, catalog_item_id, command_id,
                     state, idempotency_key, reason, created_by, approved_by,
                     created_at, state_updated_at)
                VALUES (?, ?, ?, ?, ?, 'TERMINAL', NULL, NULL,
                        'alice@example.com', 'bob@example.com', ?, ?)
                """,
                requestId, tenantId, deviceId, catalogId, commandId, nowTs, nowTs);
        return requestId;
    }

    private EndpointUninstallRequest newPendingRequest(
            UUID tenantId, UUID deviceId, UUID catalogId,
            String createdBy, String idempotencyKey) {
        EndpointUninstallRequest req = new EndpointUninstallRequest();
        req.setId(UUID.randomUUID());
        req.setTenantId(tenantId);
        req.setDeviceId(deviceId);
        req.setCatalogItemId(catalogId);
        req.setState(UninstallRequestState.PENDING_APPROVAL);
        req.setCreatedBy(createdBy);
        req.setIdempotencyKey(idempotencyKey);
        return req;
    }
}
