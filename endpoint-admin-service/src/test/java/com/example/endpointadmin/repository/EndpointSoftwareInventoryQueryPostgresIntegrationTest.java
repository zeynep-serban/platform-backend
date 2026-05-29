package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BE-020I — PostgreSQL-only regression test for the
 * {@code lower(bytea)} grammar bug that affected the device-detail
 * (item) and fleet-wide (snapshot) software inventory query surfaces
 * in production (live testai 2026-05-29).
 *
 * <p><strong>Bug</strong>: nullable {@code String} parameters passed
 * through Spring Data JPA into JPQL constructions like
 * {@code lower(:param)} or {@code lower(concat('%', :param, '%'))}
 * were sent to PostgreSQL with a {@code bytea} JDBC type when the
 * caller passed {@code null}. PG's overload resolver then tried
 * {@code lower(bytea)} (which does not exist) and the query failed
 * with {@code SQLGrammarException} — even though the column itself
 * was {@code VARCHAR(256)}. The bug was invisible to H2-backed slice
 * tests because H2's overload resolver is more permissive than PG's.
 *
 * <p><strong>Fix</strong>: explicit JPQL
 * {@code cast(:param as string)} so Hibernate emits
 * {@code CAST(? AS varchar)} regardless of binding direction, and PG
 * resolves {@code lower(varchar)} cleanly. Applied at every reference
 * to the affected nullable {@code String} parameter (both the
 * {@code (:param is null or ...)} guard and the {@code lower(...)}
 * call) so the SQL is self-consistent.
 *
 * <p><strong>Test scope</strong>:
 *
 * <ul>
 *   <li>{@link com.example.endpointadmin.repository.EndpointSoftwareInventoryItemRepository}
 *       {@code pageByTenantDeviceWithFilters} — device-detail call
 *       path (the one that produced the live errors).</li>
 *   <li>{@link com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository}
 *       {@code pageByTenantWithFilters} — fleet-wide call path with
 *       the same null-bound-String-into-{@code lower()} pattern. Fixed
 *       in the same PR to prevent the bug from resurrecting on a
 *       different endpoint.</li>
 * </ul>
 *
 * <p>The test asserts the queries no longer raise
 * {@code SQLGrammarException} when the filter parameters are
 * {@code null}, and that the basic case-insensitive match + null
 * combinations still return the expected row sets. H2-backed slice
 * tests in this codebase do not catch this regression class; that is
 * why this test lives in the PostgreSQL Testcontainers tier.
 *
 * <p>Mirrors the existing {@code *PostgresIntegrationTest} pattern:
 * PG 16 Testcontainer + Flyway enabled + {@code ddl-auto=validate} +
 * {@code public} schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointSoftwareInventoryQueryPostgresIntegrationTest {

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
    private EndpointSoftwareInventoryItemRepository itemRepository;

    @Autowired
    private EndpointSoftwareInventorySnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private UUID tenantId;
    private UUID deviceId;
    private UUID snapshotId;

    @BeforeEach
    void seed() {
        // Reset to a known starting point (cleared by @DataJpaTest tx
        // rollback in most cases, but explicit cleanup keeps the seed
        // counts deterministic when a test exercises a partial path).
        jdbc.update("DELETE FROM endpoint_software_inventory_items");
        jdbc.update("DELETE FROM endpoint_software_inventory_snapshots");
        jdbc.update("DELETE FROM endpoint_devices");

        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();

        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                deviceId, tenantId, "test-host", Timestamp.from(now),
                Timestamp.from(now));

        // V8 CHECK ck_endpoint_software_inventory_snapshots_apps_available_pair
        // requires apps_collected_at NOT NULL when apps_available = true.
        jdbc.update(
                "INSERT INTO endpoint_software_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " supported, truncated, apps_available, "
                        + " apps_collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 1, true, false, true, ?, ?, ?, 0)",
                snapshotId, tenantId, deviceId,
                Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));

        insertItem("7-Zip", "Igor Pavlov", SoftwareInstallSource.HKLM, now);
        insertItem("Notepad++", "Notepad++ Team",
                SoftwareInstallSource.HKLM, now);
        insertItem("Google Chrome", "Google LLC",
                SoftwareInstallSource.HKLM_WOW6432, now);
    }

    private void insertItem(String displayName, String publisher,
                            SoftwareInstallSource source, Instant now) {
        jdbc.update(
                "INSERT INTO endpoint_software_inventory_items "
                        + "(id, snapshot_id, tenant_id, device_id, "
                        + " display_name, publisher, install_source, "
                        + " uninstall_string_present, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)",
                UUID.randomUUID(), snapshotId, tenantId, deviceId,
                displayName, publisher, source.name(), Timestamp.from(now));
    }

    // ──────────────────────────────────────────────────────────────────
    // EndpointSoftwareInventoryItemRepository.pageByTenantDeviceWithFilters
    // ──────────────────────────────────────────────────────────────────

    @Test
    void itemRepository_allNullFilters_doesNotThrowLowerBytea() {
        // This is the exact shape that triggered the live testai
        // SQLGrammarException("function lower(bytea) does not exist")
        // before the cast(:param as string) fix.
        assertThatCode(() -> {
            Page<EndpointSoftwareInventoryItem> page =
                    itemRepository.pageByTenantDeviceWithFilters(
                            tenantId, deviceId,
                            /* q */ null,
                            /* publisher */ null,
                            /* installSource */ null,
                            PageRequest.of(0, 20));
            assertThat(page.getTotalElements()).isEqualTo(3);
        }).doesNotThrowAnyException();
    }

    @Test
    void itemRepository_partialMatchQ_caseInsensitive() {
        Page<EndpointSoftwareInventoryItem> page =
                itemRepository.pageByTenantDeviceWithFilters(
                        tenantId, deviceId,
                        /* q */ "ZIP",
                        /* publisher */ null,
                        /* installSource */ null,
                        PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getDisplayName())
                .isEqualTo("7-Zip");
    }

    @Test
    void itemRepository_exactPublisher_caseInsensitive() {
        Page<EndpointSoftwareInventoryItem> page =
                itemRepository.pageByTenantDeviceWithFilters(
                        tenantId, deviceId,
                        /* q */ null,
                        /* publisher */ "IGOR PAVLOV",
                        /* installSource */ null,
                        PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getPublisher())
                .isEqualTo("Igor Pavlov");
    }

    @Test
    void itemRepository_combinedFilters() {
        Page<EndpointSoftwareInventoryItem> page =
                itemRepository.pageByTenantDeviceWithFilters(
                        tenantId, deviceId,
                        /* q */ "Chrome",
                        /* publisher */ "Google LLC",
                        /* installSource */ SoftwareInstallSource.HKLM_WOW6432,
                        PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getDisplayName())
                .isEqualTo("Google Chrome");
    }

    @Test
    void itemRepository_nullStringPlusNonNullEnum_doesNotThrow() {
        // Regression: ensure null String + non-null enum binding is
        // self-consistent (the SQL must not infer bytea on the String
        // param just because the enum param is non-null).
        assertThatCode(() -> {
            Page<EndpointSoftwareInventoryItem> page =
                    itemRepository.pageByTenantDeviceWithFilters(
                            tenantId, deviceId,
                            /* q */ null,
                            /* publisher */ null,
                            /* installSource */ SoftwareInstallSource.HKLM,
                            PageRequest.of(0, 20));
            assertThat(page.getTotalElements()).isEqualTo(2);
        }).doesNotThrowAnyException();
    }

    // ──────────────────────────────────────────────────────────────────
    // EndpointSoftwareInventorySnapshotRepository.pageByTenantWithFilters
    // ──────────────────────────────────────────────────────────────────

    @Test
    void snapshotRepository_allNullFilters_doesNotThrowLowerBytea() {
        // Same root cause as the item repository but for the
        // fleet-wide list endpoint exists-subquery shape.
        assertThatCode(() -> {
            Page<EndpointSoftwareInventorySnapshot> page =
                    snapshotRepository.pageByTenantWithFilters(
                            tenantId,
                            /* softwareName */ null,
                            /* publisher */ null,
                            /* wingetReady */ null,
                            /* truncated */ null,
                            PageRequest.of(0, 20));
            assertThat(page.getTotalElements()).isEqualTo(1);
        }).doesNotThrowAnyException();
    }

    @Test
    void snapshotRepository_softwareNameOnly_caseInsensitivePartial() {
        Page<EndpointSoftwareInventorySnapshot> page =
                snapshotRepository.pageByTenantWithFilters(
                        tenantId,
                        /* softwareName */ "zip",
                        /* publisher */ null,
                        /* wingetReady */ null,
                        /* truncated */ null,
                        PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void snapshotRepository_publisherOnly_caseInsensitiveExact() {
        Page<EndpointSoftwareInventorySnapshot> page =
                snapshotRepository.pageByTenantWithFilters(
                        tenantId,
                        /* softwareName */ null,
                        /* publisher */ "google llc",
                        /* wingetReady */ null,
                        /* truncated */ null,
                        PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void snapshotRepository_combinedExistsFilters() {
        Page<EndpointSoftwareInventorySnapshot> page =
                snapshotRepository.pageByTenantWithFilters(
                        tenantId,
                        /* softwareName */ "7-zip",
                        /* publisher */ "igor pavlov",
                        /* wingetReady */ null,
                        /* truncated */ null,
                        PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void snapshotRepository_nullStringPlusNonNullBoolean_doesNotThrow() {
        // Regression mirror of itemRepository null+enum: ensure null
        // String filters are self-consistent when a boolean filter is
        // non-null on the same query.
        assertThatCode(() -> {
            snapshotRepository.pageByTenantWithFilters(
                    tenantId,
                    /* softwareName */ null,
                    /* publisher */ null,
                    /* wingetReady */ null,
                    /* truncated */ Boolean.FALSE,
                    PageRequest.of(0, 20));
        }).doesNotThrowAnyException();
    }
}
