-- V56 — Device DECOMMISSION / REACTIVATE lifecycle audit (append-only, org-composite).
--
-- BOUNDARY: Adds an INSERT-only audit trail for device lifecycle transitions
-- driven by the new admin endpoints
--   POST /api/v1/admin/endpoint-devices/{id}/decommission
--   POST /api/v1/admin/endpoint-devices/{id}/reactivate
-- This migration does NOT change the DeviceStatus enum and adds NO device
-- delete path. KVKK posture: "deactivate, not delete" — a decommission is
-- reversible (reactivate) and the device row + its data are retained. The
-- device row's `status` column stays the lifecycle source of truth; this table
-- is the immutable who/when/why + cascade-count trail.
--
-- ORG CONTRACT: mirrors the V52 new-table org contract. tenant_id for read
-- paths; org_id carried for the org-composite FK. endpoint_org_id_compat_fill()
-- (V29) fills org_id = tenant_id on write; CHECK constraints validate the
-- match. Composite FK (device_id, org_id) -> endpoint_devices(id, org_id),
-- satisfied by endpoint_devices_id_org_id_key (V34).
--
-- APPEND-ONLY: UPDATE / DELETE rejected by trigger (immutable audit; the
-- canonical hash-chained EndpointAuditService event is emitted in the same
-- service transaction — this table is the structured lifecycle projection).

CREATE TABLE endpoint_device_lifecycle_audit (
    id                      UUID            NOT NULL,
    tenant_id               UUID            NOT NULL,
    org_id                  UUID,
    device_id               UUID            NOT NULL,
    action                  VARCHAR(16)     NOT NULL,
    from_status             VARCHAR(32)     NOT NULL,
    to_status               VARCHAR(32)     NOT NULL,
    actor_subject           VARCHAR(255)    NOT NULL,
    reason                  VARCHAR(512)    NOT NULL,
    cancelled_commands      INTEGER         NOT NULL DEFAULT 0,
    revoked_tokens          INTEGER         NOT NULL DEFAULT 0,
    finalized_uninstalls    INTEGER         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_device_lifecycle_audit PRIMARY KEY (id),
    CONSTRAINT ck_endpoint_device_lifecycle_audit_action
        CHECK (action IN ('DECOMMISSION', 'REACTIVATE')),
    CONSTRAINT ck_endpoint_device_lifecycle_audit_from_status
        CHECK (from_status IN
            ('PENDING_ENROLLMENT', 'ONLINE', 'STALE', 'OFFLINE', 'DECOMMISSIONED')),
    CONSTRAINT ck_endpoint_device_lifecycle_audit_to_status
        CHECK (to_status IN
            ('PENDING_ENROLLMENT', 'ONLINE', 'STALE', 'OFFLINE', 'DECOMMISSIONED')),
    CONSTRAINT ck_endpoint_device_lifecycle_audit_reason_not_blank
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_endpoint_device_lifecycle_audit_counts_nonneg
        CHECK (cancelled_commands >= 0
            AND revoked_tokens >= 0
            AND finalized_uninstalls >= 0),
    CONSTRAINT endpoint_device_lifecycle_audit_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_device_lifecycle_audit_org_id_not_null
        CHECK (org_id IS NOT NULL),
    CONSTRAINT fk_endpoint_device_lifecycle_audit_device
        FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id)
);

DROP TRIGGER IF EXISTS endpoint_device_lifecycle_audit_org_id_compat
    ON endpoint_device_lifecycle_audit;
CREATE TRIGGER endpoint_device_lifecycle_audit_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_device_lifecycle_audit
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE OR REPLACE FUNCTION endpoint_device_lifecycle_audit_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'endpoint_admin_service.endpoint_device_lifecycle_audit is append-only: % rejected',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_endpoint_device_lifecycle_audit_append_only
    BEFORE UPDATE OR DELETE ON endpoint_device_lifecycle_audit
    FOR EACH ROW EXECUTE FUNCTION endpoint_device_lifecycle_audit_append_only();

CREATE INDEX ix_endpoint_device_lifecycle_audit_tenant_device_created
    ON endpoint_device_lifecycle_audit (tenant_id, device_id, created_at DESC);

COMMENT ON TABLE endpoint_device_lifecycle_audit IS
    'Append-only audit of device DECOMMISSION/REACTIVATE lifecycle transitions '
    '(who/when/why + cascade counts). KVKK: deactivate not delete; reversible.';
