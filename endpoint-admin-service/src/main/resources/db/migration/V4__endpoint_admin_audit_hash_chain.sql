-- BE-016 — Audit integrity hash-chain (Codex 019e4f8e plan-time AGREE).
--
-- Adds a tenant-scoped, append-only hash-chain over endpoint_audit_events so
-- that tamper of historical audit rows becomes detectable. This is the
-- security foundation required before BE-017 destructive command saga.
--
-- Scope discipline (Codex 019e4f8e scope-lock):
--   * NO historical backfill — pre-BE-016 rows keep NULL hash columns and are
--     treated as "legacy / pre-chain". Integrity is claimed only from the
--     first post-deployment hashed row onward (per-tenant GENESIS).
--   * NO retroactive integrity claim over legacy rows.
--   * DB-level append-only is enforced by the reject trigger below; combined
--     with the application hash-chain this yields genuine tamper-evidence.
--
-- KNOWN INTERACTION — deferred to BE-017 design (Codex 019e4f8e caveat):
--   endpoint_audit_events.device_id / command_id carry FK ON DELETE SET NULL.
--   The append-only trigger rejects UPDATE, so deleting a parent endpoint_device
--   / endpoint_command that has audit rows will FAIL (the cascade SET NULL is an
--   UPDATE). This is intentional — audit immutability outranks parent-row
--   deletion convenience. BE-017 destructive saga must either soft-delete
--   parents, drop the SET NULL cascade, or detach audit rows before delete.
--   BE-016 does NOT change this FK model.

-- --------------------------------------------------------------------------
-- 1. Hash-chain columns. Nullable: legacy rows stay NULL; the application
--    layer (EndpointAuditService) populates them for every new row.
-- --------------------------------------------------------------------------
ALTER TABLE endpoint_audit_events
    ADD COLUMN prev_event_hash    VARCHAR(64),
    ADD COLUMN event_hash         VARCHAR(64),
    ADD COLUMN event_hash_alg     VARCHAR(32),
    ADD COLUMN event_hash_version INTEGER;

COMMENT ON COLUMN endpoint_audit_events.prev_event_hash IS
    'BE-016: event_hash of the previous hashed row in the same tenant chain; NULL = tenant GENESIS or legacy pre-chain row.';
COMMENT ON COLUMN endpoint_audit_events.event_hash IS
    'BE-016: lowercase hex SHA-256 over the canonical event payload + prev hash; NULL = legacy pre-chain row.';
COMMENT ON COLUMN endpoint_audit_events.event_hash_alg IS
    'BE-016: hash algorithm label (currently SHA-256) for forward-compat verification.';
COMMENT ON COLUMN endpoint_audit_events.event_hash_version IS
    'BE-016: canonicalization scheme version; bump when the canonical payload field set or formatting changes.';

-- --------------------------------------------------------------------------
-- 2. Partial index for tenant-scoped chain-tail lookup. EndpointAuditService
--    finds the previous hashed row via
--      WHERE tenant_id = ? AND event_hash IS NOT NULL
--      ORDER BY occurred_at DESC, id DESC LIMIT 1
-- --------------------------------------------------------------------------
CREATE INDEX idx_endpoint_audit_events_chain_tail
    ON endpoint_audit_events (tenant_id, occurred_at DESC, id DESC)
    WHERE event_hash IS NOT NULL;

-- --------------------------------------------------------------------------
-- 3. Append-only enforcement. Any direct UPDATE or DELETE on a row is
--    rejected at the database layer. INSERT remains allowed (the only
--    legitimate audit write path).
-- --------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION endpoint_audit_events_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'endpoint_audit_events is append-only (BE-016 audit integrity): % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_endpoint_audit_events_append_only
    BEFORE UPDATE OR DELETE ON endpoint_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION endpoint_audit_events_append_only();

-- --------------------------------------------------------------------------
-- 4. Insert enforcement (Codex 019e4f8e P2-4). Every row inserted AFTER this
--    migration MUST carry event_hash + event_hash_alg + event_hash_version.
--    This closes the gap where a direct SQL insert or an application
--    regression could create a post-deploy null-hash row that the verifier
--    silently skips. Legacy pre-V4 rows are unaffected — they were inserted
--    before this trigger existed and keep their NULL hash columns.
--    prev_event_hash stays nullable: the per-tenant GENESIS row has none.
-- --------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION endpoint_audit_events_require_hash()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.event_hash IS NULL
        OR NEW.event_hash_alg IS NULL
        OR NEW.event_hash_version IS NULL THEN
        RAISE EXCEPTION
            'endpoint_audit_events insert requires event_hash + event_hash_alg + event_hash_version (BE-016 audit integrity)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_endpoint_audit_events_require_hash
    BEFORE INSERT ON endpoint_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION endpoint_audit_events_require_hash();
