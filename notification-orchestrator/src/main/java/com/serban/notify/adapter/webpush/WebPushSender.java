package com.serban.notify.adapter.webpush;

/**
 * WebPushSender — push service POST facade (Faz 23.7 M7 T4.2 PR-W2.2
 * scaffold; real {@code nl.martijndwars:web-push} library integration
 * PR-W2.3 scope).
 *
 * <p>Interface boundary: {@link WebPushAdapter} status mapping +
 * endpoint cleanup için bu kontratı çağırır. Real implementation lib
 * facade {@code DefaultWebPushSender} (PR-W2.3'te eklenecek) Notification
 * + Subscription instantiate edip {@code PushService.send} invoke eder.
 *
 * <p>Test'lerde {@code Mockito.mock(WebPushSender.class)} ile inject
 * edilir; gerçek HTTP traffic yapılmadan adapter logic doğrulanır.
 */
public interface WebPushSender {

    /**
     * Send encrypted payload to push endpoint.
     *
     * @param endpointUrl  push service endpoint (FCM/Mozilla/Edge)
     * @param p256dhKey    subscription public key (base64url)
     * @param authSecret   subscription auth secret (base64url)
     * @param payload      plaintext payload bytes (UTF-8 JSON);
     *                     adapter cap'ten geçer, library encryption yapar
     * @param ttlSeconds   RFC 8030 TTL header
     * @return SendResult — HTTP status code + reason phrase only
     * @throws Exception network / library failure (adapter RETRY mapper'a düşer)
     */
    SendResult send(
        String endpointUrl,
        String p256dhKey,
        String authSecret,
        byte[] payload,
        int ttlSeconds
    ) throws Exception;

    /**
     * Adapter cap (max plaintext bytes) — adapter payload check ile cap'i
     * önceden uygular; library invocation oversize FAILED dönmesin diye.
     */
    int getMaxPlaintextBytes();

    /**
     * Adapter TTL default — adapter send call'ında parametre olarak iletir.
     */
    int getDefaultTtlSeconds();

    /**
     * Push service response status — minimal audit metadata (PII-safe;
     * body intentionally not captured).
     */
    record SendResult(int statusCode, String reasonPhrase) {}
}
