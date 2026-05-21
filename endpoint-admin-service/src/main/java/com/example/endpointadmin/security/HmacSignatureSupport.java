package com.example.endpointadmin.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public final class HmacSignatureSupport {

    public static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private HmacSignatureSupport() {
    }

    public static String canonicalPayload(String method,
                                          String path,
                                          String query,
                                          String timestamp,
                                          String nonce,
                                          String bodySha256Hex) {
        return String.join("\n",
                nullToEmpty(method).toUpperCase(),
                nullToEmpty(path),
                nullToEmpty(query),
                nullToEmpty(timestamp),
                nullToEmpty(nonce),
                nullToEmpty(bodySha256Hex).toLowerCase()
        );
    }

    public static byte[] hmacSha256(String secret, String canonicalPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available.", ex);
        }
    }

    public static String hmacSha256Base64Url(String secret, String canonicalPayload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(secret, canonicalPayload));
    }

    public static boolean matches(String providedSignature, String secret, String canonicalPayload) {
        if (providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        byte[] expected = hmacSha256(secret, canonicalPayload);
        byte[] provided = decodeSignature(providedSignature);
        return provided != null && MessageDigest.isEqual(expected, provided);
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decodeSignature(String value) {
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "sha256=", 0, "sha256=".length())) {
            normalized = normalized.substring("sha256=".length());
        }
        try {
            return Base64.getUrlDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            try {
                return HEX.parseHex(normalized);
            } catch (IllegalArgumentException ignoredAgain) {
                return null;
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
