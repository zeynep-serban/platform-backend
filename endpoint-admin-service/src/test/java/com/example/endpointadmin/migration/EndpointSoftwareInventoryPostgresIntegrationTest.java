package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-020I — PostgreSQL-only migration integration tests for
 * {@code V8__endpoint_software_inventory.sql} (Faz 22.5.3A).
 *
 * <p>Verifies the parts the H2 {@code @DataJpaTest} slice cannot:
 * Flyway V8 applies cleanly on a real Postgres engine, Hibernate
 * {@code validate} is happy against the resulting tables, and the
 * declared constraint inventory (CHECK on install_source +
 * apps_available_pair, UNIQUE tenant+device, foreign keys with the right
 * cascade actions) is actually registered.
 *
 * <p>Mirrors the existing {@code *PostgresIntegrationTest} pattern in the
 * service: PG 16 Testcontainer + Flyway enabled + {@code ddl-auto=validate}
 * + {@code public} schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointSoftwareInventoryPostgresIntegrationTest {

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
    private EndpointSoftwareInventorySnapshotRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayLiftsSchemaToV8AndHibernateValidatesAgainstIt() {
        // Context start with ddl-auto=validate is itself the assertion.
        assertThat(repository).isNotNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void v8DeclaresExpectedCheckAndUniqueConstraints() {
        List<String> checks = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid IN ("
                        + "'public.endpoint_software_inventory_snapshots'::regclass,"
                        + "'public.endpoint_software_inventory_items'::regclass) "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_software_inventory_snapshots_apps_available_pair",
                "ck_endpoint_software_inventory_items_install_source");

        List<String> uniques = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_inventory_snapshots'::regclass "
                        + "AND contype = 'u'",
                String.class);
        assertThat(uniques).contains(
                "uq_endpoint_software_inventory_snapshots_tenant_device");
    }

    @Test
    void v8DeclaresExpectedForeignKeys() {
        List<String> snapshotForeignKeys = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_inventory_snapshots'::regclass "
                        + "AND contype = 'f'",
                String.class);
        assertThat(snapshotForeignKeys).contains(
                "fk_endpoint_software_inventory_snapshots_device",
                "fk_endpoint_software_inventory_snapshots_summary_result",
                "fk_endpoint_software_inventory_snapshots_full_result");

        List<String> itemForeignKeys = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_inventory_items'::regclass "
                        + "AND contype = 'f'",
                String.class);
        assertThat(itemForeignKeys).contains(
                "fk_endpoint_software_inventory_items_snapshot");
    }

    @Test
    void v8DeclaresExpectedIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'public' "
                        + "AND tablename IN ("
                        + "'endpoint_software_inventory_snapshots',"
                        + "'endpoint_software_inventory_items')",
                String.class);
        assertThat(indexes).contains(
                "idx_endpoint_software_inventory_snapshots_tenant_apps_available",
                "idx_endpoint_software_inventory_snapshots_device",
                "idx_endpoint_software_inventory_items_tenant_device",
                "idx_endpoint_software_inventory_items_tenant_display_name_lower",
                "idx_endpoint_software_inventory_items_tenant_publisher");
    }

    // ────────────────────────────────────────────────────────────────
    // BE-021A V9 column + constraint + FK checks (Codex 019e6b88
    // plan-time AGREE acceptance #1).

    @Test
    void v9DeclaresExpectedWingetEgressColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name = 'endpoint_software_inventory_snapshots'",
                String.class);
        assertThat(columns).contains(
                "winget_egress",
                "winget_egress_collected_at",
                "latest_winget_egress_command_result_id",
                "winget_egress_schema_version");

        String jsonbType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name = 'endpoint_software_inventory_snapshots' "
                        + "AND column_name = 'winget_egress'",
                String.class);
        assertThat(jsonbType).isEqualTo("jsonb");
    }

    @Test
    void v9DeclaresEgressSchemaAndPairCheckConstraints() {
        List<String> checks = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_inventory_snapshots'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_software_inventory_snapshots_egress_schema",
                "ck_endpoint_software_inventory_snapshots_egress_pair");
    }

    @Test
    void v9DeclaresEgressForeignKeyWithSetNullCascade() {
        List<String> egressForeignKeys = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = "
                        + "'public.endpoint_software_inventory_snapshots'::regclass "
                        + "AND contype = 'f' "
                        + "AND conname = 'fk_endpoint_software_inventory_snapshots_egress_result'",
                String.class);
        assertThat(egressForeignKeys).hasSize(1);

        // ON DELETE SET NULL semantics: when a referenced
        // endpoint_command_results row is deleted, the FK column should
        // be cleared rather than cascading the delete to the snapshot.
        // pg_catalog.pg_constraint.confdeltype = 'n' for SET NULL.
        String confdeltype = jdbcTemplate.queryForObject(
                "SELECT confdeltype::text FROM pg_catalog.pg_constraint "
                        + "WHERE conname = 'fk_endpoint_software_inventory_snapshots_egress_result'",
                String.class);
        assertThat(confdeltype).isEqualTo("n");
    }
}
