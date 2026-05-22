package com.serban.notify.fbl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * ARF event fingerprint — idempotency key for the {@code email_bounce_event}
 * dedupe ledger (Faz 23.8 M7 T4.3.5 FBL — Codex 019e4fc6).
 *
 * <p><b>Plain SHA-256, NOT HMAC.</b> The fingerprint is an idempotency key
 * over report/correlator metadata — reporter + report Message-ID + original
 * Message-ID — none of which is recipient identity. The HMAC-with-pepper
 * hard rule applies only to {@code recipient_hash} (PII). Using a peppered
 * HMAC here would mean a pepper rotation silently changes dedupe behaviour
 * and the same ARF report could be processed twice. Plain SHA-256 keeps the
 * fingerprint stable for the lifetime of the report.
 *
 * <p>Format: {@code SHA-256( "arf:v1|" + reporter + "|" + reportMessageId +
 * "|" + originalMessageId )}, lowercase hex. Inputs are normalised:
 * trimmed, lower-cased, Message-IDs stripped of angle brackets so
 * {@code <abc@x>} and {@code abc@x} fingerprint identically.
 */
public final class FblFingerprint {

    private static final String PREFIX = "arf:v1|";

    private FblFingerprint() {
    }

    /**
     * Compute the idempotency fingerprint. Null inputs normalise to empty
     * string — the caller is responsible for ensuring enough stable
     * identity is present (a report with no reporter and no message-ids
     * is rejected upstream as unparseable).
     */
    public static String compute(String reporter,
                                 String reportMessageId,
                                 String originalMessageId) {
        String input = PREFIX
            + normalizePlain(reporter) + "|"
            + normalizeMessageId(reportMessageId) + "|"
            + normalizeMessageId(originalMessageId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Trim + lowercase (Locale.ROOT). */
    static String normalizePlain(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Trim + strip a single pair of angle brackets + lowercase. */
    static String normalizeMessageId(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
            && trimmed.startsWith("<") && trimmed.endsWith(">")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
