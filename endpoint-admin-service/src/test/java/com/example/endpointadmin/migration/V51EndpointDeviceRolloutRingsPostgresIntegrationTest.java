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

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V51EndpointDeviceRolloutRingsPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String DEVICES = SCHEMA + ".endpoint_devices";

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
    void v51AddsDefaultPilotRingAndEmptyTagArray() {
        UUID device = insertDevice(UUID.randomUUID(), "pc-default");
        assertThat(jdbc.queryForObject(
                "SELECT deployment_ring FROM " + DEVICES + " WHERE id=?", String.class, device))
                .isEqualTo("PILOT");
        assertThat(jdbc.queryForObject(
                "SELECT device_tags::text FROM " + DEVICES + " WHERE id=?", String.class, device))
                .isEqualTo("[]");
    }

    @Test
    void ringCheckIsEnforced() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertDeviceWithRollout(org, "pc-bad-ring", "EVERYONE", "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jsonArrayCheckIsEnforced() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertDeviceWithRollout(org, "pc-bad-tags", "PILOT", "{\"tag\":\"pilot\"}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rolloutIndexesExist() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname='idx_endpoint_devices_org_deployment_ring'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname='idx_endpoint_devices_device_tags_gin'",
                Long.class)).isEqualTo(1L);
    }

    private UUID insertDevice(UUID org, String hostname) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DEVICES
                        + " (id, tenant_id, hostname, os_type, status, created_at, updated_at, version)"
                        + " VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, hostname, now, now);
        return id;
    }

    private void insertDeviceWithRollout(UUID org, String hostname, String ring, String tagsJson) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DEVICES
                        + " (id, tenant_id, hostname, os_type, status, deployment_ring, device_tags, created_at, updated_at, version)"
                        + " VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?::jsonb, ?, ?, 0)",
                id, org, hostname, ring, tagsJson, now, now);
    }
}
