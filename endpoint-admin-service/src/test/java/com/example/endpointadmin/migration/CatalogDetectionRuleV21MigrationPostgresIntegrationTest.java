package com.example.endpointadmin.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-only verification of the V21 detection_rule sweep
 * ({@code V21__catalog_detection_rule_agent_schema.sql}) — agent PR #43 /
 * Codex 019e7d82.
 *
 * <p>Flyway runs V21 once at container start against an EMPTY table (a no-op),
 * so this test inserts old-shape fixture rows AFTER start and re-executes the
 * V21 script on the same (transaction-bound) connection to exercise the actual
 * migration SQL — most importantly the PG GUID regex and the JSONB key
 * rename/drop operators that H2 cannot represent.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CatalogDetectionRuleV21MigrationPostgresIntegrationTest {

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

    private static final UUID TENANT =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String GUID =
            "{23170F69-40C1-2702-2107-000001000000}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void wingetRowRenamesWingetPackageIdToPackageId() {
        insertCatalogRow("v21-winget-legacy",
                "{\"type\":\"WINGET_PACKAGE\",\"wingetPackageId\":\"7zip.7zip\"}");

        runV21();

        assertThat(jsonText("v21-winget-legacy", "type")).isEqualTo("WINGET_PACKAGE");
        assertThat(jsonText("v21-winget-legacy", "packageId")).isEqualTo("7zip.7zip");
        assertThat(jsonHasKey("v21-winget-legacy", "wingetPackageId")).isFalse();
    }

    @Test
    void wingetRowAlreadyOnPackageIdIsLeftUnchanged() {
        insertCatalogRow("v21-winget-canonical",
                "{\"type\":\"WINGET_PACKAGE\",\"packageId\":\"7zip.7zip\"}");

        runV21();

        assertThat(jsonText("v21-winget-canonical", "packageId")).isEqualTo("7zip.7zip");
        assertThat(jsonHasKey("v21-winget-canonical", "wingetPackageId")).isFalse();
    }

    @Test
    void registryGuidUninstallKeyBecomesProductCodeAndDropsLegacyKeys() {
        insertCatalogRow("v21-reg-guid",
                "{\"type\":\"REGISTRY_UNINSTALL\",\"hive\":\"HKLM\","
                        + "\"uninstallKeyName\":\"" + GUID + "\","
                        + "\"displayNameRegex\":\"^7-Zip.*\"}");

        runV21();

        assertThat(jsonText("v21-reg-guid", "productCode")).isEqualTo(GUID);
        assertThat(jsonHasKey("v21-reg-guid", "hive")).isFalse();
        assertThat(jsonHasKey("v21-reg-guid", "uninstallKeyName")).isFalse();
        assertThat(jsonHasKey("v21-reg-guid", "displayNameRegex")).isFalse();
    }

    @Test
    void registryRowWithExistingProductCodeIsNotClobberedAndLegacyKeysStripped() {
        // A row carrying BOTH a canonical productCode (A) and a divergent legacy
        // GUID uninstallKeyName (B): the canonical productCode must survive
        // (NOT be overwritten by B), and the legacy keys must be stripped
        // (Codex 019e7dce clobber guard).
        insertCatalogRow("v21-reg-both",
                "{\"type\":\"REGISTRY_UNINSTALL\","
                        + "\"productCode\":\"{11111111-1111-1111-1111-111111111111}\","
                        + "\"hive\":\"HKLM\","
                        + "\"uninstallKeyName\":\"{22222222-2222-2222-2222-222222222222}\"}");

        runV21();

        assertThat(jsonText("v21-reg-both", "productCode"))
                .isEqualTo("{11111111-1111-1111-1111-111111111111}");
        assertThat(jsonHasKey("v21-reg-both", "hive")).isFalse();
        assertThat(jsonHasKey("v21-reg-both", "uninstallKeyName")).isFalse();
    }

    @Test
    void registryNonGuidUninstallKeyIsLeftForManualReauthor() {
        insertCatalogRow("v21-reg-nonguid",
                "{\"type\":\"REGISTRY_UNINSTALL\",\"hive\":\"HKLM\","
                        + "\"uninstallKeyName\":\"7-Zip\"}");

        runV21();

        // Not a GUID → not auto-converted; left intact for manual reauthoring.
        assertThat(jsonHasKey("v21-reg-nonguid", "productCode")).isFalse();
        assertThat(jsonText("v21-reg-nonguid", "uninstallKeyName")).isEqualTo("7-Zip");
    }

    @Test
    void fileExistsRowIsUntouched() {
        insertCatalogRow("v21-file",
                "{\"type\":\"FILE_EXISTS\",\"absolutePath\":\"C:\\\\Program Files\\\\7-Zip\\\\7z.exe\"}");

        runV21();

        assertThat(jsonText("v21-file", "type")).isEqualTo("FILE_EXISTS");
        assertThat(jsonText("v21-file", "absolutePath"))
                .isEqualTo("C:\\Program Files\\7-Zip\\7z.exe");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void runV21() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource(
                            "db/migration/V21__catalog_detection_rule_agent_schema.sql"),
                    StandardCharsets.UTF_8));
            return null;
        });
    }

    private void insertCatalogRow(String slug, String detectionRuleJson) {
        jdbcTemplate.update(
                "INSERT INTO endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, "
                        + "source_type, source_name, source_trust, package_id, "
                        + "display_name, publisher, version_policy_type, "
                        + "detection_rule, risk_tier, enabled, "
                        + "created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'DRAFT', 'WINGET', 'WINGET', "
                        + "'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', "
                        + "'7-Zip', 'Igor Pavlov', 'LATEST', ?::jsonb, 'LOW', "
                        + "false, 'alice@example.com', now(), "
                        + "'alice@example.com', now(), 0)",
                UUID.randomUUID(), TENANT, slug, detectionRuleJson);
    }

    /** {@code detection_rule ->> key} — uses the operator (no clash with JDBC ?). */
    private String jsonText(String slug, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT detection_rule ->> ? FROM endpoint_software_catalog_items "
                        + "WHERE catalog_item_id = ?",
                String.class, key, slug);
    }

    /**
     * {@code jsonb_exists(detection_rule, key)} — the function form, NOT the
     * {@code ?} existence operator, which would clash with JDBC placeholders.
     */
    private boolean jsonHasKey(String slug, String key) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT jsonb_exists(detection_rule, ?) "
                        + "FROM endpoint_software_catalog_items "
                        + "WHERE catalog_item_id = ?",
                Boolean.class, key, slug);
        return Boolean.TRUE.equals(exists);
    }
}
