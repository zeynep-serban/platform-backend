-- Faz 23.5 hardening — multi-pod DB-clock cutoff for notification_inbox
-- (Codex thread `019e03b5` AGREE iter-1).
--
-- The mark-all-read flow originally captured the cutoff timestamp on the
-- handler's first line (JVM clock) and let JPA write `created_at` /
-- `read_at` from the same JVM. Pre-prod single-pod sub-second NTP drift
-- made that safe; multi-pod scale (HPA min/max>1) breaks the assumption
-- because two pods can disagree on "now" by hundreds of ms, and a row
-- created on pod A with a future-skewed clock could still satisfy the
-- mark-all-read predicate on pod B.
--
-- The fix is to make the database the canonical clock source for every
-- inbox timestamp. V9 already had `created_at TIMESTAMPTZ NOT NULL
-- DEFAULT NOW()`, so for fresh installs nothing structural needs to
-- change; this migration is an idempotent guard against pre-prod / live
-- DBs that may have run with stale defaults from a tweaked V9 checksum.
-- It also flips the column documentation from "app sets timestamps;
-- trigger safety net" to "DB timestamp authoritative".

ALTER TABLE notify.notification_inbox
    ALTER COLUMN created_at SET DEFAULT NOW();

COMMENT ON COLUMN notify.notification_inbox.created_at IS
    'DB-authoritative creation timestamp (Faz 23.5 hardening). NEVER set by the application — DEFAULT NOW() owns the value so multi-pod mark-all-read cutoff (Faz 23.5 PR1) is race-safe under clock drift.';

COMMENT ON COLUMN notify.notification_inbox.read_at IS
    'DB-authoritative read transition timestamp. mark-as-read / mark-all-read SQL writes NOW() (or CURRENT_TIMESTAMP) directly so the read marker uses the same DB clock as the cutoff predicate.';

COMMENT ON COLUMN notify.notification_inbox.archived_at IS
    'DB-authoritative archive timestamp. archive endpoint SQL writes NOW() so archive timeline is consistent with the inbox read clock.';

-- Codex REVISE iter-2 absorb (thread `019e03c9`): the V9 `state` column
-- comment claimed "Timestamps set by app (JPQL UPDATE); trigger acts as
-- safety net". After Faz 23.5 hardening that narrative is wrong on two
-- counts — (1) the timestamps come from the DB clock, not the app, and
-- (2) writes are native SQL `NOW()` rather than JPQL UPDATE. Override
-- the comment so the schema docs match the new authority model.
COMMENT ON COLUMN notify.notification_inbox.state IS
    'Inbox row state machine: UNREAD → READ → ARCHIVED. Faz 23.5: state transitions are written by native SQL alongside the DB clock (NOW()/CURRENT_TIMESTAMP) for read_at / archived_at; the V9 trigger remains as a backward-transition safety net.';
