package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
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

/**
 * BE — #1146 regression guard: the bulk latest-snapshots query MUST resolve
 * the snapshot tables when they live in a NON-{@code public} schema —
 * exactly like the live testai database ({@code endpoint_admin_service}).
 *
 * <p><strong>Why this exists.</strong> The first cut used a native window
 * query with an unqualified {@code FROM endpoint_device_health_snapshots}
 * and 500'd on live testai with {@code relation ... does not exist}: the
 * tables live in the non-{@code public} {@code endpoint_admin_service}
 * schema and the connection search_path does not include it. The other
 * {@code *PostgresIntegrationTest} classes pin the schema to {@code public},
 * so the unqualified native SQL resolved there and the bug slipped through.
 *
 * <p>This test reproduces the live topology: the schema is
 * {@code endpoint_admin_service} but the JDBC URL sets NO
 * {@code currentSchema}, so the connection search_path stays the PostgreSQL
 * default ({@code "$user", public}). Hibernate qualifies HQL/JPQL from
 * {@code default_schema} (so the entity query resolves), while an
 * unqualified native query would NOT find the tables. If the bulk query is
 * ever reverted to native-unqualified SQL, this test fails with the exact
 * live error — the regression cannot ship silently again.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BulkLatestSnapshotsSchemaQualificationPostgresIntegrationTest {

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
        // Deliberately NO currentSchema/search_path override on the URL: the
        // schema is non-public but the connection search_path stays the PG
        // default, exactly like live testai. Hibernate qualifies entity
        // queries from default_schema; an unqualified native query would not
        // find the tables (that is the bug this test guards).
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
    private EndpointDeviceHealthSnapshotRepository healthRepo;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bulkQueryResolvesTablesInNonPublicSchema() {
        UUID tenant = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-05-30T09:00:00Z"));
        // Raw SQL must name the schema explicitly (only Hibernate
        // auto-qualifies); the snapshots below go through JPA, which
        // Hibernate qualifies from default_schema.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'schema-host', 'WINDOWS', 'ONLINE', ?, ?, 0)",
                device, tenant, now, now);

        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        saveHealth(tenant, device, t.minusSeconds(3600), 10);
        saveHealth(tenant, device, t, 90);

        // The JPQL query must resolve endpoint_admin_service.* and return the
        // latest snapshot. A native unqualified FROM would throw here with
        // "relation ... does not exist" (the live 500).
        List<EndpointDeviceHealthSnapshot> latest =
                healthRepo.findLatestPerDeviceForTenant(tenant, PageRequest.of(0, 10));

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getMemoryUsedPercent()).isEqualTo((short) 90);
    }

    private void saveHealth(UUID tenant, UUID device, Instant collectedAt, int memoryUsedPercent) {
        EndpointDeviceHealthSnapshot s = new EndpointDeviceHealthSnapshot();
        s.setTenantId(tenant);
        s.setDeviceId(device);
        s.setSchemaVersion((short) 1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setAnyLowDisk(false);
        s.setFixedDiskCount(1);
        s.setFixedDisksTruncated(false);
        s.setMaxFixedDisks(64);
        s.setMemoryUsedPercent((short) memoryUsedPercent);
        s.setMemoryHighPressure(false);
        s.setUptimeDays(10);
        s.setLongUptimeWarning(false);
        s.setSourceUsed("win32");
        s.setPayloadHashSha256("a".repeat(64));
        s.setCollectedAt(collectedAt);
        healthRepo.saveAndFlush(s);
    }
}
