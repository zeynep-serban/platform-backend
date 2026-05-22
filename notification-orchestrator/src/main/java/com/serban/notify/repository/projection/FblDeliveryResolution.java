package com.serban.notify.repository.projection;

import com.serban.notify.domain.NotificationDelivery;

/**
 * FBL delivery correlation projection (Faz 23.8 M7 T4.3.5, Codex 019e4fc6).
 *
 * <p>An ARF spam-complaint report does not carry a platform {@code org_id}.
 * The FBL service resolves the tenant + recipient identity by correlating
 * the ARF original-message {@code Message-ID} / {@code X-Notify-Message-ID}
 * against {@code notification_delivery.provider_msg_id}, then joining
 * {@code notification_intent} for the {@code org_id}.
 *
 * <p>Codex 019e4fc6 critical rule: the resolved {@code recipientHash} comes
 * straight from {@code notification_delivery.recipient_hash} — it MUST NOT
 * be recomputed from the ARF original recipient address, because subscriber
 * email dispatch hashes the {@code subscriberId} while external email
 * dispatch hashes the email address; recomputation would break suppression
 * matching.
 *
 * <p>Spring Data JPA interface projection — getter return types match the
 * underlying entity attribute types ({@code recipientType} is the
 * {@link NotificationDelivery.RecipientType} enum).
 */
public interface FblDeliveryResolution {

    /** Tenant org_id (from notification_intent). */
    String getOrgId();

    /** Authoritative recipient_hash (from notification_delivery — do NOT recompute). */
    String getRecipientHash();

    /** Recipient type (SUBSCRIBER | EXTERNAL | CHANNEL). */
    NotificationDelivery.RecipientType getRecipientType();

    /** Correlated intent_id (audit linkage). */
    String getIntentId();

    /** Delivery channel (expected "email" for FBL). */
    String getChannel();
}
