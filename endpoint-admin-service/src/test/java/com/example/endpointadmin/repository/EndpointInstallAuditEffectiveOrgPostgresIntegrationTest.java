package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.e-A regression guard —
 * {@link EndpointInstallAuditRepository} per-device + tenant-wide
 * history + audit ownership reads migrated to the canonical
 * effective-org filter (Codex 019e8dde Option B sub-slice e-A AGREE;
 * slice c iter-1 index-friendly form).
 *
 * <p>Canonical predicate:
 * <pre>
 *   WHERE a.tenant_id = :orgId
 *     AND (a.org_id = :orgId OR a.org_id IS NULL)
 *     [+ a.device_id = :deviceId for device-scoped variants]
 *     [+ a.id = :id for ownership gate]
 * </pre>
 *
 * <p>Assertions: ownership gate (canonical / legacy NULL insert
 * REJECTED by V36 / cross-org), device history Page (canonical ordering
 * + countQuery), tenant-wide history Page (canonical + cross-org), a
 * dedicated legacy-NULL UPDATE rejection proof, plus a non-migration
 * guard for {@code findByCommandId} (V12 {@code command_id UNIQUE} backs
 * per-result uniqueness independent of effective-org).
 *
 * <p>Legacy NULL row REJECTED by V36 (C1.5) — CHECK org_id IS NOT NULL
 * makes {@code org_id IS NULL} physically unconstructable on the source
 * tables; the prior trigger-bypass {@code UPDATE org_id = NULL} fixture
 * is now rejected 23514. The OR-fallback read branch stays, dead until
 * A5.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointInstallAuditEffectiveOrgPostgresIntegrationTest {

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
    private EndpointInstallAuditRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    // ───────────────────────── Ownership gate (Method 1) ─────────────────────────

    @Test
    void ownership_canonicalRow_visibleViaEffectiveOrg() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.canonical");
        UUID commandId = seedCommand(orgA, deviceId, "INSTALL_SOFTWARE", "key-A");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);

        Optional<EndpointInstallAudit> hit = repository
                .findVisibleToOrgAndId(orgA, auditId);

        assertThat(hit).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId));
    }

    @Test
    void ownership_legacyNullOrgUpdate_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL ownership-gate visibility fixture is
        // replaced by its invariant proof — a trigger-bypass
        // UPDATE org_id = NULL on a persisted canonical row is rejected
        // 23514. The OR-fallback branch in findVisibleToOrgAndId is left
        // intact (provably dead until A5 removes it with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.legacy");
        UUID commandId = seedCommand(orgA, deviceId, "INSTALL_SOFTWARE", "key-legacy");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);

        // Terminal: the rejected UPDATE must be the last DB op in the method.
        assertMakeAuditOrgIdNullRejectedByV36(auditId);
    }

    @Test
    void ownership_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA, "INSTALL_SOFTWARE", "key-A");
        UUID auditA = persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Optional<EndpointInstallAudit> hit = repository
                .findVisibleToOrgAndId(orgB, auditA);

        assertThat(hit).isEmpty();
    }

    // ───────────────────────── Device history page (Method 3) ─────────────────────────

    @Test
    void deviceHistoryPage_canonicalRows_orderedByReportedAtDesc() {
        // Codex 019e8dde iter-1 REVISE #1 hardening: disjoint reportedAt
        // values + containsExactly(newer, older) so the inline
        // ORDER BY a.reportedAt DESC contract is pinned by the test (a
        // regression removing the sort from the @Query would fail this
        // assertion). Under C1.5/V36 the prior legacy-NULL second row is
        // no longer constructable; its rejection is pinned by
        // ownership_legacyNullOrgUpdate_isRejectedByV36. Two canonical rows
        // retain the ORDER BY DESC + countQuery coverage.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.deviceHist");
        UUID commandNewer = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-newer");
        UUID commandOlder = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-older");
        Instant newer = Instant.parse("2026-06-03T10:00:00Z");
        Instant older = Instant.parse("2026-06-03T09:00:00Z");
        UUID auditNewer = persistAuditCanonical(
                orgA, deviceId, commandNewer, catalogId, newer);
        UUID auditOlder = persistAuditCanonical(
                orgA, deviceId, commandOlder, catalogId, older);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
                        orgA, deviceId, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(EndpointInstallAudit::getId)
                .as("page returns rows in ORDER BY reportedAt DESC: "
                        + "newer first, older second — proves the inline sort holds")
                .containsExactly(auditNewer, auditOlder);
        assertThat(page.getTotalElements())
                .as("countQuery sibling totals over the effective-org predicate")
                .isEqualTo(2L);
    }

    @Test
    void deviceHistoryPage_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA,
                "INSTALL_SOFTWARE", "key-A");
        persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
                        orgB, deviceA, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0L);
    }

    // ───────────────────────── Tenant-wide history page (Method 4) ─────────────────────────

    @Test
    void tenantWideHistoryPage_includesMultipleDevicesUnderSameOrg() {
        // Codex 019e8dde iter-1 REVISE #1 hardening: 2 devices under
        // same org, disjoint reportedAt, containsExactly(newer, older)
        // pins the inline ORDER BY reportedAt DESC on the tenant-wide
        // query (a regression removing the sort would fail this).
        UUID orgA = UUID.randomUUID();
        UUID device1 = seedDevice(orgA);
        UUID device2 = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.tenantwide");
        UUID command1 = seedCommand(orgA, device1,
                "INSTALL_SOFTWARE", "key-d1");
        UUID command2 = seedCommand(orgA, device2,
                "INSTALL_SOFTWARE", "key-d2");
        Instant newer = Instant.parse("2026-06-03T10:00:00Z");
        Instant older = Instant.parse("2026-06-03T09:00:00Z");
        UUID auditNewer = persistAuditCanonical(
                orgA, device1, command1, catalogId, newer);
        UUID auditOlder = persistAuditCanonical(
                orgA, device2, command2, catalogId, older);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgOrderByReportedAtDesc(orgA, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(EndpointInstallAudit::getId)
                .as("page returns rows in ORDER BY reportedAt DESC: "
                        + "newer device's audit first, older device's audit second")
                .containsExactly(auditNewer, auditOlder);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void tenantWideHistoryPage_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA,
                "INSTALL_SOFTWARE", "key-A");
        persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgOrderByReportedAtDesc(orgB, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0L);
    }

    // ───────────────────────── Non-migration guard: findByCommandId ─────────────────────────

    @Test
    void findByCommandId_remainsTenantAndOrgUnscoped_byDesign() {
        // V12 (command_id UNIQUE) + composite FK to endpoint_commands
        // (id, tenant_id) enforce per-result uniqueness + tenant integrity
        // by themselves. findByCommandId is an idempotency probe (not an
        // admin visibility read path) and stays signature-stable.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.cmdId");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-cmdId");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);

        Optional<EndpointInstallAudit> hit = repository.findByCommandId(commandId);

        assertThat(hit).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId))
                .hasValueSatisfying(a -> assertThat(a.getCommandId()).isEqualTo(commandId));
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private UUID seedDevice(UUID tenantId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, machine_fingerprint, status, "
                        + " os_type, os_version, agent_version, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, "host-" + id, "fp-" + id, "ONLINE",
                "WINDOWS", "Windows 11", "1.0.0", now, now, 0L);
        return id;
    }

    private UUID seedCatalog(UUID tenantId, String packageId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, version_policy_value, installer_type,"
                        + " silent_args_policy, sha256, provenance, detection_rule,"
                        + " risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, ?, 'LATEST', NULL,"
                        + " 'WINGET_SILENT', 'DEFAULT', NULL, NULL,"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, tenantId, packageId, packageId, "DisplayName-" + packageId,
                "Publisher", now, now);
        return id;
    }

    private UUID seedCommand(UUID tenantId, UUID deviceId, String commandType,
                             String idempotencyKey) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_commands ("
                        + "id, tenant_id, device_id, command_type, idempotency_key, "
                        + " issued_by_subject, issued_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, deviceId, commandType, idempotencyKey,
                "issuer@example.com", now, now, now, 0L);
        return id;
    }

    /**
     * Persist a canonical audit row via the JPA repository — V29 trigger
     * fills org_id from tenant_id, so the row lands canonical
     * (org_id = tenant_id). Default `reportedAt` is "now"; the overload
     * with explicit `reportedAt` is used by Page sort tests to seed
     * disjoint timestamps and assert `ORDER BY reportedAt DESC`
     * content-side semantics.
     */
    private UUID persistAuditCanonical(UUID tenantId, UUID deviceId,
            UUID commandId, UUID catalogItemId) {
        return persistAuditCanonical(tenantId, deviceId, commandId,
                catalogItemId, Instant.now());
    }

    private UUID persistAuditCanonical(UUID tenantId, UUID deviceId,
            UUID commandId, UUID catalogItemId, Instant reportedAt) {
        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(tenantId);
        // Faz 21.1 PR2b-iv.e-A — explicit canonical write (PR2b-ii
        // Option A inline) so the JPA path matches the production
        // write contract even if the V29 trigger is bypassed.
        audit.setOrgId(tenantId);
        audit.setDeviceId(deviceId);
        audit.setCommandId(commandId);
        audit.setCatalogItemId(catalogItemId);
        audit.setCatalogPackageId("pkg-" + catalogItemId);
        audit.setCatalogRowVersion(0L);
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(reportedAt);
        audit.setActorSubject("actor@example.com");
        audit.setResultStatus(CommandResultStatus.SUCCEEDED);
        audit.setReportedAt(reportedAt);
        audit.setPostVerification(InstallPostVerification.SATISFIED);
        EndpointInstallAudit saved = repository.saveAndFlush(audit);
        return saved.getId();
    }

    /**
     * C1.5/V36 terminal-assertion helper: assert that forcing the legacy
     * NULL shape on an existing canonical audit row via direct
     * {@code UPDATE org_id = NULL} is REJECTED with SQLSTATE 23514.
     *
     * <p>{@code DISABLE TRIGGER USER} is essential here (Codex 019e8dde
     * iter-1 REVISE #2): V29's {@code BEFORE INSERT OR UPDATE} function
     * would otherwise silently refill {@code NEW.org_id} from
     * {@code tenant_id}, masking the V36 CHECK. With the trigger disabled
     * the explicit {@code org_id = NULL} reaches the V36 CHECK
     * {@code (org_id IS NOT NULL)} and is rejected.
     *
     * <p>Transaction-hygiene (Codex 019e92a7): the rejected UPDATE aborts
     * the surrounding tx, so this helper performs NO ENABLE-TRIGGER finally
     * and MUST be the last DB op of its caller — the @DataJpaTest per-method
     * rollback re-enables the trigger.
     */
    private void assertMakeAuditOrgIdNullRejectedByV36(UUID auditId) {
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".endpoint_install_audit DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA
                        + ".endpoint_install_audit SET org_id = NULL WHERE id = ?",
                auditId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("source org_id NULL must be 23514 check_violation")
                        .isEqualTo("23514"));
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
