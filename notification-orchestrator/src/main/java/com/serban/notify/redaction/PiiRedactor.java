package com.serban.notify.redaction;

import com.serban.notify.config.NotifyConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PII redaction utility (Faz 23.1 PR2 — ADR-0013 D42 + D46 #7 must-have).
 *
 * <p>Codex 019df9ae Q4 REVISE absorb:
 * <ul>
 *   <li>Recipient hash: <b>HMAC-SHA256</b> with Vault pepper (config-injected),
 *       NOT plain SHA-256 (dictionary attack resistance)</li>
 *   <li>Normalization: NFKC unicode + IDNA punycode + Locale.ROOT lowercase
 *       (NOT provider-specific gmail dot/plus normalization — yanlış merge riski)</li>
 *   <li>Phone E.164 strict (+ + 8-15 digit) — libphonenumber Faz 23.3 SMS</li>
 *   <li>Audit details payload <b>whitelist</b> (NOT blacklist) — sadece
 *       template metadata + hash; payload value asla audit'e girmez</li>
 * </ul>
 */
@Component
public class PiiRedactor {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SUBSCRIBER_TYPE = "subscriber";
    private static final String EXTERNAL_TYPE = "external";

    /**
     * Whitelist set — only these keys may appear in audit_event.details.
     * Codex non-neg: blacklist filter has accidental-leak risk; whitelist
     * is fail-safe.
     */
    private static final Set<String> AUDIT_DETAILS_WHITELIST = Set.of(
        "template_id",
        "template_version",
        "template_locale",
        "recipient_hash",
        "recipient_type",
        "channel",
        "channels",                  // Codex post-impl bulgu #1: intent-level channels list
        "delivery_status",
        "provider_msg_id",
        "failure_reason",
        "delivery_latency_ms",
        "attempt_count",
        "blocked_reason",
        "correlation_id",
        "data_classification",
        "severity",
        "topic_key",
        "org_id",
        // Faz 23.2 PR-B iter-1 absorb: KVKK erasure audit fields (Codex Q3+P1)
        "erasure_reason",            // KVKK §11 reason (subject_request, expired_consent)
        "evidence_ref",              // ticket/letter/audit operator reference
        "subscriber_id",             // erasure target (NOT email/phone — pseudonymous boundary)
        "deliveries_anonymized",     // count of recipient_id null'lanan delivery row
        "inbox_rows_deleted",        // Faz 23.3 PR-E.1 (Codex iter-2 P2 absorb): inbox hard delete count
        "provider_code",             // Faz 23.4 PR-F: DLR provider terminal code (00/04/05/16/17/70)
        "dlr_state_mutated",         // Faz 23.4 PR-F: did DLR cause delivery row state transition
        "dlr_ignored_reason",        // Faz 23.4 PR-F: terminal-conflict / unknown / pre-dispatch
        "delivery_id_long",          // Faz 23.4 PR-F: numeric delivery id for DLR audit linkage
        "policy",                    // PR5 absorb: BLOCKED_* policy identifier
        "reason",                    // PR5 absorb: human-readable detail
        "status",                    // PR5 absorb: BLOCKED_* status
        "dlq_reason",                // PR4 absorb: max_attempts | expired
        "delivery_id",               // PR4 absorb: numeric id (not PII)
        "last_failure_reason",       // PR4 absorb: provider error string
        "provider",                  // PR3 absorb: provider key
        "provider_response_code",    // PR3 absorb: HTTP / SMTP code
        "expire_at",                 // PR4 absorb: intent expiration
        "deleted_count"              // Faz 23.6 PR-A1: PREFERENCE_RESTORE_DEFAULTS audit detail
    );

    private final NotifyConfig.RedactionConfig redactionConfig;

    public PiiRedactor(NotifyConfig notifyConfig) {
        this.redactionConfig = notifyConfig.redaction();
    }

    /**
     * Compute HMAC-SHA256 recipient hash with org namespacing.
     *
     * <p>Format: {@code HMAC(pepper, orgId | RS | recipientType | RS | normalized)}
     * where RS = ASCII 0x1F (record separator, prevents collision via
     * unambiguous delimiter).
     *
     * @param orgId platform org_id (cross-tenant correlation prevention)
     * @param recipientType "subscriber" or "external"
     * @param recipient subscriber_id or email or phone
     * @return 64-char lowercase hex SHA-256 HMAC
     */
    public String hashRecipient(String orgId, String recipientType, String recipient) {
        if (orgId == null || recipientType == null || recipient == null) {
            throw new IllegalArgumentException("hashRecipient: orgId/type/recipient required");
        }
        String normalized = normalize(recipientType, recipient);
        String input = orgId + "\u001F" + recipientType + "\u001F" + normalized;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                redactionConfig.pepper().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM
            ));
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " unavailable", e);
        } catch (java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC key init failed", e);
        }
    }

    /**
     * Normalize recipient for consistent hashing.
     *
     * <ul>
     *   <li>Email: NFKC unicode normalize → trim → IDN ASCII (punycode for
     *       international domain) → split @ → local part Locale.ROOT lowercase
     *       + domain Locale.ROOT lowercase. NO gmail dot/plus removal
     *       (provider-specific, accidental merge risk).</li>
     *   <li>Phone: E.164 strict format expected — already validated at DTO.
     *       Just trim.</li>
     *   <li>Subscriber: trim only (numeric ID).</li>
     * </ul>
     */
    private String normalize(String recipientType, String recipient) {
        String trimmed = Normalizer.normalize(recipient.trim(), Normalizer.Form.NFKC);
        if (EXTERNAL_TYPE.equals(recipientType)) {
            // Phone (E.164) or email distinction
            if (trimmed.startsWith("+")) {
                return trimmed;  // E.164 strict — already validated
            }
            // Email
            int atIdx = trimmed.indexOf('@');
            if (atIdx <= 0 || atIdx == trimmed.length() - 1) {
                return trimmed.toLowerCase(Locale.ROOT);  // fallback non-email, lowercase
            }
            String localPart = trimmed.substring(0, atIdx).toLowerCase(Locale.ROOT);
            String domainPart = trimmed.substring(atIdx + 1).toLowerCase(Locale.ROOT);
            try {
                domainPart = IDN.toASCII(domainPart);
            } catch (IllegalArgumentException e) {
                // Invalid IDN, keep as-is
            }
            return localPart + "@" + domainPart;
        }
        return trimmed;  // subscriber id, no transformation beyond NFKC + trim
    }

    /**
     * Filter audit details Map to whitelist set only (Codex Q4 REVISE absorb).
     *
     * <p>Returns immutable Map containing only WHITELIST keys present in input.
     * Any other key (especially payload variables) silently dropped — fail-safe
     * pattern; PII never accidentally leaks into audit_event.details.
     */
    public Map<String, Object> filterAuditDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        return details.entrySet().stream()
            .filter(e -> AUDIT_DETAILS_WHITELIST.contains(e.getKey()))
            .filter(e -> e.getValue() != null)
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, Map.Entry::getValue
            ));
    }

    /** Whitelist set exposed for tests. */
    public static Set<String> auditDetailsWhitelist() {
        return AUDIT_DETAILS_WHITELIST;
    }
}
