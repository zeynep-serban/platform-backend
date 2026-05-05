package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenRequest;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenResponse;
import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenRequest;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.MaintenanceTokenStatus;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMaintenanceTokenRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EnrollmentTokenGenerator;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import com.example.endpointadmin.security.DeviceCredentialResult;
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
        EndpointMaintenanceTokenService.class,
        EndpointAuditService.class,
        EnrollmentTokenGenerator.class,
        EnrollmentTokenHasher.class
})
class EndpointMaintenanceTokenServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointMaintenanceTokenService tokenService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointMaintenanceTokenRepository tokenRepository;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createTokenStoresOnlyHashAndAuditEvent() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));

        CreateMaintenanceTokenResponse response = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.UNINSTALL_AGENT, "offboarding", 60)
        );

        entityManager.flush();
        entityManager.clear();

        var stored = tokenRepository.findByTenantIdAndId(TENANT_ID, response.tokenId()).orElseThrow();
        assertThat(response.token()).isNotBlank();
        assertThat(stored.getTokenHash()).doesNotContain(response.token());
        assertThat(stored.getAction()).isEqualTo(MaintenanceAction.UNINSTALL_AGENT);
        assertThat(stored.getStatus()).isEqualTo(MaintenanceTokenStatus.PENDING);
        assertThat(stored.getReason()).isEqualTo("offboarding");
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_CREATED");
    }

    @Test
    void createTokenRejectsSecondPendingTokenForSameDeviceAndAction() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenRequest request =
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "maintenance", 60);
        tokenService.createToken(adminContext(), device.getId(), request);

        assertThatThrownBy(() -> tokenService.createToken(adminContext(), device.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Pending maintenance token already exists");
    }

    @Test
    void createTokenRequiresReasonAndTtl() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));

        assertThatThrownBy(() -> tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, " ", 60)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Maintenance reason is required");

        assertThatThrownBy(() -> tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "maintenance", null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Maintenance token TTL is required");
    }

    @Test
    void consumeTokenMarksTokenConsumedForAuthenticatedDeviceOnly() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "support", 60)
        );

        var consumed = tokenService.consumeToken(
                principal(device),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        );
        entityManager.flush();
        entityManager.clear();

        var stored = tokenRepository.findByTenantIdAndId(TENANT_ID, created.tokenId()).orElseThrow();
        assertThat(consumed.action()).isEqualTo(MaintenanceAction.STOP_AGENT);
        assertThat(consumed.deviceId()).isEqualTo(device.getId());
        assertThat(stored.getStatus()).isEqualTo(MaintenanceTokenStatus.CONSUMED);
        assertThat(stored.getConsumedByAgentVersion()).isEqualTo("0.3.0");
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_CREATED", "MAINTENANCE_TOKEN_CONSUMED");

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(device),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Maintenance token is not pending");
    }

    @Test
    void consumeTokenRejectsDifferentAuthenticatedDevice() {
        EndpointDevice first = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-002"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                first.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.UNINSTALL_AGENT, "wrong-device", 60)
        );

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(second),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void consumeTokenExpiresPendingToken() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "expired", 60)
        );
        var stored = tokenRepository.findByTenantIdAndId(TENANT_ID, created.tokenId()).orElseThrow();
        stored.setExpiresAt(Instant.now().minusSeconds(60));
        tokenRepository.saveAndFlush(stored);
        entityManager.clear();

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(device),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410 GONE");

        entityManager.clear();
        assertThat(tokenRepository.findByTenantIdAndId(TENANT_ID, created.tokenId()).orElseThrow().getStatus())
                .isEqualTo(MaintenanceTokenStatus.EXPIRED);
    }

    @Test
    void revokeTokenMarksPendingTokenRevoked() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "cancel", 60)
        );

        var revoked = tokenService.revokeToken(adminContext(), created.tokenId());

        assertThat(revoked.status()).isEqualTo(MaintenanceTokenStatus.REVOKED);
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_REVOKED");
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private DeviceCredentialResult principal(EndpointDevice device) {
        return new DeviceCredentialResult(device.getId().toString(), UUID.randomUUID().toString(), Instant.now());
    }

    private EndpointDevice device(DeviceStatus status, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.3.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(status);
        device.setLastSeenAt(Instant.now());
        return device;
    }
}
