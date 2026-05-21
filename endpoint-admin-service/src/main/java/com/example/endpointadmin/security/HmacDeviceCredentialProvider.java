package com.example.endpointadmin.security;

import com.example.endpointadmin.model.EndpointDeviceCredential;
import com.example.endpointadmin.model.EndpointRequestNonce;
import com.example.endpointadmin.repository.EndpointDeviceCredentialRepository;
import com.example.endpointadmin.repository.EndpointRequestNonceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class HmacDeviceCredentialProvider implements DeviceCredentialProvider {

    private final EndpointDeviceCredentialRepository credentialRepository;
    private final EndpointRequestNonceRepository nonceRepository;
    private final DeviceSecretProtector secretProtector;
    private final Clock clock;
    private final Duration timestampWindow;
    private final Duration nonceTtl;

    public HmacDeviceCredentialProvider(EndpointDeviceCredentialRepository credentialRepository,
                                        EndpointRequestNonceRepository nonceRepository,
                                        DeviceSecretProtector secretProtector,
                                        Clock clock,
                                        @Value("${endpoint-admin.agent-auth.timestamp-window-seconds:300}") long timestampWindowSeconds,
                                        @Value("${endpoint-admin.agent-auth.nonce-ttl-seconds:600}") long nonceTtlSeconds) {
        this.credentialRepository = credentialRepository;
        this.nonceRepository = nonceRepository;
        this.secretProtector = secretProtector;
        this.clock = clock;
        this.timestampWindow = Duration.ofSeconds(Math.max(30L, timestampWindowSeconds));
        this.nonceTtl = Duration.ofSeconds(Math.max(60L, nonceTtlSeconds));
    }

    @Override
    public String providerId() {
        return "hmac-sha256";
    }

    @Override
    @Transactional
    public DeviceCredentialResult authenticate(DeviceCredentialRequest request) {
        validateRequestShape(request);
        Instant now = Instant.now(clock);
        validateTimestamp(request.requestTimestamp(), now);

        EndpointDeviceCredential credential = credentialRepository.findByCredentialKeyIdAndActiveTrue(request.keyId())
                .orElseThrow(() -> unauthorized("DEVICE_CREDENTIAL_NOT_FOUND", "Device credential was not found."));

        if (!matchesAnyAcceptedSecret(credential, request, now)) {
            throw unauthorized("DEVICE_SIGNATURE_INVALID", "Device request signature is invalid.");
        }

        EndpointRequestNonce nonce = new EndpointRequestNonce();
        nonce.setCredential(credential);
        nonce.setDevice(credential.getDevice());
        nonce.setNonce(request.nonce());
        nonce.setRequestTimestamp(request.requestTimestamp());
        nonce.setExpiresAt(now.plus(nonceTtl));
        nonce.setRequestHash(HmacSignatureSupport.sha256Hex(request.canonicalPayload()));
        try {
            nonceRepository.saveAndFlush(nonce);
        } catch (DataIntegrityViolationException ex) {
            throw unauthorized("DEVICE_NONCE_REPLAY", "Device request nonce was already used.");
        }

        return new DeviceCredentialResult(
                credential.getDevice().getId().toString(),
                credential.getId().toString(),
                now
        );
    }

    private void validateRequestShape(DeviceCredentialRequest request) {
        if (request == null
                || isBlank(request.keyId())
                || isBlank(request.signature())
                || request.requestTimestamp() == null
                || isBlank(request.nonce())
                || isBlank(request.canonicalPayload())) {
            throw unauthorized("DEVICE_CREDENTIAL_MISSING", "Device credential headers are required.");
        }
        if (request.nonce().length() > 255) {
            throw unauthorized("DEVICE_NONCE_INVALID", "Device request nonce is too long.");
        }
    }

    private void validateTimestamp(Instant requestTimestamp, Instant now) {
        Instant earliest = now.minus(timestampWindow);
        Instant latest = now.plus(timestampWindow);
        if (requestTimestamp.isBefore(earliest) || requestTimestamp.isAfter(latest)) {
            throw unauthorized("DEVICE_TIMESTAMP_OUT_OF_WINDOW", "Device request timestamp is outside the accepted window.");
        }
    }

    private boolean matchesAnyAcceptedSecret(EndpointDeviceCredential credential,
                                             DeviceCredentialRequest request,
                                             Instant now) {
        if (matchesSecret(credential.getEncryptedSecret(), request)) {
            return true;
        }
        if (credential.getPreviousEncryptedSecret() == null
                || credential.getRotationGraceUntil() == null
                || credential.getRotationGraceUntil().isBefore(now)) {
            return false;
        }
        return matchesSecret(credential.getPreviousEncryptedSecret(), request);
    }

    private boolean matchesSecret(String encryptedSecret, DeviceCredentialRequest request) {
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            return false;
        }
        String plainSecret = secretProtector.reveal(encryptedSecret);
        return HmacSignatureSupport.matches(request.signature(), plainSecret, request.canonicalPayload());
    }

    private DeviceCredentialException unauthorized(String code, String message) {
        return new DeviceCredentialException(code, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
