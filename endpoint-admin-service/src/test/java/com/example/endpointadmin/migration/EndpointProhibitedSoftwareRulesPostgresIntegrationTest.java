package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointProhibitedSoftwareRuleRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-025 — PostgreSQL-only migration + constraint integration tests for
 * {@code V19__endpoint_prohibited_software_rules.sql} (Faz 22.5). Mirrors
 * the {@code EndpointSoftwareInventoryStateHistoryPostgresIntegrationTest}
 * (V18) setup.
 *
 * <p>The H2 {@code @DataJpaTest} slice cannot exercise the Postgres-only
 * pieces:
 * <ul>
 *   <li>the {@code match_type ↔ pattern} consistency CHECK;</li>
 *   <li>the blank-pattern and CONTAINS-min-length CHECKs;</li>
 *   <li>the functional UNIQUE dedup index (with NULL-folding via
 *       {@code COALESCE(lower(btrim(...)), '')}).</li>
 * </ul>
 *
 * <p>PG 16 Testcontainer + Flyway enabled + {@code ddl-auto=validate} so the
 * {@code EndpointProhibitedSoftwareRule} entity must line up with V19 or the
 * context refuses to start. Runs in CI only — the local
 * {@code -Dtest='!*PostgresIntegrationTest'} filter skips it.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointProhibitedSoftwareRulesPostgresIntegrationTest {

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

    private static final String TABLE = "endpoint_prohibited_software_rules";

    @Autowired
    private EndpointProhibitedSoftwareRuleRepository ruleRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ──────────────────────────────────────────────────────────────────
    // Flyway / schema validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void flywayLiftsSchemaAndHibernateValidatesAgainstIt() {
        assertThat(ruleRepository).isNotNull();
        assertThat(ruleRepository.count()).isZero();
    }

    @Test
    void v19RegistersExpectedConstraintsAndIndexes() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public." + TABLE + "'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_prohibited_software_rules_match_mode",
                "ck_endpoint_prohibited_software_rules_match_consistency",
                "ck_endpoint_prohibited_software_rules_name_pattern_blank",
                "ck_endpoint_prohibited_software_rules_pub_pattern_blank",
                "ck_endpoint_prohibited_software_rules_contains_minlen");

        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + TABLE + "'",
                String.class);
        assertThat(indexes).contains(
                "idx_endpoint_prohibited_software_rules_tenant_enabled",
                "uq_endpoint_prohibited_software_rules_tenant_dedup");
    }

    // ──────────────────────────────────────────────────────────────────
    // CHECK constraint violations
    // ──────────────────────────────────────────────────────────────────

    @Test
    void nameTypeWithPublisherRejectedByConsistencyCheck() {
        assertThatThrownBy(() -> insert(
                "NAME", "EXACT", "uTorrent", "Vendor"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_match_consistency");
    }

    @Test
    void publisherTypeWithoutPublisherRejectedByConsistencyCheck() {
        assertThatThrownBy(() -> insert(
                "PUBLISHER", "EXACT", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_match_consistency");
    }

    @Test
    void blankNamePatternRejectedByCheck() {
        assertThatThrownBy(() -> insert(
                "NAME", "EXACT", "   ", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_name_pattern_blank");
    }

    @Test
    void containsBelowMinLengthRejectedByCheck() {
        // "ab" trimmed length 2 < 3 under CONTAINS.
        assertThatThrownBy(() -> insert(
                "NAME", "CONTAINS", "ab", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_contains_minlen");
    }

    @Test
    void invalidMatchModeRejectedByCheck() {
        assertThatThrownBy(() -> insert(
                "NAME", "REGEX", "uTorrent", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_match_mode");
    }

    @Test
    void exactShortNameAccepted() {
        // EXACT is exempt from the CONTAINS min length.
        UUID id = insert("NAME", "EXACT", "qq", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Normalization parity with Java String.trim() (Codex 019e763a REVISE
    // #2). The CHECK / dedup expressions now trim over the [\x00-\x20] range
    // (same as Java String.trim()), NOT the bare btrim() that only stripped
    // U+0020 spaces. These tests lock the previously-divergent tab/newline
    // behaviour.
    // ──────────────────────────────────────────────────────────────────

    @Test
    void containsWithTrailingTabBelowMinLengthRejectedByCheck() {
        // "ab\t" — trimmed length 2 < 3 under the [\x00-\x20] range. The OLD
        // bare btrim() kept the tab (length 3) and would have WRONGLY accepted
        // this, diverging from the app validator. Now the DB rejects it too.
        assertThatThrownBy(() -> insert("NAME", "CONTAINS", "ab\t", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_contains_minlen");
    }

    @Test
    void tabAndNewlineOnlyNamePatternRejectedAsBlankByCheck() {
        // A pattern of only tab/newline trims to '' under [\x00-\x20] → blank
        // CHECK fires (the bare btrim() would have left it non-empty).
        assertThatThrownBy(() -> insert("NAME", "EXACT", "\t\n", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_prohibited_software_rules_name_pattern_blank");
    }

    @Test
    void delCharInPatternIsKeptNotTrimmed() {
        // Java String.trim() KEEPS DEL (U+007F > U+0020). The DB range
        // [\x00-\x20] also keeps it, so a trailing-DEL EXACT pattern is
        // stored verbatim (length unchanged). Proves the range is an EXACT
        // match to Java trim() and does NOT over-strip like [[:cntrl:]] would
        // (which strips DEL). EXACT mode so the CONTAINS min-length is moot.
        String withDel = "ab\u007f"; // 3 chars, trailing DEL
        UUID id = insert("NAME", "EXACT", withDel, null);
        assertThat(jdbc.queryForObject(
                "SELECT length(name_pattern) FROM " + TABLE + " WHERE id = ?",
                Integer.class, id)).isEqualTo(3);
    }

    @Test
    void dedupFoldsTabPaddedVariantCaseInsensitively() {
        // Same logical rule, one space-padded + lowercased, one tab-padded +
        // uppercased → both fold to the same [\x00-\x20]-trimmed lower key →
        // UNIQUE violation. The OLD bare-btrim index folded ONLY spaces, so a
        // tab-padded variant would have slipped through as a false distinct.
        UUID tenant = UUID.randomUUID();
        insert(tenant, "NAME", "EXACT", " uTorrent ", null);
        assertThatThrownBy(() -> insert(tenant, "NAME", "EXACT", "\tUTORRENT\t", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_endpoint_prohibited_software_rules_tenant_dedup");
    }

    // ──────────────────────────────────────────────────────────────────
    // Functional UNIQUE dedup index (with NULL-folding)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void duplicateRuleRejectedByDedupIndexCaseInsensitively() {
        UUID tenant = UUID.randomUUID();
        insert(tenant, "NAME", "EXACT", "uTorrent", null);

        // Same logical rule, different case + whitespace → folds to the same
        // dedup key → UNIQUE violation. Proves NULL publisher folds to ''.
        assertThatThrownBy(() -> insert(tenant, "NAME", "EXACT", "  UTORRENT ", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_endpoint_prohibited_software_rules_tenant_dedup");
    }

    @Test
    void distinctTenantsMayHoldEquivalentRule() {
        insert(UUID.randomUUID(), "NAME", "EXACT", "uTorrent", null);
        insert(UUID.randomUUID(), "NAME", "EXACT", "uTorrent", null);
        // Two different tenants, equivalent rule → both persist (dedup is
        // tenant-scoped).
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Long.class)).isEqualTo(2L);
    }

    @Test
    void differentMatchModeIsNotADuplicate() {
        UUID tenant = UUID.randomUUID();
        insert(tenant, "NAME", "EXACT", "uTorrent", null);
        // Same pattern, different mode → distinct dedup key → allowed.
        insert(tenant, "NAME", "CONTAINS", "uTorrent", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ?",
                Long.class, tenant)).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private UUID insert(String matchType, String matchMode,
                        String namePattern, String publisherPattern) {
        return insert(UUID.randomUUID(), matchType, matchMode, namePattern, publisherPattern);
    }

    private UUID insert(UUID tenantId, String matchType, String matchMode,
                        String namePattern, String publisherPattern) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO " + TABLE
                        + " (id, tenant_id, match_type, match_mode, name_pattern, "
                        + "  publisher_pattern, enabled, notes, created_by_subject, "
                        + "  created_at, last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, matchType, matchMode, namePattern, publisherPattern,
                true, "test note", "admin@example.com",
                Timestamp.from(Instant.now()),
                "admin@example.com",
                Timestamp.from(Instant.now()),
                0L);
        return id;
    }
}
