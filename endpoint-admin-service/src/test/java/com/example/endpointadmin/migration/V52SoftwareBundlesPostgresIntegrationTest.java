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
 * BE-029 regression guard for V52 approved software bundles.
 *
 * <p>H2 service tests cover the control-plane service behavior; this test covers
 * the PostgreSQL-only schema properties that matter for safe rollout policy
 * composition: org-id triggers, composite FKs, unique arbiters and delete
 * semantics.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V52SoftwareBundlesPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String BUNDLES = SCHEMA + ".endpoint_software_bundles";
    private static final String ITEMS = SCHEMA + ".endpoint_software_bundle_items";
    private static final String CATALOG = SCHEMA + ".endpoint_software_catalog_items";

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
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesV52AndHibernateValidatesSchema() {
        // If Flyway did not apply V52, or Hibernate validate disagreed with
        // the migration-built schema, this test context would fail to start.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + BUNDLES,
                Long.class)).isZero();
    }

    @Test
    void bundleAndItemConstraintsAreOrgComposite() {
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='endpoint_software_bundles_id_org_id_key'",
                String.class)).isEqualTo("UNIQUE (id, org_id)");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='uq_endpoint_software_bundles_org_bundle'",
                String.class)).isEqualTo("UNIQUE (org_id, bundle_id)");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='fk_endpoint_software_bundle_items_bundle'",
                String.class)).isEqualTo("FOREIGN KEY (bundle_id, org_id) "
                        + "REFERENCES " + SCHEMA
                        + ".endpoint_software_bundles(id, org_id) "
                        + "ON DELETE CASCADE");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='fk_endpoint_software_bundle_items_catalog'",
                String.class)).isEqualTo("FOREIGN KEY (catalog_item_id, org_id) "
                        + "REFERENCES " + SCHEMA
                        + ".endpoint_software_catalog_items(id, org_id) "
                        + "ON DELETE RESTRICT");
    }

    @Test
    void orgChecksAndTriggersFillLegacyWriterRows() {
        for (String con : new String[]{
                "endpoint_software_bundles_org_id_match",
                "endpoint_software_bundles_org_id_not_null",
                "endpoint_software_bundle_items_org_id_match",
                "endpoint_software_bundle_items_org_id_not_null"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT convalidated FROM pg_constraint "
                            + "WHERE conname=? AND contype='c'",
                    Boolean.class, con))
                    .as("%s validated", con)
                    .isTrue();
        }

        UUID org = UUID.randomUUID();
        UUID catalog = seedCatalog(org);
        UUID bundle = insertBundle(org, "pilot-tools");
        UUID item = insertBundleItem(org, bundle, catalog, 0);

        assertThat(jdbc.queryForObject(
                "SELECT org_id FROM " + BUNDLES + " WHERE id=?",
                UUID.class, bundle)).isEqualTo(org);
        assertThat(jdbc.queryForObject(
                "SELECT org_id FROM " + ITEMS + " WHERE id=?",
                UUID.class, item)).isEqualTo(org);
    }

    @Test
    void duplicateBundleSlugSameOrgRejectedButCrossOrgAllowed() {
        String slug = "standard-tools";
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        insertBundle(orgA, slug);
        insertBundle(orgB, slug);

        assertThatThrownBy(() -> insertBundle(orgA, slug))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void duplicateItemOrderWithinBundleRejected() {
        UUID org = UUID.randomUUID();
        UUID catalogA = seedCatalog(org);
        UUID catalogB = seedCatalog(org);
        UUID bundle = insertBundle(org, "dup-guard");
        insertBundleItem(org, bundle, catalogA, 0);

        assertThatThrownBy(() -> insertBundleItem(org, bundle, catalogB, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void duplicateCatalogWithinBundleRejected() {
        UUID org = UUID.randomUUID();
        UUID catalogA = seedCatalog(org);
        UUID bundle = insertBundle(org, "dup-catalog-guard");
        insertBundleItem(org, bundle, catalogA, 0);

        assertThatThrownBy(() -> insertBundleItem(org, bundle, catalogA, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void crossOrgBundleItemReferenceRejected() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID catalogA = seedCatalog(orgA);
        UUID bundleB = insertBundle(orgB, "cross-org");

        assertThatThrownBy(() -> insertBundleItem(orgB, bundleB, catalogA, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deletingBundleCascadesItemsButCatalogDeleteIsRestricted() {
        UUID org1 = UUID.randomUUID();
        UUID catalog1 = seedCatalog(org1);
        UUID bundle1 = insertBundle(org1, "delete-bundle");
        UUID item1 = insertBundleItem(org1, bundle1, catalog1, 0);

        jdbc.update("DELETE FROM " + BUNDLES + " WHERE id=?", bundle1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + ITEMS
                + " WHERE id=?", Long.class, item1)).isZero();

        UUID org2 = UUID.randomUUID();
        UUID catalog2 = seedCatalog(org2);
        UUID bundle2 = insertBundle(org2, "keep-catalog");
        insertBundleItem(org2, bundle2, catalog2, 0);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM " + CATALOG + " WHERE id=?", catalog2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    private UUID seedCatalog(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + CATALOG + " ("
                        + "id, tenant_id, catalog_item_id, status, provider, "
                        + "source_type, source_name, source_trust, package_id, "
                        + "display_name, publisher, version_policy_type, "
                        + "detection_rule, risk_tier, enabled, "
                        + "created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', "
                        + "'winget', 'WINGET_COMMUNITY_REVIEWED', ?, ?, "
                        + "'Publisher', 'LATEST', "
                        + "'{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true, "
                        + "'creator', ?, 'creator', ?, 0)",
                id, org, "item-" + id, "pkg-" + id, "Display-" + id, now, now);
        return id;
    }

    /** org_id omitted — V52 trigger fills it from tenant_id. */
    private UUID insertBundle(UUID org, String bundleSlug) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + BUNDLES + " ("
                        + "id, tenant_id, bundle_id, display_name, status, "
                        + "enabled, created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'Pilot Tools', 'DRAFT', false, "
                        + "'creator', ?, 'creator', ?, 0)",
                id, org, bundleSlug, now, now);
        return id;
    }

    /** org_id omitted — V52 trigger fills it from tenant_id. */
    private UUID insertBundleItem(UUID org,
                                  UUID bundle,
                                  UUID catalog,
                                  int order) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + ITEMS + " ("
                        + "id, tenant_id, bundle_id, catalog_item_id, item_order, "
                        + "required, created_at, last_updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, true, ?, ?, 0)",
                id, org, bundle, catalog, order, now, now);
        return id;
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
