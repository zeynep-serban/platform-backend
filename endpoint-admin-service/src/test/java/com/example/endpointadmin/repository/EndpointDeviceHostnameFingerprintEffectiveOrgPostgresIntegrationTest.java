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
 * Faz 21.1 PR2b-iv.b2 regression guard — {@link EndpointDeviceRepository}
 * hostname / machineFingerprint adoption resolvers migrated from derived
 * {@code findByTenantIdAndHostname} / {@code findByTenantIdAndMachine
 * Fingerprint} to explicit {@code @Query} with the canonical effective-org
 * filter (Codex 019e8d1d B-B sub-slice b2 AGREE + P1 parenthesized OR).
 *
 * <p>The canonical predicates:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *     AND d.hostname = :hostname
 *
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *     AND d.machine_fingerprint = :machineFingerprint
 * </pre>
 *
 * <p>Five assertions on the b2 read-path correctness modes (Codex
 * 019e8d1d b2 PG IT minimums):
 * <ol>
 *   <li>Canonical fingerprint row read.</li>
 *   <li>Canonical hostname row read.</li>
 *   <li>Legacy NULL row REJECTED by V36 (C1.5) — CHECK org_id IS NOT NULL
 *       makes it unconstructable; a trigger-bypass org_id-NULL insert is
 *       rejected 23514. The OR-fallback read branch (both methods) stays,
 *       dead until A5.</li>
 *   <li>Cross-org negative — orgB's row never returned for orgA query,
 *       even with the same hostname/fingerprint.</li>
 *   <li>Adoption order preserved — fingerprint first wins over hostname
 *       fallback in the canonical adoption resolver flow.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceHostnameFingerprintEffectiveOrgPostgresIntegrationTest {

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

    // ───────────────────────── Assertion 1: canonical fingerprint ─────────────────────────

    @Test
    void canonicalFingerprint_bothColumnsEqual_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canon-host", "fp-canonical");

        Optional<EndpointDevice> hit = repository
                .findVisibleToOrgAndMachineFingerprint(orgA, "fp-canonical");
        assertThat(hit).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceId));
    }

    // ───────────────────────── Assertion 2: canonical hostname ─────────────────────────

    @Test
    void canonicalHostname_bothColumnsEqual_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canon-host", "fp-canonical");

        Optional<EndpointDevice> hit = repository
                .findVisibleToOrgAndHostname(orgA, "canon-host");
        assertThat(hit).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceId));
    }

    // ───────────────────────── Assertion 3: legacy NULL insert REJECTED by V36 ─────────────────────────

    @Test
    void legacyNullRow_orgIdNullInsert_isRejectedByV36() {
        // C1.5/V36 invariant flip: org_id NULL is now physically
        // unconstructable on the source tables (CHECK org_id IS NOT NULL).
        // The pre-V36 legacy-NULL visibility fixture (both fingerprint +
        // hostname OR-fallback reads) is replaced by its invariant proof —
        // a trigger-bypass org_id-NULL insert is rejected 23514. The
        // OR-fallback branches in findVisibleToOrgAndMachineFingerprint /
        // findVisibleToOrgAndHostname are left intact (provably dead until
        // A5 removes them with composite indexes).
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        assertDeviceLegacyNullOrgInsertRejectedByV36(
                deviceId, orgA, "legacy-host", "fp-legacy");
    }

    // ───────────────────────── Assertion 4: cross-org negative (same hostname/fingerprint) ─────────────────────────

    @Test
    void crossOrg_sameHostnameAndFingerprint_doesNotLeakOrgBsRow() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        // Deliberately same hostname + fingerprint across orgs (real life:
        // two corporate tenants enrolling the same hostname/laptop model).
        insertDeviceCanonical(deviceA, orgA, "shared-host", "fp-shared");
        insertDeviceCanonical(deviceB, orgB, "shared-host", "fp-shared");

        Optional<EndpointDevice> hitA = repository
                .findVisibleToOrgAndMachineFingerprint(orgA, "fp-shared");
        Optional<EndpointDevice> hitB = repository
                .findVisibleToOrgAndMachineFingerprint(orgB, "fp-shared");

        assertThat(hitA).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId())
                        .as("orgA must see its own device, not orgB's").isEqualTo(deviceA));
        assertThat(hitB).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId())
                        .as("orgB must see its own device, not orgA's").isEqualTo(deviceB));

        // Hostname mirror:
        Optional<EndpointDevice> hitAHost = repository
                .findVisibleToOrgAndHostname(orgA, "shared-host");
        Optional<EndpointDevice> hitBHost = repository
                .findVisibleToOrgAndHostname(orgB, "shared-host");
        assertThat(hitAHost).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceA));
        assertThat(hitBHost).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceB));
    }

    // ───────────────────────── Assertion 5: adoption order fingerprint-first ─────────────────────────

    @Test
    void adoptionOrder_fingerprintFirst_winsOverHostnameFallback() {
        UUID orgA = UUID.randomUUID();
        UUID deviceFp = UUID.randomUUID();
        UUID deviceHost = UUID.randomUUID();
        // Two distinct devices in the same org with disjoint hostname +
        // fingerprint pairs (the (tenant_id, hostname) UNIQUE constraint
        // forbids two rows with the same hostname). The adoption-resolver
        // flow chooses by fingerprint first; the test confirms the
        // hostname fallback is never reached when the fingerprint hits.
        insertDeviceCanonical(deviceFp, orgA, "host-fp", "fp-WINS");
        insertDeviceCanonical(deviceHost, orgA, "host-fallback", "fp-OTHER");

        // Simulate the production adoption order with a query mix that
        // would, if the fingerprint branch were skipped, fall through to
        // the hostname branch and return deviceHost. Fingerprint matches
        // deviceFp, hostname (when checked) matches deviceHost — but the
        // .or() lazy chain MUST short-circuit on the fingerprint hit.
        Optional<EndpointDevice> adopted = repository
                .findVisibleToOrgAndMachineFingerprint(orgA, "fp-WINS")
                .or(() -> repository.findVisibleToOrgAndHostname(orgA, "host-fallback"));

        assertThat(adopted).isPresent()
                .as("adoption resolver fingerprint-first MUST surface deviceFp; "
                        + "the .or() fallback would have returned deviceHost if "
                        + "fingerprint hadn't short-circuited the chain")
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceFp));
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDeviceCanonical(UUID id, UUID org, String hostname, String fingerprint) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, fingerprint, now, now);
    }

    private void assertDeviceLegacyNullOrgInsertRejectedByV36(
            UUID id, UUID tenant, String hostname, String fingerprint) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        // Disable the V29 compat trigger so org_id stays the explicit NULL we
        // pass (proving the V36 CHECK, not the trigger, rejects it). Terminal
        // assertion (Codex 019e92a7 transaction-hygiene): the failed INSERT
        // aborts the tx, so there is no ENABLE-TRIGGER finally and nothing runs
        // after — the @DataJpaTest rollback re-enables the trigger.
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, fingerprint, now, now))
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
