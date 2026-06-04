package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Faz 21.1 Cleanup C4 step-5 regression guard — V45 pure FK flip across two
 * independent device-rooted families (outdated_software snapshots+packages +
 * software_inventory_state_history). All enablers (child org_id NON-NULL, parent
 * UNIQUE(id, org_id)) pre-existed; this migration only flips 3 FKs. Behavioral
 * cross-org 23503 coverage lives in the existing (rolled-over) outdated-software
 * and software-inventory ITs; this test pins the structural flip.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V45OutdatedSwAndInventoryOrgFkFlipPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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

    private static final String[][] ORG_FKS = {
            {"osw_pkg_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_outdated_software_snapshots(id, org_id) ON DELETE CASCADE"},
            {"osw_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"},
            {"sw_inv_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    private static final String[] OLD_TENANT_FKS = {
            "fk_endpoint_outdated_software_packages_snapshot",
            "fk_endpoint_outdated_software_snapshots_device",
            "fk_endpoint_software_inventory_state_history_device"
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allThreeFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : OLD_TENANT_FKS) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }
}
