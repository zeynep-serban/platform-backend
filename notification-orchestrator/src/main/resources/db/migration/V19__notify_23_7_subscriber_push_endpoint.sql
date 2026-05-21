-- Faz 23.7 M7 T4.2 PR-W1 — WebPush subscriber endpoint registry.
--
-- Browser push subscription persistence (RFC 8030 + RFC 8291):
-- her subscriber'ın 1+ browser/cihazı için ayrı endpoint row.
-- Endpoint URL push servisi (FCM/Mozilla/Edge/WebPush) provider-bound;
-- agent backend send sırasında Authorization VAPID JWT + payload
-- encryption ile POST eder.
--
-- KVKK Madde 12 uyumu:
--   - subscriber_id ile bağlı; erasure path inboxRepo +
--     subscriber_push_endpoint hard delete (KVKK Art 17 right-to-erasure)
--   - VAPID public/private + p256dh subscription material'ı encrypted
--     at-rest (PG TDE veya pgcrypto pgp_sym_encrypt — V19'da plaintext
--     foundation, V19.1 follow-up encryption hardening)
--
-- Mobile (FCM/APNS) Faz 22.2 dep; bu migration BROWSER-ONLY scope:
-- - Chrome/Firefox/Edge/Safari (WebPush Protocol)
-- - Mobile native FCM/APNS sub-faz follow-up

CREATE TABLE notify.subscriber_push_endpoint (
    endpoint_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               VARCHAR(64)  NOT NULL,
    subscriber_id        VARCHAR(128) NOT NULL,
    endpoint_url         VARCHAR(2048) NOT NULL,
    p256dh_key           VARCHAR(512) NOT NULL,
    auth_secret          VARCHAR(256) NOT NULL,
    user_agent           VARCHAR(512),
    platform_hint        VARCHAR(64),
    expiration_time      TIMESTAMPTZ,
    last_seen_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    failure_count        INT          NOT NULL DEFAULT 0,
    last_failure_at      TIMESTAMPTZ,
    last_failure_reason  VARCHAR(128),
    deleted_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Unique constraint: subscriber + endpoint_url (yeniden register
    -- aynı browser üzerinde upsert eder).
    CONSTRAINT subscriber_push_endpoint_uniq
        UNIQUE (org_id, subscriber_id, endpoint_url),

    -- Soft delete consistency: deleted_at NULL veya past timestamp
    CONSTRAINT subscriber_push_endpoint_deleted_check
        CHECK (deleted_at IS NULL OR deleted_at <= NOW() + INTERVAL '1 minute')
);

COMMENT ON TABLE notify.subscriber_push_endpoint IS
    'Faz 23.7 M7 T4.2 PR-W1 — WebPush subscriber endpoint registry. '
    'Her subscriber 1+ browser cihazı için ayrı row (Chrome, Firefox, '
    'Edge, Safari). FCM/APNS mobile Faz 22.2 follow-up scope.';

COMMENT ON COLUMN notify.subscriber_push_endpoint.endpoint_url IS
    'Push service URL — PushSubscription.endpoint (RFC 8030). '
    'Provider-bound (FCM https://fcm.googleapis.com/fcm/send/..., '
    'Mozilla https://updates.push.services.mozilla.com/..., '
    'Edge https://*.notify.windows.com/...).';

COMMENT ON COLUMN notify.subscriber_push_endpoint.p256dh_key IS
    'PushSubscription.keys.p256dh — base64url-encoded ECDH public key '
    '(RFC 8291 Section 3). Payload encryption için browser-side key.';

COMMENT ON COLUMN notify.subscriber_push_endpoint.auth_secret IS
    'PushSubscription.keys.auth — base64url-encoded 16-byte HMAC '
    'auth secret (RFC 8291). Payload encryption derivation için.';

COMMENT ON COLUMN notify.subscriber_push_endpoint.failure_count IS
    'Consecutive failure count — 410 Gone (subscription expired) veya '
    '404 Not Found durumunda increment; threshold (örn. 3) aşılırsa '
    'soft delete (deleted_at set).';

-- Index: subscriber lookup (her dispatch için subscriber'ın aktif endpoint'lerini bul)
CREATE INDEX idx_subscriber_push_endpoint_subscriber
    ON notify.subscriber_push_endpoint (org_id, subscriber_id)
    WHERE deleted_at IS NULL;

-- Index: last_seen reverse query (audit + cleanup için 30+ gün eski endpoint'ler)
CREATE INDEX idx_subscriber_push_endpoint_last_seen
    ON notify.subscriber_push_endpoint (last_seen_at DESC)
    WHERE deleted_at IS NULL;

-- updated_at trigger
CREATE OR REPLACE FUNCTION notify.subscriber_push_endpoint_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_subscriber_push_endpoint_touch_updated_at
    BEFORE UPDATE ON notify.subscriber_push_endpoint
    FOR EACH ROW
    EXECUTE FUNCTION notify.subscriber_push_endpoint_touch_updated_at();
