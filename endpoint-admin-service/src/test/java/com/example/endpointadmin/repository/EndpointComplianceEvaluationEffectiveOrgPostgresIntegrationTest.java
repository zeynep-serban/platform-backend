package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.a regression guard — {@link EndpointComplianceEvaluationRepository}
 * read methods migrated from derived {@code findByTenantIdAnd…} to
 * explicit {@code @Query} with the canonical effective-org filter
 * (Codex 019e8d12 D′ eligibility-gated hybrid + P1 parenthesized OR
 * pattern).
 *
 * <p>The canonical predicate accepts:
 * <pre>
 *   WHERE (e.org_id = :orgId OR (e.org_id IS NULL AND e.tenant_id = :orgId))
 *     AND e.device_id = :deviceId
 * </pre>
 *
 * <p>Six behavioural assertions:
 * <ol>
 *   <li>Canonical row read — both columns equal, repository returns it
 *       via both methods (Page + Optional latest).</li>
 *   <li>Legacy NULL row read — V29 trigger temporarily disabled to seed
 *       {@code org_id IS NULL AND tenant_id = orgA}; both methods return
 *       it via the fallback OR branch.</li>
 *   <li>Legacy NULL fixture pre-assert — the seeded row truly has
 *       {@code org_id IS NULL} (trigger bypass held).</li>
 *   <li>Cross-org negative — orgA filter returns zero orgB rows.</li>
 *   <li>Latest ordering — {@code findFirst…} returns the latest
 *       evaluatedAt row when multiple exist for the same (org, device).</li>
 *   <li>Pagination — {@code findVisibleToOrgAndDeviceIdOrderByEvaluated
 *       AtDesc} respects {@code Pageable} (page-size 1 → 1 row, total 2).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointComplianceEvaluationEffectiveOrgPostgresIntegrationTest {

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
    private EndpointComplianceEvaluationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    // ───────────────────────── Assertion 1: canonical row read ─────────────────────────

    @Test
    void canonicalRow_bothColumnsEqual_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        seedDevice(device, orgA, "canonical-device");
        UUID evalId = seedEvaluationCanonical(device, orgA,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);

        Optional<EndpointComplianceEvaluation> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device);
        Page<EndpointComplianceEvaluation> page = repository
                .findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device, PageRequest.of(0, 10));

        assertThat(latest).isPresent()
                .as("canonical row reachable via findFirst…")
                .hasValueSatisfying(e -> assertThat(e.getId()).isEqualTo(evalId));
        assertThat(page.getContent())
                .as("canonical row reachable via Page…")
                .extracting(EndpointComplianceEvaluation::getId)
                .containsExactly(evalId);
    }

    // ───────────────────────── Assertions 2 + 3: legacy NULL fixture ─────────────────────────

    @Test
    void legacyNullRow_orgIdNull_isReturnedViaTenantIdFallback_andPreservedAsNullByFixture() {
        UUID orgA = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        seedDevice(device, orgA, "legacy-device");
        UUID legacyEvalId = seedEvaluationLegacyNullOrg(device, orgA,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);

        // Pre-assert: the seed actually persisted with org_id NULL.
        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA + ".endpoint_compliance_evaluations WHERE id = ?",
                Boolean.class, legacyEvalId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: row must have org_id IS NULL "
                        + "(V29 trigger bypassed; otherwise the fallback OR branch "
                        + "is not actually being exercised by the rest of the test)")
                .isTrue();

        Optional<EndpointComplianceEvaluation> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device);
        Page<EndpointComplianceEvaluation> page = repository
                .findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device, PageRequest.of(0, 10));

        assertThat(latest).isPresent()
                .hasValueSatisfying(e -> assertThat(e.getId()).isEqualTo(legacyEvalId));
        assertThat(page.getContent())
                .as("legacy NULL row reachable via OR fallback branch")
                .extracting(EndpointComplianceEvaluation::getId)
                .containsExactly(legacyEvalId);
    }

    // ───────────────────────── Assertion 4: cross-org negative ─────────────────────────

    @Test
    void crossOrg_orgAFilter_doesNotReturnOrgBRows() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        seedDevice(deviceA, orgA, "orga-device");
        seedDevice(deviceB, orgB, "orgb-device");
        UUID evalA = seedEvaluationCanonical(deviceA, orgA,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);
        seedEvaluationCanonical(deviceB, orgB,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);

        // orgA filter for deviceA returns evalA.
        Optional<EndpointComplianceEvaluation> hit = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, deviceA);
        assertThat(hit).isPresent()
                .hasValueSatisfying(e -> assertThat(e.getId()).isEqualTo(evalA));

        // orgA filter for deviceB returns nothing (cross-org boundary).
        Optional<EndpointComplianceEvaluation> miss = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, deviceB);
        assertThat(miss)
                .as("orgA filter MUST NOT return orgB rows even via the OR fallback "
                        + "(tenant_id boundary still enforced by the AND deviceId predicate)")
                .isEmpty();
    }

    // ───────────────────────── Assertion 5: latest ordering ─────────────────────────

    @Test
    void findFirst_returnsLatestEvaluatedAt_amongMultiple() {
        UUID orgA = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        seedDevice(device, orgA, "multi-eval-device");
        seedEvaluationCanonical(device, orgA,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);
        UUID newer = seedEvaluationCanonical(device, orgA,
                Instant.parse("2026-06-03T11:00:00Z"), ComplianceDecision.NON_COMPLIANT);

        Optional<EndpointComplianceEvaluation> latest = repository
                .findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device);
        assertThat(latest).isPresent()
                .hasValueSatisfying(e -> assertThat(e.getId()).isEqualTo(newer));
    }

    // ───────────────────────── Assertion 6: pagination ─────────────────────────

    @Test
    void findVisibleToOrg_paginated_respectsPageSize() {
        UUID orgA = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        seedDevice(device, orgA, "paginated-device");
        seedEvaluationCanonical(device, orgA,
                Instant.parse("2026-06-03T10:00:00Z"), ComplianceDecision.COMPLIANT);
        seedEvaluationCanonical(device, orgA,
                Instant.parse("2026-06-03T11:00:00Z"), ComplianceDecision.NON_COMPLIANT);

        Page<EndpointComplianceEvaluation> page = repository
                .findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(orgA, device, PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements())
                .as("count via @Query — both seed rows are visible")
                .isEqualTo(2L);
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void seedDevice(UUID id, UUID org, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T09:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, now, now);
    }

    private UUID seedEvaluationCanonical(UUID deviceId, UUID org, Instant evaluatedAt,
                                         ComplianceDecision decision) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T09:00:00Z"));
        Timestamp ts = Timestamp.from(evaluatedAt);
        // Real schema (V18 endpoint_compliance_evaluations):
        //   id PK, tenant_id NOT NULL, device_id NOT NULL, evaluated_at NOT NULL,
        //   decision VARCHAR(16) NOT NULL, reasons + blocking_reasons + warnings
        //   + evidence JSONB NOT NULL, catalog_policy_hash VARCHAR(64) NOT NULL,
        //   inventory_snapshot_id UUID nullable, *_row_version BIGINT nullable,
        //   created_at NOT NULL, [PR2b-i V29 adds org_id UUID nullable].
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_compliance_evaluations "
                        + "(id, tenant_id, org_id, device_id, evaluated_at, decision, "
                        + " reasons, blocking_reasons, warnings, evidence, "
                        + " catalog_policy_hash, inventory_snapshot_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, "
                        + " '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, "
                        + " ?, NULL, ?)",
                id, org, org, deviceId, ts, decision.name(),
                "a".repeat(64), now);
        return id;
    }

    private UUID seedEvaluationLegacyNullOrg(UUID deviceId, UUID org, Instant evaluatedAt,
                                             ComplianceDecision decision) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T09:00:00Z"));
        Timestamp ts = Timestamp.from(evaluatedAt);
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_compliance_evaluations DISABLE TRIGGER USER");
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_compliance_evaluations "
                            + "(id, tenant_id, org_id, device_id, evaluated_at, decision, "
                            + " reasons, blocking_reasons, warnings, evidence, "
                            + " catalog_policy_hash, inventory_snapshot_id, created_at) "
                            + "VALUES (?, ?, NULL, ?, ?, ?, "
                            + " '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, "
                            + " ?, NULL, ?)",
                    id, org, deviceId, ts, decision.name(),
                    "b".repeat(64), now);
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_compliance_evaluations ENABLE TRIGGER USER");
        }
        return id;
    }
}
