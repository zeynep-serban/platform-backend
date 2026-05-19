-- Faz 23.4 M6a — inbox 30-day history endpoint supporting index
-- (Codex thread `019e40ec` AGREE iter-2).
--
-- GET /api/v1/notify/inbox/me/history lists a subscriber's inbox rows
-- across ALL states (UNREAD + READ + ARCHIVED) within a server-enforced
-- rolling window (default 30 days), newest-first. The V9
-- `idx_inbox_subscriber_active` partial index carries
-- `WHERE state <> 'ARCHIVED'`, so it cannot serve a history query that
-- must include archived rows.
--
-- This index covers the (org_id, subscriber_id) equality predicate, the
-- `created_at >= :since` range scan and the `created_at DESC, id DESC`
-- ordering in one access path. `id DESC` is the deterministic
-- tie-breaker: `created_at` carries `DEFAULT NOW()` and a multi-recipient
-- intent fan-out can stamp several rows with an identical transaction
-- timestamp; without the `id` tie-breaker, offset pagination could drift
-- a row across page boundaries.
--
-- Plain `CREATE INDEX` (not CONCURRENTLY): pre-prod table is small and
-- Flyway wraps each migration in a transaction (CONCURRENTLY cannot run
-- inside one). Revisit only if the live table grows large under write
-- traffic.
--
-- SCOPE NOTE: the 30-day history is a query-time visibility filter, NOT a
-- storage retention policy. `notification_inbox` rows are never purged by
-- this work — inbox row retention (purge job + policy + legal sign-off)
-- is a separate sprint.

CREATE INDEX idx_inbox_subscriber_history
    ON notify.notification_inbox (org_id, subscriber_id, created_at DESC, id DESC);

COMMENT ON INDEX notify.idx_inbox_subscriber_history IS
    'Faz 23.4 M6a — serves GET /inbox/me/history: (org,subscriber) equality + created_at range + (created_at DESC, id DESC) ordering across ALL inbox states. The 30-day window is a query-time filter, not a storage retention policy (inbox row purge is a separate sprint).';
