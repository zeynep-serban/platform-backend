package com.serban.notify.delivery;

import java.util.Map;

/**
 * Delivery target (Codex 019df9ae Q2 PARTIAL absorb).
 *
 * <p>Channel-aware addressing:
 * <ul>
 *   <li>SMTP: recipient-addressed (each recipient = separate target)</li>
 *   <li>Slack/webhook: target-addressed (provider config or routing target =
 *       single target per N recipients)</li>
 * </ul>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code channel}: "email" / "slack" / "webhook" / "sms"</li>
 *   <li>{@code recipientType}: "subscriber" / "external" (audit context)</li>
 *   <li>{@code recipientId}: subscriber_id (subscriber type only)</li>
 *   <li>{@code recipientHash}: HMAC-SHA256 (PII-redacted; for audit)</li>
 *   <li>{@code targetRef}: actual delivery address — email, Slack webhook URL,
 *       webhook target URL</li>
 *   <li>{@code providerKey}: provider config key — "smtp-corporate",
 *       "slack-workspace-1", "webhook-generic"</li>
 *   <li>{@code routingMetadata}: Faz 23.3.2 PR-A3.1 (Codex thread
 *       {@code 019e4514}) — channel-specific routing hints. SMS için
 *       {@code severity / topic_key / template_id} (JetSMS VFO/VF
 *       allowlist). Email/Slack/Webhook için boş ({@code Map.of()}).
 *       Constructor immutable copy yapar.</li>
 * </ul>
 */
public record DeliveryTarget(
    String channel,
    String recipientType,
    String recipientId,
    String recipientHash,
    String targetRef,
    String providerKey,
    Map<String, Object> routingMetadata
) {

    /**
     * Backward-compatible 6-arg constructor — {@code routingMetadata=Map.of()}.
     * Email/Slack/Webhook plan kodu bu signature ile çalışmaya devam eder.
     */
    public DeliveryTarget(String channel, String recipientType, String recipientId,
                          String recipientHash, String targetRef, String providerKey) {
        this(channel, recipientType, recipientId, recipientHash, targetRef, providerKey, Map.of());
    }

    /** Compact constructor — defensive immutable copy. */
    public DeliveryTarget {
        routingMetadata = (routingMetadata == null || routingMetadata.isEmpty())
            ? Map.of()
            : Map.copyOf(routingMetadata);
    }
}
