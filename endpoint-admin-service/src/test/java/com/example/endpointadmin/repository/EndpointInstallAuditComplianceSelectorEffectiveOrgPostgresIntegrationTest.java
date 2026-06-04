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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.e-B regression guard —
 * {@link EndpointInstallAuditRepository} compliance evaluator
 * selector + AG-028 uninstall provenance guard migrated to the
 * canonical effective-org filter (Codex 019e8dde Option B sub-slice
 * e-B AGREE; slice c iter-1 index-friendly form).
 *
 * <p>Canonical predicate:
 * <pre>
 *   WHERE a.tenant_id = :orgId
 *     AND (a.org_id = :orgId OR a.org_id IS NULL)
 *     AND a.device_id = :deviceId
 *     AND a.catalog_item_id = :catalogItemId
 *     AND a.result_status = SUCCEEDED
 *     AND a.post_verification = SATISFIED
 *     [+ a.created_at < :before for selector]
 * </pre>
 *
 * <p>Nine assertions covering both methods:
 * <ol>
 *   <li>existsSucceededSatisfiedByOrgDeviceCatalog: canonical row
 *       returns true.</li>
 *   <li>existsSucceededSatisfiedByOrgDeviceCatalog: legacy NULL insert
 *       REJECTED by V36 (C1.5) — CHECK org_id IS NOT NULL makes
 *       {@code org_id IS NULL} unconstructable; a trigger-bypass
 *       {@code UPDATE org_id = NULL} is rejected 23514. OR-fallback read
 *       branch stays, dead until A5.</li>
 *   <li>existsSucceededSatisfiedByOrgDeviceCatalog: cross-org returns
 *       false.</li>
 *   <li>existsSucceededSatisfiedByOrgDeviceCatalog: FAILED + SATISFIED
 *       (wrong status) returns false — result_status filter guard.</li>
 *   <li>existsSucceededSatisfiedByOrgDeviceCatalog: SUCCEEDED +
 *       UNSATISFIED returns false — post_verification filter guard
 *       (Codex 019e8dde iter-1 REVISE #2).</li>
 *   <li>findLatestSucceededSatisfiedByOrgDeviceCatalogBefore: canonical
 *       row matched via wrapper.</li>
 *   <li>findLatestSucceededSatisfiedByOrgDeviceCatalogBefore: legacy
 *       NULL insert REJECTED by V36 (C1.5) — trigger-bypass
 *       {@code UPDATE org_id = NULL} rejected 23514; OR-fallback read
 *       branch stays, dead until A5.</li>
 *   <li>findLatestSucceededSatisfiedByOrgDeviceCatalogBefore: cross-org
 *       miss returns empty.</li>
 *   <li>findLatestSucceededSatisfiedByOrgDeviceCatalogBefore: strict
 *       {@code < before} — audit row created AFTER {@code before} is
 *       NOT returned (deterministic selector cannot observe its own
 *       evaluation row).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointInstallAuditComplianceSelectorEffectiveOrgPostgresIntegrationTest {

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

    // ───────────────────────── existsSucceededSatisfied (AG-028 Phase 1b) ─────────────────────────

    @Test
    void exists_canonicalRow_returnsTrue() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.exists.canon");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-exists-canon");
        persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        boolean exists = repository
                .existsSucceededSatisfiedByOrgDeviceCatalog(orgA, deviceId, catalogId);

        assertThat(exists).isTrue();
    }

    @Test
    void exists_legacyNullOrgUpdate_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL OR-fallback provenance fixture is replaced
        // by its invariant proof — a trigger-bypass UPDATE org_id = NULL on a
        // persisted canonical row is rejected 23514. The OR-fallback branch
        // in existsSucceededSatisfiedByOrgDeviceCatalog is left intact
        // (provably dead until A5 removes it with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.exists.legacy");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-exists-legacy");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        // Terminal: the rejected UPDATE must be the last DB op in the method.
        assertMakeAuditOrgIdNullRejectedByV36(auditId);
    }

    @Test
    void exists_crossOrg_returnsFalse() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.exists.cross");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-exists-cross");
        persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        boolean exists = repository
                .existsSucceededSatisfiedByOrgDeviceCatalog(orgB, deviceId, catalogId);

        assertThat(exists).isFalse();
    }

    @Test
    void exists_failedSatisfied_returnsFalse() {
        // V12 result_status enum includes FAILED. A SUCCEEDED gate must
        // reject FAILED rows even when post_verification is SATISFIED.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.exists.failed");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-exists-failed");
        persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.FAILED, InstallPostVerification.SATISFIED);

        boolean exists = repository
                .existsSucceededSatisfiedByOrgDeviceCatalog(orgA, deviceId, catalogId);

        assertThat(exists).isFalse();
    }

    @Test
    void exists_succeededUnsatisfied_returnsFalse() {
        // Codex 019e8dde iter-1 REVISE #2 — guard the SECOND predicate
        // (post_verification = SATISFIED) independently of the first
        // (result_status = SUCCEEDED). A SUCCEEDED + UNSATISFIED row
        // must NOT pass the existence gate; AG-028 destructive uninstall
        // provenance requires BOTH predicates to hold.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.exists.unsatisfied");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-exists-unsatisfied");
        persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.UNSATISFIED);

        boolean exists = repository
                .existsSucceededSatisfiedByOrgDeviceCatalog(orgA, deviceId, catalogId);

        assertThat(exists).isFalse();
    }

    // ───────────────────────── findLatestSucceededSatisfied... (compliance evaluator selector) ─────────────────────────

    @Test
    void selector_canonicalRow_matchedViaWrapper() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.sel.canon");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-sel-canon");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        Optional<EndpointInstallAudit> hit = repository
                .findLatestSucceededSatisfiedByOrgDeviceCatalogBefore(
                        orgA, deviceId, catalogId,
                        Instant.parse("2999-12-31T23:59:59Z"));

        assertThat(hit).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId));
    }

    @Test
    void selector_legacyNullOrgUpdate_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL selector OR-fallback fixture is replaced by
        // its invariant proof — a trigger-bypass UPDATE org_id = NULL on a
        // persisted canonical row is rejected 23514. The OR-fallback branch in
        // findLatestSucceededSatisfiedByOrgDeviceCatalogBefore is left intact
        // (provably dead until A5 removes it with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.sel.legacy");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-sel-legacy");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        // Terminal: the rejected UPDATE must be the last DB op in the method.
        assertMakeAuditOrgIdNullRejectedByV36(auditId);
    }

    @Test
    void selector_crossOrg_returnsEmpty() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.sel.cross");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-sel-cross");
        persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        Optional<EndpointInstallAudit> hit = repository
                .findLatestSucceededSatisfiedByOrgDeviceCatalogBefore(
                        orgB, deviceId, catalogId,
                        Instant.parse("2999-12-31T23:59:59Z"));

        assertThat(hit).isEmpty();
    }

    @Test
    void selector_strictBeforeBound_excludesRowsCreatedAtOrAfterBefore() {
        // The deterministic-selector contract: created_at < before so the
        // compliance evaluator cannot observe its own evaluation row.
        // Seed an audit row, derive its created_at from the DB, pass
        // before = created_at exactly: row MUST NOT match.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.sel.strict");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-sel-strict");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED);

        // Codex 019e8dde iter-1 REVISE #1 — PostgreSQL JDBC 42.7.x does
        // not expose an Instant branch in getObject(idx, Class<?>); read
        // a Timestamp and convert. The toInstant() reflects the row's
        // committed timestamp regardless of session TZ.
        Timestamp createdAtTs = jdbc.queryForObject(
                "SELECT created_at FROM " + SCHEMA
                        + ".endpoint_install_audit WHERE id = ?",
                Timestamp.class, auditId);
        Instant createdAt = createdAtTs.toInstant();

        Optional<EndpointInstallAudit> hitExact = repository
                .findLatestSucceededSatisfiedByOrgDeviceCatalogBefore(
                        orgA, deviceId, catalogId, createdAt);
        Optional<EndpointInstallAudit> hitAfter = repository
                .findLatestSucceededSatisfiedByOrgDeviceCatalogBefore(
                        orgA, deviceId, catalogId, createdAt.plusSeconds(1));

        assertThat(hitExact)
                .as("strict < before: row whose created_at == before is NOT returned")
                .isEmpty();
        assertThat(hitAfter)
                .as("row whose created_at < before is returned")
                .isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId));
    }

    // ───────────────────────── Seed helpers (mirror e-A) ─────────────────────────

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

    private UUID persistAuditCanonical(UUID tenantId, UUID deviceId,
            UUID commandId, UUID catalogItemId,
            CommandResultStatus resultStatus,
            InstallPostVerification postVerification) {
        Instant now = Instant.now();
        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(tenantId);
        audit.setOrgId(tenantId);  // canonical write parity (PR2b-ii)
        audit.setDeviceId(deviceId);
        audit.setCommandId(commandId);
        audit.setCatalogItemId(catalogItemId);
        audit.setCatalogPackageId("pkg-" + catalogItemId);
        audit.setCatalogRowVersion(0L);
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(now);
        audit.setActorSubject("actor@example.com");
        audit.setResultStatus(resultStatus);
        audit.setReportedAt(now);
        audit.setPostVerification(postVerification);
        EndpointInstallAudit saved = repository.saveAndFlush(audit);
        return saved.getId();
    }

    /**
     * C1.5/V36 terminal-assertion helper: assert that forcing the legacy
     * NULL shape on an existing canonical audit row via direct
     * {@code UPDATE org_id = NULL} is REJECTED with SQLSTATE 23514.
     *
     * <p>DISABLE TRIGGER USER essential (slice e-A iter-1 Codex comment):
     * with the V29 BEFORE UPDATE trigger enabled, {@code UPDATE org_id = NULL}
     * would be silently refilled back to tenant_id, masking the V36 CHECK.
     * With the trigger disabled the explicit NULL reaches the V36 CHECK
     * {@code (org_id IS NOT NULL)} and is rejected.
     *
     * <p>Transaction-hygiene (Codex 019e92a7): the rejected UPDATE aborts the
     * surrounding tx, so this helper performs NO ENABLE-TRIGGER finally and
     * MUST be the last DB op of its caller — the @DataJpaTest per-method
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
