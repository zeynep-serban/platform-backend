package com.serban.notify.redaction;

import com.serban.notify.api.dto.DeliveryLogResponse;
import com.serban.notify.repository.projection.DeliveryLogRow;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Translate internal {@link DeliveryLogRow} into a redacted
 * {@link DeliveryLogResponse} (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb:
 * <ul>
 *   <li>Raw {@code recipient_id}, {@code claim_token},
 *       {@code processing_lease_until} <b>never</b> appear in the response.</li>
 *   <li>{@code provider_msg_id} masked: prefix preserved, suffix last-4
 *       kept, middle replaced with three asterisks.</li>
 *   <li>{@code failure_reason} mapped to a deterministic
 *       {@code failure_category} (whitelist enum); the raw provider text is
 *       never returned.</li>
 *   <li>{@code failure_summary_redacted} is an i18n-friendly key
 *       (e.g. {@code provider.failure.recipient_rejected}) or
 *       {@code PROVIDER_FAILURE_REDACTED} for unknown categories.</li>
 *   <li>{@code recipient_hash} is org-namespaced HMAC-SHA256 — safe to
 *       surface; the pepper makes reversal infeasible without it.</li>
 * </ul>
 *
 * <p>Failure-reason classification is a tiny, intentionally conservative
 * heuristic — known carrier / SMTP error idioms map to a coarse category;
 * anything else collapses to {@code UNKNOWN} + {@code PROVIDER_FAILURE_REDACTED}.
 * Operators see <i>shape</i>, not provider strings.
 */
@Component
public class DeliveryLogRedactor {

    /** Whitelist of category keys exposed via {@link DeliveryLogResponse#failureCategory()}. */
    public static final Set<String> FAILURE_CATEGORIES = Set.of(
        "PROVIDER_QUOTA",
        "RECIPIENT_REJECTED",
        "RECIPIENT_BLOCKED",
        "INVALID_TARGET",
        "TRANSIENT_NETWORK",
        "AUTH_FAILURE",
        "UNKNOWN"
    );

    /** Mapping from category to deterministic i18n key. */
    private static final Map<String, String> CATEGORY_SUMMARY_KEY = Map.of(
        "PROVIDER_QUOTA", "provider.failure.quota_exceeded",
        "RECIPIENT_REJECTED", "provider.failure.recipient_rejected",
        "RECIPIENT_BLOCKED", "provider.failure.recipient_blocked",
        "INVALID_TARGET", "provider.failure.invalid_target",
        "TRANSIENT_NETWORK", "provider.failure.transient_network",
        "AUTH_FAILURE", "provider.failure.auth_failure",
        "UNKNOWN", "PROVIDER_FAILURE_REDACTED"
    );

    /**
     * Lightweight idiom heuristics — order matters; the first match wins.
     * Keep the patterns broad but case-insensitive; their job is to catch
     * the most common SMS / SMTP error wordings without leaking raw text.
     */
    private static final Pattern P_QUOTA = Pattern.compile(
        "(?i)(quota|rate[- ]?limit|too many|throttl)"
    );
    private static final Pattern P_REJECTED = Pattern.compile(
        "(?i)(rejected|reject|recipient[- ]?refused|user[- ]?refused|550)"
    );
    private static final Pattern P_BLOCKED = Pattern.compile(
        "(?i)(blocked|blacklist|spam|deny|opt[- ]?out)"
    );
    private static final Pattern P_INVALID = Pattern.compile(
        "(?i)(invalid|malformed|not[- ]?found|no such|nonexist)"
    );
    private static final Pattern P_NETWORK = Pattern.compile(
        "(?i)(timeout|timed out|connection|network|unreachable|dns)"
    );
    private static final Pattern P_AUTH = Pattern.compile(
        "(?i)(unauthori[sz]ed|forbidden|invalid[- ]?token|invalid[- ]?credential|401|403)"
    );

    /**
     * Convert {@link DeliveryLogRow} to {@link DeliveryLogResponse}, dropping
     * raw fields and applying the masking / categorization rules above.
     */
    public DeliveryLogResponse toResponse(DeliveryLogRow row) {
        if (row == null) {
            throw new IllegalArgumentException("DeliveryLogRow must not be null");
        }
        String category = classify(row.failureReason());
        String summaryKey = CATEGORY_SUMMARY_KEY.getOrDefault(
            category, "PROVIDER_FAILURE_REDACTED"
        );
        return new DeliveryLogResponse(
            row.deliveryId(),
            row.intentId(),
            row.topicKey(),
            row.correlationId(),
            row.channel(),
            row.provider(),
            maskProviderMsgId(row.providerMsgId()),
            row.recipientType(),
            row.recipientHash(),
            row.status(),
            row.attemptCount(),
            category,
            summaryKey,
            row.lastAttemptAt(),
            row.deliveredAt(),
            row.permanentFailureAt(),
            row.nextRetryAt(),
            row.createdAt(),
            row.updatedAt(),
            row.activityAt()
        );
    }

    /**
     * Mask a provider message id while keeping enough breadcrumb for
     * operators to correlate with provider portals.
     *
     * <ul>
     *   <li>{@code null} or empty → {@code null}</li>
     *   <li>{@code &le; 4} chars → {@code "***"}</li>
     *   <li>otherwise → {@code prefix + "***" + last4}</li>
     * </ul>
     *
     * <p>{@code prefix} is the substring before the first {@code -} (provider
     * tag like {@code netgsm-}, {@code smtp-}); falls back to the first 2
     * characters when no dash separator is present.
     */
    public String maskProviderMsgId(String providerMsgId) {
        if (providerMsgId == null || providerMsgId.isBlank()) {
            return null;
        }
        int len = providerMsgId.length();
        if (len <= 4) {
            return "***";
        }
        int dashIdx = providerMsgId.indexOf('-');
        String prefix;
        String tail;
        if (dashIdx > 0 && dashIdx < len - 4) {
            prefix = providerMsgId.substring(0, dashIdx + 1);
            tail = providerMsgId.substring(len - 4);
        } else {
            int prefixLen = Math.min(2, len - 4);
            prefix = providerMsgId.substring(0, prefixLen);
            tail = providerMsgId.substring(len - 4);
        }
        return prefix + "***" + tail;
    }

    /**
     * Classify a raw failure reason into one of {@link #FAILURE_CATEGORIES}.
     */
    public String classify(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return "UNKNOWN";
        }
        String lower = failureReason.toLowerCase(Locale.ROOT);
        if (P_AUTH.matcher(lower).find()) return "AUTH_FAILURE";
        if (P_QUOTA.matcher(lower).find()) return "PROVIDER_QUOTA";
        if (P_BLOCKED.matcher(lower).find()) return "RECIPIENT_BLOCKED";
        if (P_REJECTED.matcher(lower).find()) return "RECIPIENT_REJECTED";
        if (P_INVALID.matcher(lower).find()) return "INVALID_TARGET";
        if (P_NETWORK.matcher(lower).find()) return "TRANSIENT_NETWORK";
        return "UNKNOWN";
    }

    /** Stable redaction policy identifier carried in list responses. */
    public String redactionPolicy() {
        return "v1";
    }
}
