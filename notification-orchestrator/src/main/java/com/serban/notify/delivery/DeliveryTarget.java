package com.serban.notify.delivery;

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
 *   <li>{@code channel}: "email" / "slack" / "webhook"</li>
 *   <li>{@code recipientType}: "subscriber" / "external" (audit context)</li>
 *   <li>{@code recipientId}: subscriber_id (subscriber type only)</li>
 *   <li>{@code recipientHash}: HMAC-SHA256 (PII-redacted; for audit)</li>
 *   <li>{@code targetRef}: actual delivery address — email, Slack webhook URL,
 *       webhook target URL</li>
 *   <li>{@code providerKey}: provider config key — "smtp-corporate",
 *       "slack-workspace-1", "webhook-generic"</li>
 * </ul>
 */
public record DeliveryTarget(
    String channel,
    String recipientType,
    String recipientId,
    String recipientHash,
    String targetRef,
    String providerKey
) {}
