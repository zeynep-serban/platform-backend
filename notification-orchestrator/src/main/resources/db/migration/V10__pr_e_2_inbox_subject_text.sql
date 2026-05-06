-- Faz 23.3 PR-E.2 — Codex iter-1 P1.2 absorb: notification_inbox.subject
-- alignment with notification_template.subject (TEXT, unbounded).
--
-- Earlier (V9): subject VARCHAR(500). Rendered subjects from longer templates
-- (multi-line, full sentences with substitution) can exceed 500 chars.
-- Constraint violation at insert would surface as transient RETRY (dispatch
-- exception handling), but it's deterministic data error — never resolves on
-- retry. Aligning to TEXT removes the false-RETRY path.

ALTER TABLE notify.notification_inbox
    ALTER COLUMN subject TYPE TEXT;

COMMENT ON COLUMN notify.notification_inbox.subject IS
    'Faz 23.3 PR-E.2: TEXT (was VARCHAR(500) in V9). Aligns with '
    'notification_template.subject (TEXT). Long rendered subjects from '
    'substitution-heavy templates do not trigger constraint violation.';
