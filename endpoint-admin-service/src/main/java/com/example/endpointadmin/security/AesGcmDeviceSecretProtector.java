package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesGcmDeviceSecretProtector implements DeviceSecretProtector {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec secretKey;
    private final String keyVersion;

    public AesGcmDeviceSecretProtector(
            @Value("${endpoint-admin.secrets.encryption-key:}") String encryptionKey,
            @Value("${endpoint-admin.secrets.encryption-key-version:local-v1}") String keyVersion,
            Environment environment
    ) {
        String keyMaterial = encryptionKey == null || encryptionKey.isBlank()
                ? fallbackKeyMaterial(environment)
                : encryptionKey;
        this.secretKey = new SecretKeySpec(normalizeKey(keyMaterial), "AES");
        this.keyVersion = keyVersion == null || keyVersion.isBlank() ? "v1" : keyVersion;
    }

    @Override
    public ProtectedDeviceSecret protect(String plainSecret) {
        if (plainSecret == null || plainSecret.isBlank()) {
            throw new IllegalArgumentException("Device secret is required.");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));
            String encoded = "aes-gcm:" + keyVersion + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(cipherText);
            return new ProtectedDeviceSecret(encoded, keyVersion);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Device secret could not be encrypted.", ex);
        }
    }

    @Override
    public String reveal(String encryptedSecret) {
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            throw new IllegalArgumentException("Encrypted device secret is required.");
        }
        String[] parts = encryptedSecret.split(":");
        if (parts.length != 4 || !"aes-gcm".equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported encrypted secret format.");
        }
        try {
            byte[] iv = Base64.getUrlDecoder().decode(parts[2]);
            byte[] cipherText = Base64.getUrlDecoder().decode(parts[3]);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Device secret could not be decrypted.", ex);
        }
    }

    private static byte[] normalizeKey(String keyMaterial) {
        if (keyMaterial.startsWith("base64:")) {
            byte[] decoded = Base64.getDecoder().decode(keyMaterial.substring("base64:".length()));
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        }
        return sha256(keyMaterial.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static String fallbackKeyMaterial(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return "endpoint-admin-local-dev-device-secret-key";
            }
        }
        throw new IllegalStateException("endpoint-admin.secrets.encryption-key must be configured outside local/dev/test.");
    }
}
