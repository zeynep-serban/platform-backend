package com.example.endpointadmin.migration;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-017 — Postgres-only verification that the V5 dual-control migration
 * applies cleanly through the full Flyway V1–V5 chain on a real Postgres
 * engine. The H2 test profile has Flyway disabled ({@code ddl-auto=create-drop}),
 * so this is the only place the V5 SQL itself is exercised; {@code ddl-auto=validate}
 * additionally cross-checks every JPA entity mapping against the migrated schema.
 *
 * <p>Codex {@code 019e50e0} post-impl review caught a V5 index-name collision
 * with V2's {@code idx_endpoint_commands_deliverable} — Flyway V5 would have
 * failed at startup. {@link #v5DeliverableIndexDoesNotCollideWithV2} is the
 * regression guard.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointCommandDualControlMigrationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("endpoint_admin")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema on the real Postgres engine; Hibernate only validates.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    void v5AddsApprovalStatusColumnWithNotRequiredDefault() {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT is_nullable, column_default FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = 'endpoint_commands' "
                                + "AND column_name = 'approval_status'")
                .getSingleResult();
        assertThat(row[0]).isEqualTo("NO");
        assertThat((String) row[1]).contains("NOT_REQUIRED");
    }

    @Test
    void v5CreatesApprovalTableWithItsNamedConstraints() {
        assertThat(count(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'endpoint_command_approvals'"))
                .as("endpoint_command_approvals table created")
                .isEqualTo(1L);
        assertThat(count(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_schema = 'public' AND constraint_name IN ("
                        + "'uq_endpoint_command_approvals_command', "
                        + "'fk_endpoint_command_approvals_command', "
                        + "'ck_endpoint_command_approvals_decision', "
                        + "'ck_endpoint_commands_approval_status')"))
                .as("all four BE-017 named constraints present")
                .isEqualTo(4L);
    }

    @Test
    void v5DeliverableIndexDoesNotCollideWithV2() {
        // Codex 019e50e0 P0: V5 must NOT reuse V2's idx_endpoint_commands_deliverable.
        assertThat(count(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = 'idx_endpoint_commands_deliverable'"))
                .as("V2 global deliverable index preserved")
                .isEqualTo(1L);
        assertThat(count(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = 'idx_endpoint_commands_device_deliverable'"))
                .as("V5 per-device deliverable index created under its own name")
                .isEqualTo(1L);
    }

    @Test
    void v5RecordedAsSuccessfulInFlywayHistory() {
        assertThat(count(
                "SELECT count(*) FROM endpoint_admin_flyway_history "
                        + "WHERE version = '5' AND success = true"))
                .isEqualTo(1L);
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
