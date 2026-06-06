package com.example.endpointadmin.migration;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-031 regression guard for V54 signed agent update release catalog.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V54AgentUpdateReleasesPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String RELEASES =
            SCHEMA + ".endpoint_agent_update_releases";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA512 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
    void flywayAppliesV54AndHibernateValidatesSchema() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + RELEASES,
                Long.class)).isZero();
    }

    @Test
    void orgTriggerFillsLegacyWriterRowsAndUniqueArbiterIsOrgScoped() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID releaseA = insertRelease(orgA, "endpoint-agent-0.2.0",
                "DRAFT", "alice@example.com", null);
        insertRelease(orgB, "endpoint-agent-0.2.0",
                "DRAFT", "alice@example.com", null);

        assertThat(jdbc.queryForObject(
                "SELECT org_id FROM " + RELEASES + " WHERE id=?",
                UUID.class, releaseA)).isEqualTo(orgA);

        assertThatThrownBy(() -> insertRelease(orgA,
                "endpoint-agent-0.2.0", "DRAFT", "charlie@example.com", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sha256CheckRejectsInvalidRows() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertReleaseWithOverrides(
                org, "bad-sha", "DRAFT", "alice@example.com",
                null, "not-a-sha", SHA512, "A".repeat(40),
                "PILOT", "TRUSTED_SIGNED", 25_000_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thumbprintCheckRejectsInvalidRows() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertReleaseWithOverrides(
                org, "bad-thumbprint", "DRAFT", "alice@example.com",
                null, SHA256, SHA512, "A".repeat(39),
                "PILOT", "TRUSTED_SIGNED", 25_000_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void channelCheckRejectsInvalidRows() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertReleaseWithOverrides(
                org, "bad-channel", "DRAFT", "alice@example.com",
                null, SHA256, SHA512, "A".repeat(40),
                "EVERYONE", "TRUSTED_SIGNED", 25_000_000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void makerCheckerConstraintIsDbBackstop() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertRelease(
                org, "self-approved", "APPROVED",
                "alice@example.com", "alice@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvalPairConstraintIsDbBackstop() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertReleaseWithPartialApproval(
                org, "partial-approval"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** org_id omitted intentionally — V54 trigger fills it from tenant_id. */
    private UUID insertRelease(UUID org,
                               String releaseId,
                               String status,
                               String createdBy,
                               String approvedBy) {
        return insertReleaseWithOverrides(org, releaseId, status, createdBy,
                approvedBy, SHA256, SHA512, "A".repeat(40), "PILOT",
                "TRUSTED_SIGNED", 25_000_000);
    }

    private UUID insertReleaseWithOverrides(UUID org,
                                            String releaseId,
                                            String status,
                                            String createdBy,
                                            String approvedBy,
                                            String sha256,
                                            String sha512,
                                            String thumbprint,
                                            String channel,
                                            String signingTier,
                                            long maxBytes) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + RELEASES + " ("
                        + "id, tenant_id, release_id, channel, target_version, "
                        + "binary_url, manifest_url, sha256, sha512, "
                        + "signer_thumbprint, signing_tier, max_bytes, "
                        + "status, enabled, created_by_subject, created_at, "
                        + "last_updated_by_subject, last_updated_at, "
                        + "approved_by_subject, approved_at, version) "
                        + "VALUES (?, ?, ?, ?, '0.2.0', "
                        + "'https://downloads.example.com/endpoint-agent.exe', "
                        + "'https://downloads.example.com/endpoint-agent.json', "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, org, releaseId, channel, sha256, sha512, thumbprint,
                signingTier, maxBytes, status, "APPROVED".equals(status),
                createdBy, now, createdBy, now, approvedBy,
                approvedBy == null ? null : now);
        return id;
    }

    private UUID insertReleaseWithPartialApproval(UUID org, String releaseId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + RELEASES + " ("
                        + "id, tenant_id, release_id, channel, target_version, "
                        + "binary_url, sha256, signer_thumbprint, signing_tier, "
                        + "max_bytes, status, enabled, created_by_subject, "
                        + "created_at, last_updated_by_subject, last_updated_at, "
                        + "approved_by_subject, approved_at, version) "
                        + "VALUES (?, ?, ?, 'PILOT', '0.2.0', "
                        + "'https://downloads.example.com/endpoint-agent.exe', "
                        + "?, ?, 'TRUSTED_SIGNED', 25000000, 'APPROVED', true, "
                        + "'alice@example.com', ?, 'alice@example.com', ?, "
                        + "'bob@example.com', NULL, 0)",
                id, org, releaseId, SHA256, "A".repeat(40), now, now);
        return id;
    }
}
