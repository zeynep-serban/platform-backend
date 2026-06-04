package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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
 * Faz 21.1 Cleanup C4 step-7 regression guard — V47 endpoint_software_catalog_items
 * HUB org foundation. Mirrors the V46 commands hub: full org machinery
 * (trigger-fill + match/non-null CHECK), the UNIQUE(id, org_id) FK-target enabler
 * the catalog-consumer flips need (V48 install_audit / V49 uninstall family), and
 * the business UNIQUE single-arbiter swap: (tenant_id, catalog_item_id) dropped,
 * (org_id, catalog_item_id) sole arbiter (V35 single-arbiter lesson).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V47CatalogOrgFoundationPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String CAT = SCHEMA + ".endpoint_software_catalog_items";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

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
    private JdbcTemplate jdbc;

    @Test
    void catalogHasValidatedOrgChecks_andIdOrgUnique() {
        for (String con : new String[]{
                "endpoint_software_catalog_items_org_id_match",
                "endpoint_software_catalog_items_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_software_catalog_items_id_org_id_key' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void businessArbiterSwapped_orgUniquePresent_tenantUniqueDropped() {
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='uq_endpoint_software_catalog_items_org_catalog_item' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (org_id, catalog_item_id)");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='uq_endpoint_software_catalog_items_tenant_catalog_item'", Long.class))
                .as("old tenant business unique dropped").isEqualTo(0L);
        // The (id, tenant_id) unique stays (A6 deferred; reads tenant-keyed).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='uq_endpoint_software_catalog_items_id_tenant' AND contype='u'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void legacyWriter_catalogOrgIdOmitted_filledByTriggerToTenant() {
        UUID org = UUID.randomUUID();
        UUID id = insertCatalogNoOrg(org, "pkg-" + UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + CAT + " WHERE id=?", UUID.class, id)).isEqualTo(org);
    }

    @Test
    void duplicateOrgCatalogItem_isRejectedByOrgArbiter_23505() {
        UUID org = UUID.randomUUID();
        String catalogItemId = "dup-item";
        insertCatalogNoOrg(org, catalogItemId);
        // Same (tenant_id -> org_id, catalog_item_id): the org-keyed arbiter rejects it (23505).
        assertThatThrownBy(() -> insertCatalogNoOrg(org, catalogItemId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void explicitCatalogOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID org = UUID.randomUUID(), tenant = UUID.randomUUID();
        jdbc.execute("ALTER TABLE " + CAT + " DISABLE TRIGGER USER");
        assertThatThrownBy(() -> insertCatalogExplicitOrg(tenant, org, "mismatch-item"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23514"));
    }

    // ───────────────────────── helpers ─────────────────────────

    /** Legacy writer: org_id omitted — the V47 trigger fills it from tenant_id. */
    private UUID insertCatalogNoOrg(UUID tenant, String catalogItemId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + CAT + " ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, detection_rule, risk_tier, enabled,"
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, 'Publisher', 'LATEST',"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, tenant, catalogItemId, "pkg-" + catalogItemId, "Display-" + catalogItemId, now, now);
        return id;
    }

    /** Explicit org_id (used after trigger disabled) to exercise the match CHECK. */
    private UUID insertCatalogExplicitOrg(UUID tenant, UUID org, String catalogItemId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + CAT + " ("
                        + "id, tenant_id, org_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, detection_rule, risk_tier, enabled,"
                        + " created_by_subject, created_at, last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, 'Publisher', 'LATEST',"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, tenant, org, catalogItemId, "pkg-" + catalogItemId, "Display-" + catalogItemId, now, now);
        return id;
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) return sqlEx.getSQLState();
            cur = cur.getCause();
        }
        return null;
    }
}
