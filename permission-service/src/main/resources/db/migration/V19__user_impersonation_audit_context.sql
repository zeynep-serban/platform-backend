-- V19: User Impersonation v1 (PR-B) — Codex 019e0dfb iter-19 absorb
--
-- Adds impersonation context to permission_audit_events + new
-- impersonation_sessions table for jti_session_lookup binding model
-- (Spike-2 PASS_JTI_SESSION_LOOKUP).
--
-- Design decisions (Codex iter-10/19):
--   1. UNIQUE (issuer, jti) for cross-realm safety
--   2. status varchar + check (NOT enum, easier migration)
--   3. FAILED attempts → permission_audit_events, NOT sessions
--   4. Indexes: ux_issuer_jti (unique), ix_active_lookup (runtime),
--      ix_actor_target_started (audit dashboard)
--   5. impersonator_user_id authoritative from DB session (NOT JWT)
--   6. HPA-managed assumption (Codex iter-19): pod-independent lookup

-- ─── 1. permission_audit_events impersonation context columns ─────────
ALTER TABLE permission_audit_events
    ADD COLUMN impersonation_session_id UUID,
    ADD COLUMN is_impersonated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN impersonator_user_id BIGINT,
    ADD COLUMN impersonator_subject VARCHAR(255),
    ADD COLUMN impersonator_email VARCHAR(255),
    ADD COLUMN target_user_id BIGINT,
    ADD COLUMN target_subject VARCHAR(255),
    ADD COLUMN target_email VARCHAR(255),
    ADD COLUMN impersonation_reason VARCHAR(500);

-- Audit dashboard query indexes (PR-D query patterns)
CREATE INDEX IF NOT EXISTS idx_permission_audit_impersonation_session
    ON permission_audit_events(impersonation_session_id)
    WHERE impersonation_session_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_permission_audit_impersonator
    ON permission_audit_events(impersonator_user_id, occurred_at DESC)
    WHERE impersonator_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_permission_audit_target
    ON permission_audit_events(target_user_id, occurred_at DESC)
    WHERE target_user_id IS NOT NULL;

-- ─── 2. impersonation_sessions table ──────────────────────────────────
-- Authoritative source for actor/target identity in audit middleware;
-- jti+sid lookup via runtime middleware (jti_session_lookup binding).
-- 2026-05-10 (Codex iter-25 P1 absorb): pgcrypto extension assumption
-- DROP edildi. App-generated UUID birincil yol (Hibernate @PrePersist);
-- DB default kalmadı — JPA save akışı sırasında Hibernate id assigned
-- bekler. Defansif katman gerekirse application code güvence verir.
CREATE TABLE impersonation_sessions (
    id UUID PRIMARY KEY,

    -- Actor / target identity (authoritative)
    impersonator_user_id BIGINT NOT NULL,
    impersonator_subject VARCHAR(255) NOT NULL,
    impersonator_email VARCHAR(255),
    target_user_id BIGINT NOT NULL,
    target_subject VARCHAR(255) NOT NULL,
    target_email VARCHAR(255),

    -- Token binding (Codex iter-10: UNIQUE issuer+jti)
    issuer VARCHAR(255) NOT NULL,
    jti VARCHAR(255) NOT NULL,
    sid VARCHAR(255) NOT NULL,

    -- Lifecycle
    reason VARCHAR(500) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    ended_reason VARCHAR(50),       -- USER_STOP, LOGOUT, TOKEN_EXPIRED, ADMIN_REVOKE, SYSTEM_SWEEP

    -- Status (varchar + check, NOT enum — Codex iter-10 önerisi)
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',

    -- Optional context
    ip_address INET,
    user_agent TEXT,
    client_ip_via_xff INET,

    -- Constraints
    CONSTRAINT impersonation_sessions_status_check
        CHECK (status IN ('ACTIVE', 'STOPPED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT impersonation_sessions_no_self
        CHECK (impersonator_user_id != target_user_id),
    -- Codex iter-25 P1 absorb: subject-level no-self guard (user_id check
    -- yetmez — keycloak subject'leri farklı id mapping'iyle eşleşebilir).
    CONSTRAINT impersonation_sessions_no_self_subject
        CHECK (impersonator_subject != target_subject),
    CONSTRAINT impersonation_sessions_ended_after_start
        CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT impersonation_sessions_expires_after_start
        CHECK (expires_at > started_at)
);

-- Codex iter-10 önerisi: UNIQUE (issuer, jti) cross-realm safety
CREATE UNIQUE INDEX ux_impersonation_sessions_issuer_jti
    ON impersonation_sessions(issuer, jti);

-- Runtime lookup index (middleware hot path):
-- WHERE issuer=? AND jti=? AND sid=? AND status='ACTIVE' AND expires_at > now()
CREATE INDEX ix_impersonation_sessions_active_lookup
    ON impersonation_sessions(issuer, jti, sid, status, expires_at)
    WHERE status = 'ACTIVE' AND ended_at IS NULL;

-- Audit dashboard query: actor history
CREATE INDEX ix_impersonation_sessions_actor_target_started
    ON impersonation_sessions(impersonator_user_id, target_user_id, started_at DESC);

-- Single-active-session policy DB ENFORCE (Codex iter-25 P1 absorb).
-- Bir impersonator'ın aynı anda en fazla 1 active session'ı olabilir.
-- HPA-managed pod-paralel POST request'lerinde DB UNIQUE constraint
-- ile race condition kapanır. Repository check yetmez (TOCTOU race).
-- Start endpoint transaction başında sweepExpiredSessions(now) çağırarak
-- expired ama henüz ACTIVE row'u temizler (yoksa unique violation).
CREATE UNIQUE INDEX ux_impersonation_sessions_one_active_per_impersonator
    ON impersonation_sessions(impersonator_user_id)
    WHERE status = 'ACTIVE' AND ended_at IS NULL;

-- ─── 3. Comments for audit clarity ────────────────────────────────────
COMMENT ON TABLE impersonation_sessions IS
    'User Impersonation v1 (PR-B) — authoritative session lookup table for jti_session_lookup binding model. impersonator_user_id resolved from this table, NOT from JWT (Codex iter-10/19).';

COMMENT ON COLUMN impersonation_sessions.status IS
    'ACTIVE: live session; STOPPED: user explicit stop; EXPIRED: TTL sweeper; REVOKED: admin/system revoke. FAILED attempts go to permission_audit_events (IMPERSONATION_FAILED/IMPERSONATION_BLOCKED), NOT this table.';

COMMENT ON COLUMN impersonation_sessions.ended_reason IS
    'USER_STOP: explicit DELETE /current; LOGOUT: best-effort logout flow; TOKEN_EXPIRED: TTL sweeper; ADMIN_REVOKE: admin terminate; SYSTEM_SWEEP: scheduled cleanup.';
