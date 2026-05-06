-- Faz 23.1 PR5 — SubscriberContact + Authz integration (Codex 019dfaaa absorb).
--
-- Bu migration:
--   1. notify.subscriber_contact tablosu — subscriber_id → email/phone/locale
--      (PR3 caveat'i çözer; subscriber recipient için email lookup)
--   2. subscriber_preference org_id-aware unique index (mevcut subscriber_id
--      tek başına unique değildi)
--   3. PR5 seed-friendly contact + preference test data hazırlığı

-- ============================================================================
-- 1) subscriber_contact — read-model lookup table
-- ============================================================================
-- Source-of-truth gerçek (Workcube ETL veya event projection) sonraki faz.
-- PR5'te local seed + test fixture ile doldurulur.
CREATE TABLE notify.subscriber_contact (
    id BIGSERIAL PRIMARY KEY,
    org_id VARCHAR(64) NOT NULL,
    subscriber_id VARCHAR(128) NOT NULL,
    email VARCHAR(254),
    phone VARCHAR(32),
    locale VARCHAR(16) NOT NULL DEFAULT 'tr-TR',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    source VARCHAR(64) NOT NULL DEFAULT 'manual',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_subscriber_contact_org_subscriber
    ON notify.subscriber_contact (org_id, subscriber_id);

CREATE INDEX idx_subscriber_contact_email
    ON notify.subscriber_contact (email)
    WHERE email IS NOT NULL;

COMMENT ON TABLE notify.subscriber_contact IS
    'Subscriber contact lookup (Codex 019dfaaa PR5 Q2 absorb). Source-of-truth '
    'preference''dan ayrı: tercih = topic/channel allow/deny; contact = subscriber-level '
    'identity/contact projection. Workcube ETL/event projection sonraki faz.';

-- ============================================================================
-- 2) subscriber_preference org-aware unique constraint
-- ============================================================================
-- Mevcut V1 schema'da unique index yok; aynı subscriber_id farklı org'larda
-- var olabilir + aynı (subscriber_id, channel, topic_key) farklı org'larda
-- duplicate olabilir. PR5: org_id-aware unique tuple.
CREATE UNIQUE INDEX uq_subscriber_preference_org_subscriber_channel_topic
    ON notify.subscriber_preference (org_id, subscriber_id, COALESCE(channel, ''), COALESCE(topic_key, ''));

COMMENT ON INDEX notify.uq_subscriber_preference_org_subscriber_channel_topic IS
    'Org-aware preference uniqueness (Codex 019dfaaa PR5 Q2 absorb). NULL channel/topic '
    'wildcard means "all channels" or "all topics" — coalesced to empty string for '
    'unique resolution.';

-- ============================================================================
-- 3) DELIVERY_BLOCKED audit event type semantik (audit_event tarafında schema
--    değişikliği gerek YOK — event_type column zaten string)
-- ============================================================================
-- Codex 019dfaaa PR5 lock-in #5: BLOCKED_* delivery row için DELIVERY_ATTEMPTED
-- yerine DELIVERY_BLOCKED audit event yazılmalı. Bu DispatchService kod-side
-- enforcement; schema değişikliği yok.
