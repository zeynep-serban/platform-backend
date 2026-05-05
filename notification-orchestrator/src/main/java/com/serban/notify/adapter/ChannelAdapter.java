package com.serban.notify.adapter;

import com.serban.notify.domain.NotificationIntent;

/**
 * Channel adapter interface (Faz 23.1 D38 baseline).
 *
 * Each channel (email/sms/in-app/slack/webhook/push) implements this interface.
 * Provider abstraction: SmtpAdapter (SendGrid/Mailgun/Postal/corporate),
 * SmsAdapter (NetGsm/IletiMerkezi), SlackAdapter (incoming webhook),
 * WebhookAdapter (HMAC signed), InAppAdapter (DB-backed inbox), etc.
 *
 * Codex thread 019df86f Q3 absorb — TR SMS native Java (NOT TS plugin).
 *
 * Detail: platform-k8s-gitops/docs/adr/0013-notification-orchestration.md §D38
 */
public interface ChannelAdapter {

    /**
     * Channel identifier (matches notification_delivery.channel column).
     * Examples: "email", "sms", "in-app", "slack", "webhook", "push-fcm".
     */
    String channelKey();

    /**
     * Send notification via this channel.
     *
     * @param intent  the source intent
     * @param recipient target recipient (subscriber or external)
     * @param renderedSubject template-rendered subject (email) or null
     * @param renderedBody template-rendered body
     * @return delivery result with provider message ID + status
     */
    DeliveryAttemptResult send(
        NotificationIntent intent,
        Recipient recipient,
        String renderedSubject,
        String renderedBody
    );

    /**
     * Recipient model — abstracts subscriber vs external.
     */
    record Recipient(
        Type type,
        String subscriberId,
        String email,
        String phone,
        String name,
        String locale
    ) {
        public enum Type { SUBSCRIBER, EXTERNAL }
    }

    /**
     * Delivery attempt result.
     */
    record DeliveryAttemptResult(
        Status status,
        String providerMessageId,
        String failureReason
    ) {
        public enum Status {
            DELIVERED, FAILED, BOUNCED, RETRY,
            BLOCKED_BY_PREFERENCE, BLOCKED_BY_AUTHZ, BLOCKED_BY_IDEMPOTENCY,
            BLOCKED_EXTERNAL_NOT_ALLOWED
        }

        public static DeliveryAttemptResult delivered(String providerMsgId) {
            return new DeliveryAttemptResult(Status.DELIVERED, providerMsgId, null);
        }

        public static DeliveryAttemptResult failed(String reason) {
            return new DeliveryAttemptResult(Status.FAILED, null, reason);
        }

        public static DeliveryAttemptResult retry(String reason) {
            return new DeliveryAttemptResult(Status.RETRY, null, reason);
        }
    }
}
