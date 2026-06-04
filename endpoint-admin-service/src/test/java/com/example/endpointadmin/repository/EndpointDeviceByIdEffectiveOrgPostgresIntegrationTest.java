package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.model.EndpointDevice;
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
 * Faz 21.1 PR2b-iv.b1 regression guard —
 * {@link EndpointDeviceRepository#findVisibleToOrgAndId} canonical
 * effective-org device-by-id ownership gate (Codex 019e8d1d B-B
 * sub-slice AGREE + P1 parenthesized OR pattern).
 *
 * <p>The canonical predicate:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *     AND d.id = :id
 * </pre>
 *
 * <p>Three assertions on the b1 read-path correctness modes (Codex
 * 019e8d1d b1 PG IT minimums):
 * <ol>
 *   <li>Canonical row read — both columns equal, repository returns
 *       it via {@code findVisibleToOrgAndId}.</li>
 *   <li>Legacy NULL row REJECTED by V36 (C1.5) — the source CHECK
 *       {@code (org_id IS NOT NULL)} makes the org_id-NULL row physically
 *       unconstructable; a trigger-bypass insert is rejected 23514. The
 *       OR-fallback read branch stays (provably dead until A5 removes it
 *       with its composite org_id mirror indexes).</li>
 *   <li>Cross-org negative — orgA lookup for orgB's device returns
 *       empty (no existence leak, ownership gate preserved).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceByIdEffectiveOrgPostgresIntegrationTest {

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
    private EndpointDeviceRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void canonicalRow_bothColumnsEqual_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canonical-device");

        Optional<EndpointDevice> hit = repository.findVisibleToOrgAndId(orgA, deviceId);
        assertThat(hit).isPresent()
                .hasValueSatisfying(d -> {
                    assertThat(d.getId()).isEqualTo(deviceId);
                    assertThat(d.getHostname()).isEqualTo("canonical-device");
                });
    }

    @Test
    void legacyNullRow_orgIdNullInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL visibility fixture is replaced by its
        // invariant proof — a trigger-bypass org_id-NULL insert is rejected
        // 23514. The OR-fallback branch in findVisibleToOrgAndId is left
        // intact (provably dead until A5 removes it with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        assertDeviceLegacyNullOrgInsertRejectedByV36(deviceId, orgA, "legacy-null-device");
    }

    @Test
    void crossOrg_orgAFilter_doesNotReturnOrgBsDevice() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "orga-device");
        insertDeviceCanonical(deviceB, orgB, "orgb-device");

        // orgA's lookup of its own device → present.
        Optional<EndpointDevice> hit = repository.findVisibleToOrgAndId(orgA, deviceA);
        assertThat(hit).isPresent();

        // orgA's lookup of orgB's device → empty (ownership gate enforced;
        // no existence leak — same empty Optional whether the device
        // doesn't exist at all or exists under another org).
        Optional<EndpointDevice> miss = repository.findVisibleToOrgAndId(orgA, deviceB);
        assertThat(miss)
                .as("orgA MUST NOT see orgB's device via the OR fallback "
                        + "(the parenthesized effective-org predicate keeps the "
                        + "tenant boundary; orgA's filter never matches orgB's "
                        + "org_id nor orgB's tenant_id)")
                .isEmpty();
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDeviceCanonical(UUID id, UUID org, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, now, now);
    }

    private void assertDeviceLegacyNullOrgInsertRejectedByV36(UUID id, UUID tenant, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        // Disable the V29 compat trigger so org_id stays the explicit NULL we
        // pass (proving the V36 CHECK, not the trigger, rejects it). Terminal
        // assertion (Codex 019e92a7 transaction-hygiene): the failed INSERT
        // aborts the tx, so there is no ENABLE-TRIGGER finally and nothing runs
        // after — the @DataJpaTest rollback re-enables the trigger.
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now))
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
