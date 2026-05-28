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
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointMaintenanceTokenService.class,
        EndpointAuditService.class,
        com.example.endpointadmin.audit.NoOpAuditChainLock.class,
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

        // BE-014A (Codex 019e4ed6 + 019e4ee1 noRollbackFor pattern):
        // re-consume attempt against already-consumed token emits deny audit
        // that PERSISTS even after the 409 throws (noRollbackFor=
        // ResponseStatusException on consumeToken keeps the audit row).
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_DENIED_ALREADY_CONSUMED");
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

        // BE-014A: device mismatch deny path emits audit event (durable via
        // noRollbackFor=ResponseStatusException — 403 throws but caller tx
        // NOT rolled back, so audit row persists).
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_DENIED_DEVICE_MISMATCH");
    }

    @Test
    void consumeTokenAfterRevokeEmitsDeniedAudit() {
        // BE-014A (Codex 019e4ed6 + 019e4ee1 noRollbackFor pattern):
        // re-consume against a REVOKED token must deny + emit
        // MAINTENANCE_TOKEN_DENIED_REVOKED audit event (durable).
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "revoke-then-consume", 60)
        );
        tokenService.revokeToken(adminContext(), created.tokenId());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(device),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Maintenance token is not pending");

        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_DENIED_REVOKED");
    }

    @Test
    void consumeTokenAfterExpiryReAttemptEmitsDeniedAudit() {
        // BE-014A (Codex 019e4ed6 + 019e4ee1 noRollbackFor pattern):
        // the just-expired path emits MAINTENANCE_TOKEN_EXPIRED via
        // expireIfNeeded(); a *re-attempt* against an already-EXPIRED
        // status token must emit MAINTENANCE_TOKEN_DENIED_EXPIRED (durable
        // via noRollbackFor=ResponseStatusException).
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                device.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "expire-then-consume", 60)
        );
        // Force EXPIRED status directly (bypass natural expireIfNeeded path
        // since that emits _EXPIRED, not _DENIED_EXPIRED).
        var stored = tokenRepository.findByTenantIdAndId(TENANT_ID, created.tokenId()).orElseThrow();
        stored.setStatus(MaintenanceTokenStatus.EXPIRED);
        stored.setExpiresAt(Instant.now().minusSeconds(120));
        tokenRepository.saveAndFlush(stored);
        entityManager.clear();

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(device),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("MAINTENANCE_TOKEN_DENIED_EXPIRED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void consumeTokenDeviceMismatchAuditSurvivesCallerRollback_noRollbackForRegression() {
        // BE-014A (Codex 019e4ee1 iter-2 REVISE absorb): regression guard
        // for the durability claim. By suspending the @DataJpaTest class-level
        // transaction (NOT_SUPPORTED), each service call runs in its OWN
        // tx. The deny audit is emitted in the same tx as consumeToken;
        // noRollbackFor=ResponseStatusException keeps that tx from rolling
        // back when the 403 throws. If noRollbackFor is removed from
        // consumeToken, this test MUST fail because the deny audit row
        // would not survive the throw.
        //
        // Cleanup: NOT_SUPPORTED means data persists across the test;
        // we use random hostnames + explicit cleanup at the end.
        EndpointDevice first = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "REGRESSION-OWNER"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "REGRESSION-ATTACKER"));
        CreateMaintenanceTokenResponse created = tokenService.createToken(
                adminContext(),
                first.getId(),
                new CreateMaintenanceTokenRequest(MaintenanceAction.UNINSTALL_AGENT, "regression-mismatch", 60)
        );

        assertThatThrownBy(() -> tokenService.consumeToken(
                principal(second),
                new ConsumeMaintenanceTokenRequest(created.token(), "0.3.0")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");

        // Durability assertion outside any test tx: audit row must
        // survive the caller tx's 403 throw. If noRollbackFor is removed
        // from consumeToken's @Transactional, the deny audit would
        // roll back and this assertion would FAIL.
        //
        // (No cleanup: NOT_SUPPORTED means H2 in-memory test class scope
        // persists this test's rows; other tests use random hostnames so
        // there's no collision. @DataJpaTest creates a fresh per-class
        // database.)
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .as("deny audit row must persist past the 403 throw "
                        + "(noRollbackFor=ResponseStatusException invariant)")
                .contains("MAINTENANCE_TOKEN_DENIED_DEVICE_MISMATCH");
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
