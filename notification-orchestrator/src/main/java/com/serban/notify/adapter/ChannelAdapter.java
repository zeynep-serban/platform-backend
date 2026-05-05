package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;

/**
 * Channel adapter interface — Faz 23.1 PR3 (Codex 019df9ae Q1 + Q2 absorb).
 *
 * <p>Channel implementations (PR3 scope):
 * <ul>
 *   <li>SMTP — recipient-addressed (her recipient delivery row)</li>
 *   <li>Slack incoming webhook — target-addressed (provider/routing target başına)</li>
 *   <li>Webhook egress — target-addressed</li>
 * </ul>
 *
 * <p>PR3 scope (Codex Q1 REVISE):
 * <ul>
 *   <li>Adapter implementation tam, runtime call **internal-only**
 *       (DeliveryDispatchService direct invoke; no scheduled worker)</li>
 *   <li>Submit endpoint auto-dispatch YOK</li>
 *   <li>Worker PR4'te (OutboxPoller + RetryWorker)</li>
 * </ul>
 */
public interface ChannelAdapter {

    /**
     * Channel identifier (matches notification_delivery.channel column).
     * Examples: "email", "slack", "webhook".
     */
    String channelKey();

    /**
     * Send rendered message to delivery target via this channel.
     *
     * <p>Codex Q1: This method is invoked by {@code DeliveryDispatchService}
     * (internal direct-invoke); not by submit endpoint or scheduled worker
     * in PR3 scope.
     *
     * @param target delivery target (recipient or routing target ref)
     * @param message rendered subject + body parts
     * @return delivery result with provider message ID + status
     */
    DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message);

    /**
     * Delivery attempt result (immutable record).
     *
     * <p>Status semantics:
     * <ul>
     *   <li>{@code DELIVERED} — provider 2xx, message accepted</li>
     *   <li>{@code FAILED} — provider 4xx (permanent client error, no retry)</li>
     *   <li>{@code RETRY} — provider 5xx or timeout (transient, PR4 worker retries)</li>
     *   <li>{@code BOUNCED} — email-specific (hard bounce, no retry)</li>
     * </ul>
     */
    record DeliveryAttemptResult(
        Status status,
        String providerMessageId,
        String failureReason,
        Integer providerResponseCode
    ) {
        public enum Status {
            DELIVERED, FAILED, RETRY, BOUNCED
        }

        public static DeliveryAttemptResult delivered(String providerMsgId) {
            return new DeliveryAttemptResult(Status.DELIVERED, providerMsgId, null, null);
        }

        public static DeliveryAttemptResult failed(String reason, Integer code) {
            return new DeliveryAttemptResult(Status.FAILED, null, reason, code);
        }

        public static DeliveryAttemptResult retry(String reason, Integer code) {
            return new DeliveryAttemptResult(Status.RETRY, null, reason, code);
        }

        public static DeliveryAttemptResult bounced(String reason) {
            return new DeliveryAttemptResult(Status.BOUNCED, null, reason, null);
        }
    }
}
