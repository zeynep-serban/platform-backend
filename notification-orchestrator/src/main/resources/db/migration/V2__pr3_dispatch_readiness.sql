-- Faz 23.1 PR3 dispatch readiness (Codex 019df9ef post-impl P2 absorb).
--
-- Bu migration iki blocker'i çözer:
--   1. Recipients snapshot persist (PR4 worker readiness): submit endpoint
--      recipients listesini intent.recipients_snapshot JSONB'ye persist eder;
--      PR4 OutboxPoller email N-target plan'ini intent'ten yeniden kurabilir.
--   2. Per-target dispatch idempotency: notification_delivery unique constraint
--      (intent_id, channel, recipient_hash) — aynı dispatchPlanned() iki kez
--      cagrilirsa duplicate row insert engellenir; existing DELIVERED satirlar
--      tekrar gonderilmez.

-- ============================================================================
-- 1) Recipients snapshot for PR4 worker readiness
-- ============================================================================
ALTER TABLE notify.notification_intent
    ADD COLUMN recipients_snapshot JSONB;

COMMENT ON COLUMN notify.notification_intent.recipients_snapshot IS
    'Submit-time snapshot of recipients (PR3 — Codex 019df9ef P2 absorb). '
    'Email channel fan-out (N targets per intent) requires this for PR4 worker '
    'to reconstruct delivery plan. Slack/webhook channels are target-addressed '
    '(1 target per intent) and do not depend on this column.';

-- ============================================================================
-- 2) Per-target dispatch idempotency
-- ============================================================================
-- Same (intent_id, channel, recipient_hash) tuple → 1 delivery row max.
-- recipient_hash is HMAC-redacted address (email/subscriber/external) for
-- recipient-addressed channels, or namespaced channel id for target-addressed
-- channels (e.g., HMAC(org_id, "channel", "slack")). Uniqueness ensures:
--   - dispatchPlanned() retry skips DELIVERED targets
--   - PR4 OutboxPoller re-attempts honor existing rows
--   - Concurrent dispatch (multi-pod) → ON CONFLICT DO NOTHING semantics
CREATE UNIQUE INDEX uq_delivery_intent_channel_recipient
    ON notify.notification_delivery (intent_id, channel, recipient_hash);
