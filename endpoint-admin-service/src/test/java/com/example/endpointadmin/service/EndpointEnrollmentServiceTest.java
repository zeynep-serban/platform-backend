package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentResponse;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentResponse;
import com.example.endpointadmin.model.EndpointDeviceCredential;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointDeviceCredentialRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import com.example.endpointadmin.security.DeviceSecretGenerator;
import com.example.endpointadmin.security.EnrollmentAttemptLimiter;
import com.example.endpointadmin.security.EnrollmentTokenGenerator;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        TimeConfig.class,
        EndpointEnrollmentService.class,
        EndpointDeviceService.class,
        DeviceCredentialService.class,
        EndpointAuditService.class,
        EnrollmentTokenGenerator.class,
        EnrollmentTokenHasher.class,
        EnrollmentAttemptLimiter.class,
        DeviceSecretGenerator.class,
        AesGcmDeviceSecretProtector.class
})
class EndpointEnrollmentServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointEnrollmentService enrollmentService;

    @Autowired
    private EndpointEnrollmentRepository enrollmentRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointDeviceCredentialRepository credentialRepository;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private AesGcmDeviceSecretProtector secretProtector;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createAndConsumeEnrollmentIssuesCredentialOnce() {
        CreateEndpointEnrollmentResponse created = enrollmentService.createEnrollment(
                adminContext(),
                new CreateEndpointEnrollmentRequest(60, "rollout-1")
        );

        ConsumeEnrollmentResponse consumed = enrollmentService.consumeEnrollment(
                consumeRequest(created.getToken(), "PC-001", "fp-001"),
                "127.0.0.1"
        );
        entityManager.flush();
        entityManager.clear();

        var enrollment = enrollmentRepository.findById(created.getEnrollmentId()).orElseThrow();
        EndpointDeviceCredential credential = credentialRepository.findByCredentialKeyId(consumed.getCredentialKeyId()).orElseThrow();

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONSUMED);
        assertThat(enrollment.getDevice().getId()).isEqualTo(consumed.getDeviceId());
        assertThat(deviceRepository.findByTenantIdAndMachineFingerprint(TENANT_ID, "fp-001")).isPresent();
        assertThat(credential.getEncryptedSecret()).doesNotContain(consumed.getSecret());
        assertThat(secretProtector.reveal(credential.getEncryptedSecret())).isEqualTo(consumed.getSecret());
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("ENDPOINT_ENROLLMENT_CREATED", "ENDPOINT_ENROLLMENT_CONSUMED");
    }

    @Test
    void consumeEnrollmentRejectsSecondUseOfSameToken() {
        CreateEndpointEnrollmentResponse created = enrollmentService.createEnrollment(
                adminContext(),
                new CreateEndpointEnrollmentRequest(60, null)
        );
        enrollmentService.consumeEnrollment(consumeRequest(created.getToken(), "PC-001", "fp-001"), "127.0.0.1");

        assertThatThrownBy(() -> enrollmentService.consumeEnrollment(
                consumeRequest(created.getToken(), "PC-002", "fp-002"),
                "127.0.0.1"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void consumeEnrollmentExpiresPendingToken() {
        CreateEndpointEnrollmentResponse created = enrollmentService.createEnrollment(
                adminContext(),
                new CreateEndpointEnrollmentRequest(60, null)
        );
        var enrollment = enrollmentRepository.findById(created.getEnrollmentId()).orElseThrow();
        enrollment.setExpiresAt(Instant.now().minusSeconds(60));
        enrollmentRepository.saveAndFlush(enrollment);
        entityManager.clear();

        assertThatThrownBy(() -> enrollmentService.consumeEnrollment(
                consumeRequest(created.getToken(), "PC-001", "fp-001"),
                "127.0.0.1"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410 GONE");

        entityManager.clear();
        assertThat(enrollmentRepository.findById(created.getEnrollmentId()).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.EXPIRED);
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private ConsumeEnrollmentRequest consumeRequest(String token, String hostname, String fingerprint) {
        return new ConsumeEnrollmentRequest(
                token,
                hostname,
                OsType.WINDOWS,
                "Windows 11 Pro",
                "0.1.0",
                fingerprint,
                "corp.local"
        );
    }
}
