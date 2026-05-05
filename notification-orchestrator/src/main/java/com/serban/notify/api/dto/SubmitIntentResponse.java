package com.serban.notify.api.dto;

/**
 * Submit intent response.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code ACCEPTED} — yeni intent kabul (HTTP 202)</li>
 *   <li>{@code REPLAYED} — aynı idempotency_key 24h içinde, original intent_id döndürüldü
 *       (HTTP 202; client safe replay)</li>
 *   <li>{@code DUPLICATE_CONFLICT} — aynı idempotency_key farklı payload/template
 *       (HTTP 409; future when request_hash field eklenir)</li>
 * </ul>
 */
public record SubmitIntentResponse(
    String intentId,
    String status,
    String trackingUrl
) {
    public static SubmitIntentResponse accepted(String intentId) {
        return new SubmitIntentResponse(intentId, "ACCEPTED",
            "/api/v1/notify/intents/" + intentId);
    }

    public static SubmitIntentResponse replayed(String intentId) {
        return new SubmitIntentResponse(intentId, "REPLAYED",
            "/api/v1/notify/intents/" + intentId);
    }
}
