package com.example.endpointadmin.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE — PostgreSQL tier of the #1146 bulk latest-snapshots behaviour
 * (see {@link AbstractBulkLatestSnapshotsRepositoryTest}). Proves the
 * {@code ROW_NUMBER() OVER (PARTITION BY ...)} window function + the
 * {@code LIMIT :limit} parameter binding + the deterministic tie-break
 * resolve on a REAL PostgreSQL 16 engine — the H2-vs-PG gap that bit
 * BE-020I is the reason this query also runs against Testcontainers, not
 * just the H2 slice.
 *
 * <p>Mirrors the existing {@code *PostgresIntegrationTest} pattern: PG 16
 * Testcontainer + Flyway enabled + {@code ddl-auto=validate} +
 * {@code public} schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BulkLatestSnapshotsRepositoryPostgresIntegrationTest
        extends AbstractBulkLatestSnapshotsRepositoryTest {

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
}
