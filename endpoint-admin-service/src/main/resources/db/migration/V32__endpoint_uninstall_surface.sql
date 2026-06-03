-- AG-028 Phase 1 — Backend uninstall surface (Faz 22.5.6).
--
-- Adds the destructive-side companion to V12 install audit:
--   endpoint_uninstall_requests  — in-flight state, mutable (PENDING_APPROVAL → APPROVED → … → TERMINAL).
--   endpoint_uninstall_audit     — terminal-result append-only audit (BE-016 hash-chain via service emit).
--
-- Also extends the existing `endpoint_commands.command_type` CHECK constraint
-- with `UNINSTALL_SOFTWARE` (mirror V12 INSTALL_SOFTWARE pattern).
--
-- Cross-AI plan consensus thread chain:
--   - Plan-time iter-6 AGREE   on thread `019e8c8a-4c90-7c00-8f64-c88d47801a06` (expired).
--   - Plan-time iter-2 AGREE   on thread `019e8d81-3d87-78f2-ba17-9a8981c5eb16` (Phase 1 fresh thread).
-- Tracking issue: platform-k8s-gitops #1239.
--
-- Slot history: Phase 0 took V31 (catalog uninstall flags + change-request flow,
-- merged 2026-06-03 PR #399). Phase 1 claims V32 (next free slot at branch open).
--
-- Companion services / controllers / sanitizer / tests ship with this PR.

-- ─────────────────────────────────────────────────────────────────────
-- 1. Extend endpoint_commands.command_type CHECK with UNINSTALL_SOFTWARE
-- ─────────────────────────────────────────────────────────────────────
--
-- V12 added INSTALL_SOFTWARE; V32 extends the same way. The CHECK constraint
-- name varies (PG auto-generated or explicit) so we discover-and-replace.

DO $$
DECLARE cn TEXT;
BEGIN
  SELECT conname INTO cn FROM pg_constraint
   WHERE conrelid = 'endpoint_commands'::regclass
     AND pg_get_constraintdef(oid) LIKE '%command_type%';
  IF cn IS NOT NULL THEN
    EXECUTE 'ALTER TABLE endpoint_commands DROP CONSTRAINT ' || quote_ident(cn);
  END IF;
END $$;

ALTER TABLE endpoint_commands ADD CONSTRAINT ck_endpoint_commands_type
  CHECK (command_type IN (
    'COLLECT_INVENTORY',
    'LOCK_USER_LOGIN',
    'UNLOCK_USER_LOGIN',
    'CHANGE_LOCAL_PASSWORD',
    'SMB_LIST_ALLOWED_PATH',
    'SMB_READ_FILE_METADATA',
    'SMB_DOWNLOAD_FILE',
    'SMB_UPLOAD_FILE',
    'ROTATE_CREDENTIAL',
    'INSTALL_SOFTWARE',
    'UNINSTALL_SOFTWARE'
  ));

-- ─────────────────────────────────────────────────────────────────────
-- 2. endpoint_uninstall_requests — in-flight state (mutable)
-- ─────────────────────────────────────────────────────────────────────
--
-- State machine:
--   PENDING_APPROVAL  → APPROVED  → QUEUED → CLAIMED → RUNNING → TERMINAL
--   PENDING_APPROVAL  → TERMINAL  (rejected at approve time)
--
-- Composite tenant FK parity with V12 install audit (Codex iter-1 absorb):
-- prevents cross-tenant misroute at the DB layer.

CREATE TABLE endpoint_uninstall_requests (
    id                UUID            NOT NULL,
    tenant_id         UUID            NOT NULL,
    device_id         UUID            NOT NULL,
    catalog_item_id   UUID            NOT NULL,
    command_id        UUID,
    state             VARCHAR(32)     NOT NULL,
    idempotency_key   VARCHAR(128),
    reason            TEXT,
    created_by        VARCHAR(255)    NOT NULL,
    approved_by       VARCHAR(255),
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    state_updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_endpoint_uninstall_requests PRIMARY KEY (id),

    CONSTRAINT uq_endpoint_uninstall_requests_id_tenant UNIQUE (id, tenant_id),

    CONSTRAINT ck_endpoint_uninstall_state
        CHECK (state IN (
            'PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'CLAIMED', 'RUNNING', 'TERMINAL'
        )),

    -- Composite tenant FK parity with V12.
    CONSTRAINT fk_endpoint_uninstall_requests_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id),

    CONSTRAINT fk_endpoint_uninstall_requests_catalog
        FOREIGN KEY (catalog_item_id, tenant_id)
        REFERENCES endpoint_software_catalog_items (id, tenant_id),

    -- command_id is set after dispatch; deferred so the same-tx INSERT into
    -- endpoint_commands then UPDATE of this row to set the FK works.
    CONSTRAINT fk_endpoint_uninstall_requests_command
        FOREIGN KEY (command_id, tenant_id)
        REFERENCES endpoint_commands (id, tenant_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- Partial unique index — true single in-flight enforcement per (tenant, device, catalog).
-- Second concurrent propose hits this and the service maps to 409.
CREATE UNIQUE INDEX uq_endpoint_uninstall_one_inflight
    ON endpoint_uninstall_requests (tenant_id, device_id, catalog_item_id)
    WHERE state <> 'TERMINAL';

-- Partial unique idempotency: replay returns existing requestId at the service layer.
CREATE UNIQUE INDEX uq_endpoint_uninstall_idempotency
    ON endpoint_uninstall_requests (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Per-tenant list / count for admin UI.
CREATE INDEX idx_endpoint_uninstall_requests_tenant_state
    ON endpoint_uninstall_requests (tenant_id, state);

-- Per-device list (drawer view).
CREATE INDEX idx_endpoint_uninstall_requests_device_created
    ON endpoint_uninstall_requests (tenant_id, device_id, created_at DESC);

COMMENT ON TABLE endpoint_uninstall_requests IS
    'AG-028 in-flight uninstall requests. State machine PENDING_APPROVAL → APPROVED → QUEUED → CLAIMED → RUNNING → TERMINAL. Partial unique index uq_endpoint_uninstall_one_inflight enforces one open request per (tenant, device, catalog). Idempotency replay enforced via uq_endpoint_uninstall_idempotency. Composite (id, tenant_id) FK on device/catalog/command for tenant integrity.';

-- ─────────────────────────────────────────────────────────────────────
-- 3. endpoint_uninstall_audit — terminal-result append-only
-- ─────────────────────────────────────────────────────────────────────
--
-- Hash-chain audit event emission lives in the service layer (existing
-- endpoint_audit_events BE-016 chain). V32 only enforces append-only
-- discipline at the DB layer via trigger.

CREATE TABLE endpoint_uninstall_audit (
    id                       UUID            NOT NULL,
    request_id               UUID            NOT NULL,
    tenant_id                UUID            NOT NULL,
    device_id                UUID            NOT NULL,
    catalog_item_id          UUID            NOT NULL,
    command_id               UUID            NOT NULL,
    result_status            VARCHAR(48)     NOT NULL,
    verification             VARCHAR(32)     NOT NULL,
    exit_code                INTEGER,
    reported_at              TIMESTAMPTZ     NOT NULL,
    redacted_payload         JSONB           NOT NULL DEFAULT '{}'::jsonb,
    detection_evidence       JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_endpoint_uninstall_audit PRIMARY KEY (id),

    CONSTRAINT uq_endpoint_uninstall_audit_command UNIQUE (command_id),

    -- Composite tenant FK parity (V12 pattern).
    CONSTRAINT fk_endpoint_uninstall_audit_request
        FOREIGN KEY (request_id, tenant_id)
        REFERENCES endpoint_uninstall_requests (id, tenant_id),

    CONSTRAINT fk_endpoint_uninstall_audit_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id),

    CONSTRAINT fk_endpoint_uninstall_audit_catalog
        FOREIGN KEY (catalog_item_id, tenant_id)
        REFERENCES endpoint_software_catalog_items (id, tenant_id),

    CONSTRAINT fk_endpoint_uninstall_audit_command
        FOREIGN KEY (command_id, tenant_id)
        REFERENCES endpoint_commands (id, tenant_id),

    CONSTRAINT ck_endpoint_uninstall_audit_result_status
        CHECK (result_status IN (
            'SUCCEEDED_VERIFIED',
            'SKIP_ALREADY_ABSENT',
            'FAILED_VERIFY_GHOST',
            'FAILED_EXIT',
            'PARTIAL_RESIDUE',
            'PARTIAL_INCONCLUSIVE',
            'FAILED_PRECHECK_INCONCLUSIVE',
            'FAILED_UNSUPPORTED_PLATFORM',
            'FAILED_UNSUPPORTED_VERIFICATION'
        )),

    CONSTRAINT ck_endpoint_uninstall_audit_verification
        CHECK (verification IN (
            'ABSENT_VERIFIED',
            'PRESENT_VERIFIED',
            'RESIDUE_PRESENT',
            'VERIFY_INCONCLUSIVE',
            'NOT_RUN'
        )),

    -- JSONB object-shape CHECKs (Codex iter-1 must-fix #2 absorb): mirror
    -- V12 install audit DB-level defense. Sanitizer (UninstallEvidencePayloadPolicy)
    -- is the primary projection layer; these CHECKs are defense-in-depth.
    CONSTRAINT ck_endpoint_uninstall_audit_redacted_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_uninstall_audit_detection_evidence_shape
        CHECK (jsonb_typeof(detection_evidence) = 'object')
);

-- Append-only trigger (mirror V12 install audit pattern).
CREATE OR REPLACE FUNCTION endpoint_uninstall_audit_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_admin_service.endpoint_uninstall_audit is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_endpoint_uninstall_audit_append_only
    BEFORE UPDATE OR DELETE ON endpoint_uninstall_audit
    FOR EACH ROW EXECUTE FUNCTION endpoint_uninstall_audit_append_only();

-- History endpoint index (Codex iter-1 non-blocker #2 absorb): the planned
-- GET /uninstalls/history endpoint should not start with a table scan.
CREATE INDEX ix_endpoint_uninstall_audit_tenant_device_reported
    ON endpoint_uninstall_audit (tenant_id, device_id, reported_at DESC);

COMMENT ON TABLE endpoint_uninstall_audit IS
    'AG-028 terminal-result audit. Append-only via trigger. Composite tenant FK on request/device/catalog/command. BE-016 hash-chain event ENDPOINT_UNINSTALL_RESULT_RECORDED emitted in service layer on insert. JSONB shape CHECKs enforce object structure (defense-in-depth alongside UninstallEvidencePayloadPolicy sanitizer).';
