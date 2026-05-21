package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceCredential;
import com.example.endpointadmin.repository.EndpointDeviceCredentialRepository;
import com.example.endpointadmin.security.DeviceSecretGenerator;
import com.example.endpointadmin.security.DeviceSecretProtector;
import com.example.endpointadmin.security.ProtectedDeviceSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class DeviceCredentialService {

    private final EndpointDeviceCredentialRepository repository;
    private final DeviceSecretGenerator secretGenerator;
    private final DeviceSecretProtector secretProtector;
    private final Duration rotationGrace;

    public DeviceCredentialService(EndpointDeviceCredentialRepository repository,
                                   DeviceSecretGenerator secretGenerator,
                                   DeviceSecretProtector secretProtector,
                                   @Value("${endpoint-admin.secrets.rotation-grace-hours:24}") long rotationGraceHours) {
        this.repository = repository;
        this.secretGenerator = secretGenerator;
        this.secretProtector = secretProtector;
        this.rotationGrace = Duration.ofHours(Math.max(1L, rotationGraceHours));
    }

    public IssuedDeviceCredential issueCredential(EndpointDevice device, Instant now) {
        String plainSecret = secretGenerator.generate();
        ProtectedDeviceSecret protectedSecret = secretProtector.protect(plainSecret);
        String credentialKeyId = "edc_" + UUID.randomUUID();

        EndpointDeviceCredential credential = repository.findByDevice_Id(device.getId())
                .orElseGet(EndpointDeviceCredential::new);
        if (credential.getId() == null) {
            credential.setDevice(device);
        } else {
            credential.setPreviousEncryptedSecret(credential.getEncryptedSecret());
            credential.setRotationGraceUntil(now.plus(rotationGrace));
            credential.setRotatedAt(now);
            credential.setRevokedAt(null);
        }
        credential.setCredentialKeyId(credentialKeyId);
        credential.setEncryptedSecret(protectedSecret.encryptedSecret());
        credential.setEncryptionKeyVersion(protectedSecret.encryptionKeyVersion());
        credential.setActive(true);
        repository.saveAndFlush(credential);

        return new IssuedDeviceCredential(credentialKeyId, plainSecret);
    }
}
