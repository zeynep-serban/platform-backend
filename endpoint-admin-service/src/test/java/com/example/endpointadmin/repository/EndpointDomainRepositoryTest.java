package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceCredential;
import com.example.endpointadmin.model.EndpointRequestNonce;
import com.example.endpointadmin.model.OsType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EndpointDomainRepositoryTest {

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointDeviceCredentialRepository credentialRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointRequestNonceRepository nonceRepository;

    @Test
    void commandIdempotencyKeyIsUniqueInsideTenant() {
        UUID tenantId = UUID.randomUUID();
        EndpointDevice device = deviceRepository.saveAndFlush(device(tenantId, "win-001", "fp-001"));
        EndpointCommand first = command(tenantId, device, "lock-user-001");
        commandRepository.saveAndFlush(first);

        EndpointCommand duplicate = command(tenantId, device, "lock-user-001");

        assertThatThrownBy(() -> commandRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commandIdempotencyKeyIsScopedByTenant() {
        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        EndpointDevice firstDevice = deviceRepository.saveAndFlush(device(tenantOne, "win-001", "fp-001"));
        EndpointDevice secondDevice = deviceRepository.saveAndFlush(device(tenantTwo, "win-001", "fp-001"));

        commandRepository.saveAndFlush(command(tenantOne, firstDevice, "same-key"));
        commandRepository.saveAndFlush(command(tenantTwo, secondDevice, "same-key"));

        assertThat(commandRepository.findByTenantIdAndIdempotencyKey(tenantOne, "same-key")).isPresent();
        assertThat(commandRepository.findByTenantIdAndIdempotencyKey(tenantTwo, "same-key")).isPresent();
    }

    @Test
    void sameNonceCannotBeReusedWithSameCredential() {
        UUID tenantId = UUID.randomUUID();
        EndpointDevice device = deviceRepository.saveAndFlush(device(tenantId, "mac-001", "fp-mac-001"));
        EndpointDeviceCredential credential = credentialRepository.saveAndFlush(credential(device, "cred-001"));
        nonceRepository.saveAndFlush(nonce(device, credential, "nonce-001"));

        EndpointRequestNonce duplicate = nonce(device, credential, "nonce-001");

        assertThatThrownBy(() -> nonceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameNonceCanBeUsedByDifferentCredentials() {
        UUID tenantId = UUID.randomUUID();
        EndpointDevice firstDevice = deviceRepository.saveAndFlush(device(tenantId, "win-001", "fp-001"));
        EndpointDevice secondDevice = deviceRepository.saveAndFlush(device(tenantId, "win-002", "fp-002"));
        EndpointDeviceCredential firstCredential = credentialRepository.saveAndFlush(credential(firstDevice, "cred-001"));
        EndpointDeviceCredential secondCredential = credentialRepository.saveAndFlush(credential(secondDevice, "cred-002"));

        nonceRepository.saveAndFlush(nonce(firstDevice, firstCredential, "shared-nonce"));
        nonceRepository.saveAndFlush(nonce(secondDevice, secondCredential, "shared-nonce"));

        assertThat(nonceRepository.existsByCredential_IdAndNonce(firstCredential.getId(), "shared-nonce")).isTrue();
        assertThat(nonceRepository.existsByCredential_IdAndNonce(secondCredential.getId(), "shared-nonce")).isTrue();
    }

    @Test
    void deviceStatusQueryReturnsTenantScopedOnlineDevices() {
        UUID tenantId = UUID.randomUUID();
        EndpointDevice online = device(tenantId, "win-online", "fp-online");
        online.setStatus(DeviceStatus.ONLINE);
        EndpointDevice offline = device(tenantId, "win-offline", "fp-offline");
        offline.setStatus(DeviceStatus.OFFLINE);
        deviceRepository.saveAndFlush(online);
        deviceRepository.saveAndFlush(offline);

        assertThat(deviceRepository.findByTenantIdAndStatusIn(tenantId, java.util.List.of(DeviceStatus.ONLINE)))
                .extracting(EndpointDevice::getHostname)
                .containsExactly("win-online");
    }

    private EndpointDevice device(UUID tenantId, String hostname, String fingerprint) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname);
        device.setOsType(hostname.startsWith("mac") ? OsType.MACOS : OsType.WINDOWS);
        device.setOsVersion("test-os");
        device.setAgentVersion("0.1.0");
        device.setMachineFingerprint(fingerprint);
        device.setDomainName("corp.local");
        return device;
    }

    private EndpointDeviceCredential credential(EndpointDevice device, String keyId) {
        EndpointDeviceCredential credential = new EndpointDeviceCredential();
        credential.setDevice(device);
        credential.setCredentialKeyId(keyId);
        credential.setEncryptedSecret("vault:v1:" + keyId);
        credential.setEncryptionKeyVersion("v1");
        return credential;
    }

    private EndpointCommand command(UUID tenantId, EndpointDevice device, String idempotencyKey) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.LOCK_USER_LOGIN);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setIssuedBySubject("admin@example.com");
        command.setPayload(Map.of("username", "target.user"));
        return command;
    }

    private EndpointRequestNonce nonce(EndpointDevice device,
                                       EndpointDeviceCredential credential,
                                       String nonceValue) {
        EndpointRequestNonce nonce = new EndpointRequestNonce();
        nonce.setDevice(device);
        nonce.setCredential(credential);
        nonce.setNonce(nonceValue);
        nonce.setRequestTimestamp(Instant.now());
        nonce.setExpiresAt(Instant.now().plusSeconds(300));
        nonce.setRequestHash("sha256:test");
        return nonce;
    }
}
