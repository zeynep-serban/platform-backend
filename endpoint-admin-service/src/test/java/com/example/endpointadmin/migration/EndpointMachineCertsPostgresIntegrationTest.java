package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3 — Postgres-only migration integration test for
 * {@code V11__endpoint_machine_certs.sql}.
 *
 * <p>Verifies the parts that H2 cannot exercise:
 * <ul>
 *   <li>Flyway V11 applies cleanly on a real Postgres engine;</li>
 *   <li>Hibernate {@code validate} is happy with the resulting table;</li>
 *   <li>Partial unique indexes on {@code device_id WHERE revoked_at IS NULL}
 *       and {@code san_uri WHERE revoked_at IS NULL} are actually enforced
 *       (H2 partial-index support is unreliable);</li>
 *   <li>Revoking a cert frees the partial unique slot — a new active cert
 *       with the same SAN URI / same device_id can then be inserted.</li>
 * </ul>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointMachineCertsPostgresIntegrationTest {

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
    private EndpointMachineCertRepository certRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private EndpointDevice createDevice(UUID tenantId, String hostname, String fingerprint) {
        EndpointDevice d = new EndpointDevice();
        d.setTenantId(tenantId);
        d.setHostname(hostname);
        d.setOsType(OsType.WINDOWS);
        d.setMachineFingerprint(fingerprint);
        d.setStatus(DeviceStatus.ONLINE);
        d.setEnrolledAt(Instant.now());
        d.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(d);
    }

    private EndpointMachineCert build(EndpointDevice device, String sanUri, String thumb) {
        EndpointMachineCert c = new EndpointMachineCert();
        // Do NOT setId() — @GeneratedValue handles id assignment; manual id
        // makes Hibernate treat the entity as detached with null version.
        c.setDevice(device);
        c.setTenantId(device.getTenantId());
        c.setSanUri(sanUri);
        c.setObjectGuid(UUID.fromString(sanUri.substring("adcomputer:".length())));
        c.setCertSerial("01");
        c.setCertThumbprint(thumb);
        c.setCertIssuer("CN=acik.local-CA");
        c.setCertSubject("CN=" + device.getHostname());
        c.setCertNotBefore(Instant.now().minusSeconds(60));
        c.setCertNotAfter(Instant.now().plusSeconds(365L * 24L * 60L * 60L));
        c.setMachineFingerprint(device.getMachineFingerprint());
        c.setEnrolledAt(Instant.now());
        return c;
    }

    @Test
    void flywayLiftsSchemaAndHibernateValidates() {
        assertThat(certRepository).isNotNull();
        assertThat(certRepository.count()).isZero();
    }

    @Test
    void v11DeclaresExpectedPartialUniqueIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'endpoint_machine_certs'",
                String.class);
        assertThat(indexes)
                .contains(
                        "uq_endpoint_machine_certs_device_active",
                        "uq_endpoint_machine_certs_san_uri_active",
                        "idx_endpoint_machine_certs_tenant_thumbprint",
                        "idx_endpoint_machine_certs_object_guid",
                        "idx_endpoint_machine_certs_tenant_enrolled");
    }

    @Test
    void partialUniqueIndexRejectsTwoActiveCertsForSameSanUri() {
        UUID tenantA = UUID.randomUUID();
        EndpointDevice d1 = createDevice(tenantA, "PC-1", "fp-1");
        EndpointDevice d2 = createDevice(tenantA, "PC-2", "fp-2");

        UUID sharedGuid = UUID.randomUUID();
        String sanUri = "adcomputer:" + sharedGuid.toString().toLowerCase();

        certRepository.saveAndFlush(build(d1, sanUri, "thumb-1"));

        assertThatThrownBy(() ->
                certRepository.saveAndFlush(build(d2, sanUri, "thumb-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void partialUniqueIndexRejectsTwoActiveCertsForSameDevice() {
        UUID tenantA = UUID.randomUUID();
        EndpointDevice device = createDevice(tenantA, "PC-1", "fp-1");

        UUID guid1 = UUID.randomUUID();
        UUID guid2 = UUID.randomUUID();
        certRepository.saveAndFlush(build(device,
                "adcomputer:" + guid1.toString().toLowerCase(), "thumb-1"));

        assertThatThrownBy(() ->
                certRepository.saveAndFlush(build(device,
                        "adcomputer:" + guid2.toString().toLowerCase(), "thumb-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokingActiveCertFreesPartialUniqueSlot() {
        UUID tenantA = UUID.randomUUID();
        EndpointDevice device = createDevice(tenantA, "PC-1", "fp-1");

        UUID guid = UUID.randomUUID();
        String sanUri = "adcomputer:" + guid.toString().toLowerCase();

        EndpointMachineCert first = certRepository.saveAndFlush(build(device, sanUri, "thumb-1"));

        first.setRevokedAt(Instant.now());
        first.setRevokedReason("rotated");
        certRepository.saveAndFlush(first);

        EndpointMachineCert second = certRepository.saveAndFlush(build(device, sanUri, "thumb-2"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(certRepository.findActiveBySanUri(sanUri).orElseThrow().getId())
                .isEqualTo(second.getId());
    }

    @Test
    void revocationPairCheckRejectsRevokedAtWithoutReason() {
        UUID tenantA = UUID.randomUUID();
        EndpointDevice device = createDevice(tenantA, "PC-1", "fp-1");
        UUID guid = UUID.randomUUID();
        EndpointMachineCert cert = build(device,
                "adcomputer:" + guid.toString().toLowerCase(), "thumb-1");
        certRepository.saveAndFlush(cert);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE endpoint_machine_certs SET revoked_at = now() WHERE id = ?",
                cert.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void validityCheckRejectsNotAfterBeforeNotBefore() {
        UUID tenantA = UUID.randomUUID();
        EndpointDevice device = createDevice(tenantA, "PC-1", "fp-1");

        UUID guid = UUID.randomUUID();
        EndpointMachineCert bad = build(device,
                "adcomputer:" + guid.toString().toLowerCase(), "thumb-1");
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        bad.setCertNotBefore(t.plusSeconds(60));
        bad.setCertNotAfter(t);

        assertThatThrownBy(() -> certRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
