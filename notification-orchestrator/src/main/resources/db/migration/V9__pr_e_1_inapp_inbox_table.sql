-- Faz 23.3 PR-E.1 — In-app inbox backend table (charter scope).
--
-- notification_inbox: subscriber-addressed in-app inbox rows. Independent
-- state machine (UNREAD/READ/ARCHIVED) over notification_delivery (which
-- tracks per-channel send attempts). Inbox lifecycle orthogonal to delivery
-- status — a delivery may be DELIVERED while inbox stays UNREAD until the
-- subscriber opens the message.
--
-- Design notes:
--   * Independent table (Option A vs reusing notification_delivery channel='in-app'
--     with extra columns) — cleaner separation, dedicated index footprint
--     for inbox listing + unread badge queries.
--   * Inbox row created at intent fan-out / dispatch time when 'in-app'
--     channel selected (next sub-PR PR-E.2 wires InAppInboxAdapter).
--   * Idempotent insert via UNIQUE (org_id, intent_id, subscriber_id) — a
--     given intent yields at most one inbox row per subscriber, even if
--     dispatch retries.
--
-- Out of scope (this PR):
--   * InAppInboxAdapter integration (PR-E.2)
--   * WebSocket / SSE real-time badge endpoint (PR-E.2 / 23.4)
--   * Bulk archive (mass mark-read) endpoint (deferred — current scope is
--     single-row mutations)
--   * TTL-based auto-archive worker (deferred to retention policy review)

CREATE TABLE notify.notification_inbox (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    subscriber_id VARCHAR(128) NOT NULL,

    -- Rendered content (snapshot at fan-out time; template version pinned via
    -- notification_intent.template_version, no runtime re-render)
    subject VARCHAR(500),
    body_text TEXT,
    body_html TEXT,
    locale VARCHAR(16) NOT NULL DEFAULT 'tr-TR',

    -- Metadata for client filtering/sorting
    topic_key VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,

    -- Inbox state machine: UNREAD → READ → ARCHIVED (terminal); ARCHIVED is
    -- soft-delete (rows kept for audit; KVKK erasure handles permanent
    -- deletion via existing erasure flow).
    state VARCHAR(16) NOT NULL DEFAULT 'UNREAD'
        CHECK (state IN ('UNREAD', 'READ', 'ARCHIVED')),
    read_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,

    -- Lifecycle
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

-- Idempotent insert: one inbox row per (org, intent, subscriber)
CREATE UNIQUE INDEX uq_inbox_org_intent_subscriber
    ON notify.notification_inbox (org_id, intent_id, subscriber_id);

-- Inbox listing: subscriber's non-archived inbox, newest first
-- (90% of GET /inbox/me queries hit this path)
CREATE INDEX idx_inbox_subscriber_active
    ON notify.notification_inbox (org_id, subscriber_id, created_at DESC)
    WHERE state != 'ARCHIVED';

-- Unread badge count: WHERE state='UNREAD' lookup
-- (covers GET /inbox/me/unread-count + WS subscription)
CREATE INDEX idx_inbox_unread_badge
    ON notify.notification_inbox (org_id, subscriber_id)
    WHERE state = 'UNREAD';

-- Intent fan-out join: list all inbox rows for an intent (admin/audit)
CREATE INDEX idx_inbox_intent
    ON notify.notification_inbox (org_id, intent_id);

-- State machine guard + timestamp safety net.
--
-- Codex iter-1 P2/P3 absorb:
-- - Forward-only state machine enforced at DB level (UNREAD → READ → ARCHIVED).
--   Backward transitions (READ→UNREAD, ARCHIVED→*) raise exception.
-- - INSERT path uses TG_OP guard (cleaner than OLD.state IS NULL).
-- - Timestamps: app sets read_at/archived_at via JPQL UPDATE (authoritative);
--   trigger only acts as safety net when NEW.read_at/archived_at is NULL.
--   This avoids "trigger overrides app timestamp" surprise.
CREATE OR REPLACE FUNCTION notify.notification_inbox_state_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Insert: any valid state allowed (CHECK constraint enforces enum).
        -- Set timestamps if state already non-UNREAD (test fixture / fan-out).
        IF NEW.state = 'READ' AND NEW.read_at IS NULL THEN
            NEW.read_at = NOW();
        END IF;
        IF NEW.state = 'ARCHIVED' AND NEW.archived_at IS NULL THEN
            NEW.archived_at = NOW();
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE path: enforce forward-only state machine.
    IF OLD.state = 'ARCHIVED' AND NEW.state <> 'ARCHIVED' THEN
        RAISE EXCEPTION
            'notification_inbox: ARCHIVED is terminal; cannot transition to %',
            NEW.state
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.state = 'READ' AND NEW.state = 'UNREAD' THEN
        RAISE EXCEPTION
            'notification_inbox: cannot transition READ → UNREAD (forward-only)'
            USING ERRCODE = 'check_violation';
    END IF;

    -- Timestamp safety net: if app forgot to set read_at/archived_at on
    -- transition, populate it (idempotent — preserves existing value).
    IF NEW.state = 'READ' AND OLD.state <> 'READ' AND NEW.read_at IS NULL THEN
        NEW.read_at = NOW();
    END IF;
    IF NEW.state = 'ARCHIVED' AND OLD.state <> 'ARCHIVED' AND NEW.archived_at IS NULL THEN
        NEW.archived_at = NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_inbox_state_audit
BEFORE INSERT OR UPDATE ON notify.notification_inbox
FOR EACH ROW EXECUTE FUNCTION notify.notification_inbox_state_audit();

COMMENT ON TABLE notify.notification_inbox IS
    'Faz 23.3 PR-E.1: subscriber-addressed in-app inbox; independent state '
    'machine over delivery. UNREAD → READ → ARCHIVED, forward-only enforced '
    'by trigger.';
COMMENT ON COLUMN notify.notification_inbox.state IS
    'UNREAD (initial) → READ (subscriber opens) → ARCHIVED (soft-delete). '
    'Terminal: ARCHIVED. Forward-only — backward transitions raise exception. '
    'Timestamps set by app (JPQL UPDATE); trigger acts as safety net.';
COMMENT ON COLUMN notify.notification_inbox.expires_at IS
    'Optional TTL for auto-archive worker (deferred to retention policy review).';
