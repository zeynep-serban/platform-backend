-- V17: Add `deleted_at` column to `users` for soft-delete (tombstone).
-- Codex thread 019ea573 — user DELETE action, Phase 2 of platform-web #770
-- (UserActions İşlemler menu).
--
-- NULL = active row; non-NULL = soft-deleted tombstone.
--
-- Design (deliberate, see Codex 019ea573):
--   * NOT a global Hibernate @Where/@SQLRestriction filter. The identity-
--     resolution security paths (CurrentUserResolver, lazyProvisionFromJwt,
--     provisionFromKeycloak, common-auth AuthenticatedUserLookupService)
--     MUST read tombstones explicitly so a deleted user gets a clean
--     `403 USER_DELETED` and is never silently resurrected into a fresh row.
--     Public/query surfaces (list/get/by-email/export/impersonation-target)
--     exclude tombstones via an explicit notDeleted() Specification + named
--     repository methods.
--   * email/kc_subject UNIQUE constraints are PRESERVED (no partial unique
--     index). Re-creating a soft-deleted email returns `409
--     USER_DELETED_RESTORE_REQUIRED`; restore is an explicit admin action
--     (restore=true / POST /{id}/restore). A partial unique index is a
--     separate, larger migration only if "same email = new identity"
--     becomes a real business rule.
--
-- The column is nullable so Flyway adds it without backfill (all existing
-- rows are active). A partial index speeds the active-only filter.

ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMP;

-- Partial index: active-row lookups (the common case) skip tombstones.
CREATE INDEX IF NOT EXISTS idx_users_active
    ON users (id)
    WHERE deleted_at IS NULL;
