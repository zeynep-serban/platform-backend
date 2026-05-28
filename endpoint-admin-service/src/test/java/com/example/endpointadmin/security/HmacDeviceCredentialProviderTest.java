package com.example.endpointadmin.security;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceCredential;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceCredentialRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointRequestNonceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        TimeConfig.class,
        HmacDeviceCredentialProvider.class,
        EndpointRequestNonceCleanupJob.class,
        AesGcmDeviceSecretProtector.class
})
// BE-021 PR #319 follow-up to the BE-021 PR #317 workaround:
// application-test.yml uses jdbc:h2:mem:endpointadmin;DB_CLOSE_DELAY=-1,
// a single in-memory H2 instance shared across every @DataJpaTest class
// in the JVM. When any earlier test class tears down its @DataJpaTest
// context, ddl-auto=create-drop DROPS every table from that shared
// instance — and the next class that boots a context (even with
// @DirtiesContext BEFORE_CLASS) inherits the now-empty database without
// re-running schema generation, because Spring's cached EntityManager
// factory's create-drop hook is run only once at context init, not at
// first query. PR #317's BEFORE_CLASS workaround was insufficient
// (CI red on main commits 79a5ae82 and 305561df).
//
// Permanent fix: pin this class's datasource to its own unique in-
// memory H2 instance via DynamicPropertySource. No other test class
// can drop its schema, regardless of execution order or Surefire
// reuse policy.
class HmacDeviceCredentialProviderTest {

    @DynamicPropertySource
    static void registerIsolatedH2(DynamicPropertyRegistry registry) {
        String dbName = "hmac-device-creds-" + UUID.randomUUID();
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + dbName
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;"
                        + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }


    @Autowired
    private HmacDeviceCredentialProvider provider;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointDeviceCredentialRepository credentialRepository;

    @Autowired
    private EndpointRequestNonceRepository nonceRepository;

    @Autowired
    private EndpointRequestNonceCleanupJob nonceCleanupJob;

    @Autowired
    private AesGcmDeviceSecretProtector secretProtector;

    @Test
    void authenticateValidSignatureStoresNonce() {
        EndpointDeviceCredential credential = saveCredential("edc-valid", "secret-current");
        DeviceCredentialRequest request = signedRequest("edc-valid", "secret-current", "nonce-1", Instant.now());

        DeviceCredentialResult result = provider.authenticate(request);

        assertThat(result.deviceId()).isEqualTo(credential.getDevice().getId().toString());
        assertThat(result.credentialId()).isEqualTo(credential.getId().toString());
        assertThat(nonceRepository.existsByCredential_IdAndNonce(credential.getId(), "nonce-1")).isTrue();
    }

    @Test
    void authenticateRejectsNonceReplay() {
        saveCredential("edc-replay", "secret-current");
        DeviceCredentialRequest request = signedRequest("edc-replay", "secret-current", "nonce-2", Instant.now());
        provider.authenticate(request);

        assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOf(DeviceCredentialException.class)
                .hasMessage("Device request nonce was already used.")
                .extracting("errorCode")
                .isEqualTo("DEVICE_NONCE_REPLAY");
    }

    @Test
    void authenticateAcceptsPreviousSecretInsideRotationGrace() {
        EndpointDevice device = saveDevice();
        EndpointDeviceCredential credential = new EndpointDeviceCredential();
        credential.setDevice(device);
        credential.setCredentialKeyId("edc-previous");
        credential.setEncryptedSecret(secretProtector.protect("new-secret").encryptedSecret());
        credential.setPreviousEncryptedSecret(secretProtector.protect("old-secret").encryptedSecret());
        credential.setEncryptionKeyVersion("local-v1");
        credential.setRotationGraceUntil(Instant.now().plusSeconds(3600));
        credentialRepository.saveAndFlush(credential);

        DeviceCredentialResult result = provider.authenticate(
                signedRequest("edc-previous", "old-secret", "nonce-previous", Instant.now())
        );

        assertThat(result.deviceId()).isEqualTo(device.getId().toString());
    }

    @Test
    void authenticateRejectsTimestampOutsideWindow() {
        saveCredential("edc-old", "secret-current");
        DeviceCredentialRequest request = signedRequest(
                "edc-old",
                "secret-current",
                "nonce-old",
                Instant.now().minusSeconds(3600)
        );

        assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOf(DeviceCredentialException.class)
                .extracting("errorCode")
                .isEqualTo("DEVICE_TIMESTAMP_OUT_OF_WINDOW");
    }

    @Test
    void cleanupDeletesExpiredNoncesOnly() {
        EndpointDeviceCredential credential = saveCredential("edc-cleanup", "secret-current");
        saveNonce(credential, "nonce-expired", Instant.now().minusSeconds(60));
        saveNonce(credential, "nonce-active", Instant.now().plusSeconds(60));

        int deleted = nonceCleanupJob.deleteExpiredNonces();

        assertThat(deleted).isEqualTo(1);
        assertThat(nonceRepository.existsByCredential_IdAndNonce(credential.getId(), "nonce-expired")).isFalse();
        assertThat(nonceRepository.existsByCredential_IdAndNonce(credential.getId(), "nonce-active")).isTrue();
    }

    private EndpointDeviceCredential saveCredential(String keyId, String secret) {
        EndpointDeviceCredential credential = new EndpointDeviceCredential();
        credential.setDevice(saveDevice());
        credential.setCredentialKeyId(keyId);
        credential.setEncryptedSecret(secretProtector.protect(secret).encryptedSecret());
        credential.setEncryptionKeyVersion("local-v1");
        return credentialRepository.saveAndFlush(credential);
    }

    private EndpointDevice saveDevice() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(UUID.randomUUID());
        device.setHostname("PC-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        return deviceRepository.saveAndFlush(device);
    }

    private void saveNonce(EndpointDeviceCredential credential, String nonceValue, Instant expiresAt) {
        com.example.endpointadmin.model.EndpointRequestNonce nonce =
                new com.example.endpointadmin.model.EndpointRequestNonce();
        nonce.setCredential(credential);
        nonce.setDevice(credential.getDevice());
        nonce.setNonce(nonceValue);
        nonce.setRequestTimestamp(Instant.now());
        nonce.setExpiresAt(expiresAt);
        nonce.setRequestHash("request-hash-" + nonceValue);
        nonceRepository.saveAndFlush(nonce);
    }

    private DeviceCredentialRequest signedRequest(String keyId, String secret, String nonce, Instant timestamp) {
        String canonical = HmacSignatureSupport.canonicalPayload(
                "POST",
                "/api/v1/agent/heartbeat",
                "",
                timestamp.toString(),
                nonce,
                HmacSignatureSupport.sha256Hex("{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        String signature = HmacSignatureSupport.hmacSha256Base64Url(secret, canonical);
        return new DeviceCredentialRequest(null, keyId, signature, timestamp, nonce, canonical);
    }
}
