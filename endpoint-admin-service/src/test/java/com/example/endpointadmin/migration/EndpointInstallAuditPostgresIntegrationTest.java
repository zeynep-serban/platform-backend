package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-021 — PostgreSQL-only migration + tenant-integrity integration
 * tests for {@code V12__endpoint_install_audit.sql} (Faz 22.5).
 *
 * <p>Codex 019e6dfb iter-4 P2 follow-up: BE-021 PR #317 landed the V12
 * migration + the {@link EndpointInstallAudit} entity + the
 * deterministic-selector repository query. The H2 {@code @DataJpaTest}
 * slice cannot exercise the parts that depend on real Postgres
 * semantics:
 *
 * <ul>
 *   <li>Composite-FK enforcement on {@code (command_id, tenant_id)} +
 *       {@code (device_id, tenant_id)} + {@code (catalog_item_id, tenant_id)}
 *       — H2 silently accepts cross-tenant inserts;</li>
 *   <li>The {@code ck_endpoint_commands_type} CHECK extension that gates
 *       {@code INSTALL_SOFTWARE} acceptance at the DB layer;</li>
 *   <li>The partial index
 *       {@code idx_endpoint_install_audit_eval_selector} predicate
 *       ({@code WHERE result_status = 'SUCCEEDED' AND post_verification
 *       = 'SATISFIED'}) — H2 partial-index support is unreliable;</li>
 *   <li>JSONB round-trip for {@code preflight_warn_codes} (array),
 *       {@code post_verification_evidence} (object), and
 *       {@code redacted_payload} (object);</li>
 *   <li>The {@link
 *       EndpointInstallAuditRepository#findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
 *       UUID, UUID, UUID, Instant)} selector ordering against the same
 *       partial index the production query path uses.</li>
 * </ul>
 *
 * <p>Mirrors {@code EndpointComplianceStatePostgresIntegrationTest} +
 * {@code EndpointMachineCertsPostgresIntegrationTest} setup: PG 16
 * Testcontainer + Flyway enabled + {@code ddl-auto=validate} + {@code
 * public} schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointInstallAuditPostgresIntegrationTest {

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
    private EndpointInstallAuditRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    // ─── Flyway / schema validation ──────────────────────────────────

    @Test
    void flywayLiftsSchemaToV12AndHibernateValidatesAgainstIt() {
        // ddl-auto=validate context start is itself the assertion:
        // every column in EndpointInstallAudit must line up with V12 or
        // Spring refuses to bring the test context up.
        assertThat(auditRepository).isNotNull();
        assertThat(auditRepository.count()).isZero();
    }

    @Test
    void v12RegistersExpectedConstraintsAndIndexes() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_install_audit'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_install_audit_result_status",
                "ck_endpoint_install_audit_preflight_decision",
                "ck_endpoint_install_audit_post_verification",
                "ck_endpoint_install_audit_post_verification_shape",
                "ck_endpoint_install_audit_redacted_payload_shape",
                "ck_endpoint_install_audit_warn_codes_shape");

        List<String> uniques = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid IN ("
                        + "'public.endpoint_install_audit'::regclass,"
                        + "'public.endpoint_commands'::regclass,"
                        + "'public.endpoint_devices'::regclass) "
                        + "AND contype = 'u'",
                String.class);
        // V12 §2 composite-FK enablers — both must be present so the
        // composite FKs from endpoint_install_audit have something to
        // reference. V10 already added the catalog-side (id, tenant_id)
        // unique; we assert the install-audit UNIQUE(command_id) too.
        assertThat(uniques).contains(
                "uq_endpoint_commands_id_tenant",
                "uq_endpoint_devices_id_tenant",
                "uq_endpoint_install_audit_command");

        List<String> foreignKeys = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_install_audit'::regclass "
                        + "AND contype = 'f'",
                String.class);
        assertThat(foreignKeys).contains(
                "fk_endpoint_install_audit_command",
                "fk_endpoint_install_audit_device",
                "fk_endpoint_install_audit_catalog");

        // Every FK must be composite (2 columns), so a single-column
        // misrouting cannot reach the parent row. pg_constraint.conkey
        // is the array of local column ordinals.
        List<String> fkConKeys = jdbc.queryForList(
                "SELECT conkey::text FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_install_audit'::regclass "
                        + "AND contype = 'f'",
                String.class);
        assertThat(fkConKeys).hasSize(3);
        assertThat(fkConKeys).allMatch(k -> k.matches("\\{\\d+,\\d+\\}"));

        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = 'endpoint_install_audit'",
                String.class);
        assertThat(indexes).contains(
                "idx_endpoint_install_audit_tenant_device_time",
                "idx_endpoint_install_audit_tenant_catalog_time",
                "idx_endpoint_install_audit_tenant_status_time",
                "idx_endpoint_install_audit_eval_selector");
    }

    @Test
    void v12CommandTypeCheckIsDynamicallyDiscoveredAndExtended() {
        // V12 §1 drops the V2-baseline CHECK by definition lookup (so
        // both the inline `ck_endpoint_commands_type` and the auto-named
        // `endpoint_commands_command_type_check` flavour are handled —
        // see PR #318) and re-creates a single canonical constraint.
        List<String> commandTypeChecks = jdbc.queryForList(
                "SELECT pg_get_constraintdef(oid) "
                        + "FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_commands'::regclass "
                        + "AND contype = 'c' "
                        + "AND pg_get_constraintdef(oid) LIKE '%command_type%'",
                String.class);
        assertThat(commandTypeChecks).hasSize(1);
        assertThat(commandTypeChecks.get(0))
                .contains("INSTALL_SOFTWARE")
                .contains("COLLECT_INVENTORY");

        // The canonical constraint name V12 leaves in place is stable
        // across re-deploys and forward Flyway migrations.
        List<String> commandTypeNames = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_commands'::regclass "
                        + "AND contype = 'c' "
                        + "AND pg_get_constraintdef(oid) LIKE '%command_type%'",
                String.class);
        assertThat(commandTypeNames).containsExactly("ck_endpoint_commands_type");
    }

    @Test
    void evalSelectorPartialIndexCarriesExpectedPredicate() {
        // pg_get_expr(indpred, indrelid) returns the deparsed partial-
        // index WHERE predicate — stronger than substring-matching
        // pg_indexes.indexdef because the result is the normalized
        // boolean expression PG evaluates at query-plan time. Codex
        // iter-1 nitpick #1 absorb: the substring form would have
        // passed on a predicate that contains the same literals under
        // an OR/NOT, so we additionally pin the connective and forbid
        // OR/NOT in the predicate.
        String predicate = jdbc.queryForObject(
                "SELECT pg_get_expr(indpred, indrelid) "
                        + "FROM pg_catalog.pg_index "
                        + "WHERE indexrelid = "
                        + "'public.idx_endpoint_install_audit_eval_selector'::regclass",
                String.class);
        assertThat(predicate).isNotNull();
        // PG 16's pg_get_expr typically emits "(column)::text = 'literal'::text"
        // for VARCHAR equality predicates; tolerate either form (with or
        // without the column paren wrap) via an explicit optional-paren
        // group so the assertion is readable.
        assertThat(predicate)
                .containsPattern("\\(?result_status\\)?::text\\s*=\\s*'SUCCEEDED'::text")
                .containsPattern(
                        "\\(?post_verification\\)?::text\\s*=\\s*'SATISFIED'::text");
        assertThat(predicate.toUpperCase()).contains(" AND ");
        // The selector's correctness depends on the predicate being a
        // pure conjunction over the two equality terms. OR / NOT would
        // either widen the matched rows or invert the selector,
        // breaking the compliance evaluator's deterministic semantics.
        assertThat(predicate.toUpperCase())
                .doesNotContain(" OR ")
                .doesNotContain(" NOT ");

        // Sanity: indexdef also contains created_at as the ordering
        // column (this is the column the selector ORDER BY ... DESC
        // uses; not in the predicate, in the index expression).
        String indexdef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE indexname = 'idx_endpoint_install_audit_eval_selector'",
                String.class);
        assertThat(indexdef).contains("created_at");
    }

    // ─── command_type CHECK extension (INSTALL_SOFTWARE) ─────────────

    @Test
    void installSoftwareCommandTypeIsAcceptedAfterV12() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        // INSTALL_SOFTWARE was added by V12 §1 — pre-V12 baseline would
        // have rejected this insert with the original V2 CHECK.
        UUID commandId = seedCommand(tenantId, deviceId, "INSTALL_SOFTWARE",
                "idem-" + UUID.randomUUID());
        assertThat(commandId).isNotNull();

        Long inserted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_commands "
                        + "WHERE id = ? AND command_type = 'INSTALL_SOFTWARE'",
                Long.class, commandId);
        assertThat(inserted).isEqualTo(1L);
    }

    @Test
    void unknownCommandTypeIsRejectedByCheckConstraint() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = seedDevice(tenantId);
        // A value outside the V12-extended allowlist must trip the
        // CHECK constraint at the DB layer — the application controller
        // never reaches this point, but the contract is enforced even
        // if a bug routes through.
        assertThatThrownBy(() -> seedCommand(tenantId, deviceId, "INVALID_TYPE",
                "idem-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── Composite-FK enforcement (cross-tenant rejection) ───────────

    @Test
    void compositeFkRejectsCrossTenantCommandReference() {
        // @DataJpaTest aborts the transaction the moment a constraint
        // violation fires (PG SQL state 25P02), so the reject side and
        // the same-tenant happy path live in separate @Test methods.
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-cross-cmd-" + UUID.randomUUID());

        // tenant B tries to bind tenant A's commandId on its own audit
        // row. The composite FK (command_id, tenant_id) ->
        // endpoint_commands (id, tenant_id) must reject the insert.
        UUID deviceB = seedDevice(tenantB);
        UUID catalogB = seedCatalog(tenantB, "tenantB.app");

        assertThatThrownBy(() -> persistAudit(tenantB, deviceB, commandA, catalogB,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeFkRejectsCrossTenantDeviceReference() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        UUID deviceA = seedDevice(tenantA);
        UUID catalogB = seedCatalog(tenantB, "tenantB.app");
        UUID commandB = seedCommand(tenantB, seedDevice(tenantB),
                "INSTALL_SOFTWARE", "idem-cross-dev-" + UUID.randomUUID());

        // tenant B audit row pointing at tenant A's device must be
        // rejected by fk_endpoint_install_audit_device.
        assertThatThrownBy(() -> persistAudit(tenantB, deviceA, commandB, catalogB,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeFkRejectsCrossTenantCatalogReference() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID deviceB = seedDevice(tenantB);
        UUID commandB = seedCommand(tenantB, deviceB, "INSTALL_SOFTWARE",
                "idem-cross-cat-" + UUID.randomUUID());

        // tenant B audit row pointing at tenant A's catalog row must be
        // rejected by fk_endpoint_install_audit_catalog (the catalog
        // composite-FK pair was prepared by V10).
        assertThatThrownBy(() -> persistAudit(tenantB, deviceB, commandB, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTenantAuditIsAccepted() {
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-happy-" + UUID.randomUUID());

        EndpointInstallAudit persisted = persistAudit(tenantA, deviceA, commandA,
                catalogA, CommandResultStatus.SUCCEEDED,
                InstallPostVerification.SATISFIED, Instant.now());

        assertThat(persisted.getId()).isNotNull();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_install_audit "
                        + "WHERE id = ? AND tenant_id = ? AND device_id = ? "
                        + "AND command_id = ? AND catalog_item_id = ?",
                Long.class, persisted.getId(), tenantA, deviceA, commandA, catalogA);
        assertThat(count).isEqualTo(1L);
    }

    // ─── UNIQUE(command_id) ─────────────────────────────────────────

    @Test
    void uniqueCommandIdRejectsSecondAuditForSameCommand() {
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-uniq-" + UUID.randomUUID());

        persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now());

        // Mirrors endpoint_command_results' uniqueness — one terminal
        // result per command, even if a service bug attempts a second
        // append.
        assertThatThrownBy(() -> persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.FAILED, InstallPostVerification.UNSATISFIED,
                Instant.now().plusSeconds(60)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── JSONB round-trip ───────────────────────────────────────────

    @Test
    void jsonbColumnsRoundTripThroughHibernateAndPostgres() {
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-jsonb-" + UUID.randomUUID());

        EndpointInstallAudit audit = newAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now());
        audit.setPreflightWarnCodes(List.of("WARN_DRIVE_LOW", "WARN_REBOOT_PENDING"));
        audit.setPostVerificationEvidence(Map.of(
                "detection", Map.of(
                        "kind", "WINGET_PACKAGE",
                        "matched", Boolean.TRUE,
                        "packageId", "7zip.7zip",
                        "version", "24.07"),
                "evaluatedAt", "2026-05-28T10:00:00Z"));
        audit.setRedactedPayload(Map.of(
                "exitCode", 0,
                "stdout", "[redacted]",
                "stderr", ""));

        EndpointInstallAudit saved = auditRepository.saveAndFlush(audit);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        // Codex iter-1 critical finding absorb: repository.flush()
        // only writes pending changes — it does NOT evict the managed
        // instance from the persistence context, so a subsequent
        // findById would return the SAME Java object that was saved,
        // not a fresh re-materialization from PG. That would defeat
        // the JSONB round-trip assertion (we'd be checking the in-
        // memory map, never proving the Hibernate JdbcTypeCode.JSON
        // serializer can also DESERIALIZE PG's jsonb back). Explicit
        // flush+clear forces Hibernate to round-trip through PG.
        entityManager.flush();
        entityManager.clear();

        EndpointInstallAudit reloaded = auditRepository.findById(id).orElseThrow();
        assertThat(System.identityHashCode(reloaded))
                .as("after clear(), reloaded must be a fresh instance, not "
                        + "the original managed entity")
                .isNotEqualTo(System.identityHashCode(saved));
        assertThat(reloaded.getPreflightWarnCodes())
                .containsExactly("WARN_DRIVE_LOW", "WARN_REBOOT_PENDING");
        assertThat(reloaded.getPostVerificationEvidence())
                .containsEntry("evaluatedAt", "2026-05-28T10:00:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> detection = (Map<String, Object>)
                reloaded.getPostVerificationEvidence().get("detection");
        assertThat(detection)
                .containsEntry("packageId", "7zip.7zip")
                .containsEntry("version", "24.07")
                .containsEntry("matched", Boolean.TRUE);
        assertThat(reloaded.getRedactedPayload())
                .containsEntry("stdout", "[redacted]");

        // jsonb_typeof shape CHECKs would have refused the insert if
        // any of the three JSONB columns landed as a non-matching type,
        // so reaching the SELECT also proves the type CHECKs are
        // satisfied by what Hibernate JdbcTypeCode.JSON emits.
        String warnShape = jdbc.queryForObject(
                "SELECT jsonb_typeof(preflight_warn_codes) "
                        + "FROM endpoint_install_audit WHERE id = ?",
                String.class, id);
        assertThat(warnShape).isEqualTo("array");
        String evidenceShape = jdbc.queryForObject(
                "SELECT jsonb_typeof(post_verification_evidence) "
                        + "FROM endpoint_install_audit WHERE id = ?",
                String.class, id);
        assertThat(evidenceShape).isEqualTo("object");
        String redactedShape = jdbc.queryForObject(
                "SELECT jsonb_typeof(redacted_payload) "
                        + "FROM endpoint_install_audit WHERE id = ?",
                String.class, id);
        assertThat(redactedShape).isEqualTo("object");

        // Codex iter-1 critical finding absorb (depth): also navigate
        // the JSONB on the PG side so we prove the field landed in the
        // expected nested key path, not just that it round-trips at
        // the top level.
        String detectedPackageId = jdbc.queryForObject(
                "SELECT post_verification_evidence->'detection'->>'packageId' "
                        + "FROM endpoint_install_audit WHERE id = ?",
                String.class, id);
        assertThat(detectedPackageId).isEqualTo("7zip.7zip");

        String redactedStdout = jdbc.queryForObject(
                "SELECT redacted_payload->>'stdout' "
                        + "FROM endpoint_install_audit WHERE id = ?",
                String.class, id);
        assertThat(redactedStdout).isEqualTo("[redacted]");

        Integer warnCodeCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(preflight_warn_codes) "
                        + "FROM endpoint_install_audit WHERE id = ?",
                Integer.class, id);
        assertThat(warnCodeCount).isEqualTo(2);
    }

    @Test
    void warnCodesShapeCheckRejectsNonArrayJson() {
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-warn-shape-" + UUID.randomUUID());

        Timestamp now = Timestamp.from(Instant.now());
        // Bypass JPA to force a JSONB object into the warn-codes array
        // column. ck_endpoint_install_audit_warn_codes_shape must
        // reject the insert.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_install_audit ("
                        + "id, tenant_id, device_id, command_id, catalog_item_id, "
                        + "catalog_package_id, catalog_row_version, "
                        + "preflight_decision, preflight_decision_at, "
                        + "preflight_warn_codes, actor_subject, result_status, "
                        + "reported_at, post_verification, "
                        + "post_verification_evidence, redacted_payload, "
                        + "row_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 'PASS', ?, "
                        + "'{}'::jsonb, ?, 'SUCCEEDED', ?, 'SATISFIED', "
                        + "'{}'::jsonb, '{}'::jsonb, 0)",
                UUID.randomUUID(), tenantA, deviceA, commandA, catalogA,
                "tenantA.app", now, "actor@example.com", now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── Deterministic-selector repo query (compliance evaluator) ────

    @Test
    void selectorReturnsLatestSucceededSatisfiedBeforeCutoff() {
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");

        Instant base = Instant.parse("2026-05-28T10:00:00Z");

        // t1 (oldest qualifying): SUCCEEDED + SATISFIED
        UUID c1 = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-sel-1-" + UUID.randomUUID());
        UUID a1 = persistAudit(tenantA, deviceA, c1, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                base.minusSeconds(3 * 3600)).getId();

        // t2: SUCCEEDED but UNSATISFIED — must NOT be picked.
        UUID c2 = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-sel-2-" + UUID.randomUUID());
        persistAudit(tenantA, deviceA, c2, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.UNSATISFIED,
                base.minusSeconds(2 * 3600));

        // t3: FAILED + SATISFIED — must NOT be picked (only SUCCEEDED
        // executor status qualifies).
        UUID c3 = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-sel-3-" + UUID.randomUUID());
        persistAudit(tenantA, deviceA, c3, catalogA,
                CommandResultStatus.FAILED, InstallPostVerification.SATISFIED,
                base.minusSeconds(90 * 60));

        // t4 (newest qualifying): SUCCEEDED + SATISFIED — should be the
        // selector's winner when the cutoff is well past t4.
        UUID c4 = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-sel-4-" + UUID.randomUUID());
        UUID a4 = persistAudit(tenantA, deviceA, c4, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                base.minusSeconds(3600)).getId();

        Optional<EndpointInstallAudit> winner = auditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        tenantA, deviceA, catalogA, base);
        assertThat(winner).isPresent();
        assertThat(winner.get().getId()).isEqualTo(a4);

        // Strict `<` semantics: a cutoff equal to t4 must skip t4 and
        // pick t1 (the prior qualifying row).
        Optional<EndpointInstallAudit> priorWinner = auditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        tenantA, deviceA, catalogA, base.minusSeconds(3600));
        assertThat(priorWinner).isPresent();
        assertThat(priorWinner.get().getId()).isEqualTo(a1);

        // Cutoff before any qualifying audit returns empty.
        Optional<EndpointInstallAudit> noneYet = auditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        tenantA, deviceA, catalogA, base.minusSeconds(4 * 3600));
        assertThat(noneYet).isEmpty();
    }

    @Test
    void selectorIsTenantScoped() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-tenant-scope-A-" + UUID.randomUUID());
        persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now().minusSeconds(60));

        // tenant B sees nothing for tenant A's (device, catalog).
        Optional<EndpointInstallAudit> none = auditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        tenantB, deviceA, catalogA, Instant.now());
        assertThat(none).isEmpty();
    }

    // ─── ON DELETE behavior on composite FKs ─────────────────────────

    @Test
    void deletingCommandCascadesAuditRow() {
        // V12 §3 fk_endpoint_install_audit_command is declared
        // ON DELETE CASCADE — wiping the command must also wipe the
        // audit row. Codex iter-1 nitpick #2 absorb: lock the cascade
        // semantics, not just constraint existence.
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-cascade-cmd-" + UUID.randomUUID());

        UUID auditId = persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now()).getId();

        // Pre-condition: audit row is there.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_install_audit WHERE id = ?",
                Long.class, auditId)).isEqualTo(1L);

        // Detach the entity so Hibernate doesn't try to flush stale
        // state on top of the JDBC-level cascade delete.
        entityManager.clear();

        int deleted = jdbc.update(
                "DELETE FROM endpoint_commands WHERE id = ?", commandA);
        assertThat(deleted).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_install_audit WHERE id = ?",
                Long.class, auditId)).isEqualTo(0L);
    }

    @Test
    void deletingDeviceCascadesAuditRow() {
        // V12 §3 fk_endpoint_install_audit_device is declared
        // ON DELETE CASCADE. Mirror the command-cascade test so we
        // lock both CASCADE FKs symmetrically. Codex iter-2 nitpick
        // absorb: device-side cascade was the only un-pinned ON
        // DELETE behaviour after iter-1.
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-cascade-dev-" + UUID.randomUUID());

        UUID auditId = persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now()).getId();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_install_audit WHERE id = ?",
                Long.class, auditId)).isEqualTo(1L);

        entityManager.clear();

        // Deleting the device cascades through both endpoint_commands
        // (V2 FK ON DELETE CASCADE) and endpoint_install_audit (V12 FK
        // ON DELETE CASCADE) — the audit row must disappear.
        int deletedDev = jdbc.update(
                "DELETE FROM endpoint_devices WHERE id = ?", deviceA);
        assertThat(deletedDev).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_install_audit WHERE id = ?",
                Long.class, auditId)).isEqualTo(0L);
    }

    @Test
    void deletingCatalogIsRestrictedWhileAuditReferencesIt() {
        // V12 §3 fk_endpoint_install_audit_catalog is declared
        // ON DELETE RESTRICT — the catalog row cannot be hard-deleted
        // while an audit row points at it (the catalog has a separate
        // status='REVOKED' soft-delete path; this constraint guards
        // against accidental hard DELETEs from ops scripts).
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.restricted-app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-restrict-cat-" + UUID.randomUUID());

        persistAudit(tenantA, deviceA, commandA, catalogA,
                CommandResultStatus.SUCCEEDED, InstallPostVerification.SATISFIED,
                Instant.now());

        entityManager.clear();

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM endpoint_software_catalog_items "
                        + "WHERE id = ? AND tenant_id = ?", catalogA, tenantA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── Direct JDBC negative tests for the remaining shape CHECKs ───

    @Test
    void postVerificationEvidenceShapeCheckRejectsNonObjectJson() {
        // ck_endpoint_install_audit_post_verification_shape requires a
        // JSON object. Force an array via JDBC so JPA's Map serializer
        // cannot mask the violation.
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-evidence-shape-" + UUID.randomUUID());

        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_install_audit ("
                        + "id, tenant_id, device_id, command_id, catalog_item_id, "
                        + "catalog_package_id, catalog_row_version, "
                        + "preflight_decision, preflight_decision_at, "
                        + "preflight_warn_codes, actor_subject, result_status, "
                        + "reported_at, post_verification, "
                        + "post_verification_evidence, redacted_payload, "
                        + "row_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 'PASS', ?, "
                        + "'[]'::jsonb, ?, 'SUCCEEDED', ?, 'SATISFIED', "
                        + "'[]'::jsonb, '{}'::jsonb, 0)",
                UUID.randomUUID(), tenantA, deviceA, commandA, catalogA,
                "tenantA.app", now, "actor@example.com", now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void redactedPayloadShapeCheckRejectsNonObjectJson() {
        // ck_endpoint_install_audit_redacted_payload_shape requires a
        // JSON object. Mirror the evidence-shape test but flip the
        // bad column to redacted_payload.
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-payload-shape-" + UUID.randomUUID());

        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_install_audit ("
                        + "id, tenant_id, device_id, command_id, catalog_item_id, "
                        + "catalog_package_id, catalog_row_version, "
                        + "preflight_decision, preflight_decision_at, "
                        + "preflight_warn_codes, actor_subject, result_status, "
                        + "reported_at, post_verification, "
                        + "post_verification_evidence, redacted_payload, "
                        + "row_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 'PASS', ?, "
                        + "'[]'::jsonb, ?, 'SUCCEEDED', ?, 'SATISFIED', "
                        + "'{}'::jsonb, '\"redacted\"'::jsonb, 0)",
                UUID.randomUUID(), tenantA, deviceA, commandA, catalogA,
                "tenantA.app", now, "actor@example.com", now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preflightDecisionCheckRejectsUnknownEnumLiteral() {
        // ck_endpoint_install_audit_preflight_decision allows PASS or
        // WARN only. BLOCK never reaches command creation (the install
        // controller returns 409), but the DB-layer CHECK still has to
        // reject any stray literal a service bug might attempt.
        UUID tenantA = UUID.randomUUID();
        UUID deviceA = seedDevice(tenantA);
        UUID catalogA = seedCatalog(tenantA, "tenantA.app");
        UUID commandA = seedCommand(tenantA, deviceA, "INSTALL_SOFTWARE",
                "idem-preflight-bad-" + UUID.randomUUID());

        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_install_audit ("
                        + "id, tenant_id, device_id, command_id, catalog_item_id, "
                        + "catalog_package_id, catalog_row_version, "
                        + "preflight_decision, preflight_decision_at, "
                        + "preflight_warn_codes, actor_subject, result_status, "
                        + "reported_at, post_verification, "
                        + "post_verification_evidence, redacted_payload, "
                        + "row_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 'BLOCK', ?, "
                        + "'[]'::jsonb, ?, 'SUCCEEDED', ?, 'SATISFIED', "
                        + "'{}'::jsonb, '{}'::jsonb, 0)",
                UUID.randomUUID(), tenantA, deviceA, commandA, catalogA,
                "tenantA.app", now, "actor@example.com", now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private UUID seedDevice(UUID tenantId) {
        UUID id = UUID.randomUUID();
        // PG JDBC driver can't infer SQL type for java.time.Instant via
        // JdbcTemplate.setObject — convert to java.sql.Timestamp.
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO endpoint_devices "
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
        // Mirrors the helper in EndpointComplianceStatePostgresIntegrationTest
        // — APPROVED + WINGET + LATEST so V7/V10 CHECK constraints all pass.
        jdbc.update(
                "INSERT INTO endpoint_software_catalog_items ("
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
        // Minimal command row — defaults cover status/payload/priority/
        // attempt_count/max_attempts/visible_after_at/approval_status.
        jdbc.update(
                "INSERT INTO endpoint_commands ("
                        + "id, tenant_id, device_id, command_type, idempotency_key, "
                        + " issued_by_subject, issued_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, deviceId, commandType, idempotencyKey,
                "issuer@example.com", now, now, now, 0L);
        return id;
    }

    private EndpointInstallAudit newAudit(UUID tenantId, UUID deviceId,
                                          UUID commandId, UUID catalogItemId,
                                          CommandResultStatus resultStatus,
                                          InstallPostVerification postVerification,
                                          Instant createdAt) {
        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(tenantId);
        audit.setDeviceId(deviceId);
        audit.setCommandId(commandId);
        audit.setCatalogItemId(catalogItemId);
        audit.setCatalogPackageId("pkg-" + catalogItemId);
        audit.setCatalogRowVersion(0L);
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(createdAt);
        audit.setActorSubject("actor@example.com");
        audit.setResultStatus(resultStatus);
        audit.setReportedAt(createdAt);
        audit.setPostVerification(postVerification);
        // The selector orders by created_at; @PrePersist only sets it if
        // null, so explicit assignment here is preserved on insert and
        // gives deterministic ordering across the test fixtures.
        try {
            var field = EndpointInstallAudit.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(audit, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "EndpointInstallAudit.createdAt field shape changed; "
                            + "selector ordering test cannot pin createdAt.", e);
        }
        return audit;
    }

    private EndpointInstallAudit persistAudit(UUID tenantId, UUID deviceId,
                                              UUID commandId, UUID catalogItemId,
                                              CommandResultStatus resultStatus,
                                              InstallPostVerification postVerification,
                                              Instant createdAt) {
        return auditRepository.saveAndFlush(newAudit(tenantId, deviceId, commandId,
                catalogItemId, resultStatus, postVerification, createdAt));
    }
}
