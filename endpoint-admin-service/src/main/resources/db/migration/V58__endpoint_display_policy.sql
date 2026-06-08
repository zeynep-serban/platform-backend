-- V58 — Faz 22.5 #508 Endpoint Display Policy (backend track, slice-1 schema/contract).
--
-- BOUNDARY (Codex 019ea8be plan-time AGREE-after-REVISE absorbed): this slice
-- adds the desired-state + immutable-history tables and the SET_DISPLAY_POLICY
-- command-type CHECK widening ONLY. It does NOT add the dispatch service, the
-- REST surface, the .scr/style validators, the wallpaper IMAGE binary
-- upload/object-store (slice-2), the agent Go handler, or the web UI. DEVICE
-- scope only — GROUP scope is deferred until a canonical group parent exists
-- (no opaque group UUIDs stored here).
--
-- MODEL:
--   endpoint_display_policies           — current desired-state, one ACTIVE
--                                         (cleared_at IS NULL) row per device.
--                                         Denormalised latest snapshot for fast
--                                         reads + last-enforcement status.
--   endpoint_display_policy_revisions   — append-only immutable history; one row
--                                         per PUT (ENFORCE) / DELETE (CLEAR).
--                                         Carries the full policy snapshot, the
--                                         operation, a content hash, the required
--                                         reason, and the generated maker-checker
--                                         command id (approval lives on
--                                         endpoint_commands via existing
--                                         dual-control). Desired-state ≠
--                                         enforcement-result: command results
--                                         update only last_enforcement_* on the
--                                         current table, never the revision.
--
-- ORG CONTRACT: mirrors V52 bundles / V47 catalog. tenant_id for read paths +
-- org_id for org-composite FKs. endpoint_org_id_compat_fill() fills
-- org_id=tenant_id; CHECK constraints validate the match + NOT NULL.
--
-- .scr / style / timeout VALUE validation is enforced fail-closed at the
-- service layer (slice-2); the DB CHECKs here are the durable backstop
-- (enum domains + numeric range + cleared/operation invariants).

-- ----------------------------------------------------------------------------
-- Immutable revision history (append-only)
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_display_policy_revisions (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    org_id                      UUID,
    device_id                   UUID            NOT NULL,
    scope_type                  VARCHAR(16)     NOT NULL,
    operation                   VARCHAR(16)     NOT NULL,

    -- full desired-state snapshot (NULL for CLEAR revisions)
    screensaver_enabled         BOOLEAN,
    screensaver_timeout_seconds INTEGER,
    screensaver_secure          BOOLEAN,
    screensaver_scr_path        VARCHAR(260),
    wallpaper_enabled           BOOLEAN,
    wallpaper_style             VARCHAR(16),
    wallpaper_user_cannot_change BOOLEAN,
    wallpaper_asset_ref         VARCHAR(512),
    wallpaper_asset_sha256      CHAR(64),
    wallpaper_content_type      VARCHAR(64),

    -- normalized content hash for dispatch idempotency
    policy_hash_sha256          CHAR(64)        NOT NULL,
    reason                      VARCHAR(512)    NOT NULL,
    command_id                  UUID,

    created_by_subject          VARCHAR(255)    NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_display_policy_revisions PRIMARY KEY (id),
    CONSTRAINT endpoint_display_policy_revisions_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT ck_edpr_scope_type CHECK (scope_type IN ('DEVICE')),
    CONSTRAINT ck_edpr_operation CHECK (operation IN ('ENFORCE', 'CLEAR')),
    CONSTRAINT ck_edpr_style CHECK (wallpaper_style IS NULL
        OR wallpaper_style IN ('CENTER', 'STRETCH', 'FIT', 'FILL', 'SPAN')),
    CONSTRAINT ck_edpr_timeout CHECK (screensaver_timeout_seconds IS NULL
        OR (screensaver_timeout_seconds BETWEEN 60 AND 86400)),
    CONSTRAINT ck_edpr_sha256 CHECK (wallpaper_asset_sha256 IS NULL
        OR wallpaper_asset_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_edpr_policy_hash CHECK (policy_hash_sha256 ~ '^[0-9a-f]{64}$'),
    -- CLEAR revisions carry no desired-state snapshot (managed keys removed)
    CONSTRAINT ck_edpr_clear_is_empty CHECK (
        operation <> 'CLEAR' OR (
            screensaver_enabled IS NULL AND screensaver_timeout_seconds IS NULL
            AND screensaver_secure IS NULL AND screensaver_scr_path IS NULL
            AND wallpaper_enabled IS NULL AND wallpaper_style IS NULL
            AND wallpaper_user_cannot_change IS NULL AND wallpaper_asset_ref IS NULL
            AND wallpaper_asset_sha256 IS NULL AND wallpaper_content_type IS NULL
        )
    ),
    CONSTRAINT endpoint_display_policy_revisions_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_display_policy_revisions_org_id_not_null
        CHECK (org_id IS NOT NULL),
    -- an ENFORCE revision must carry at least one desired-state value; an empty
    -- ENFORCE would be an ambiguous no-op that is neither CLEAR nor a policy.
    CONSTRAINT ck_edpr_enforce_not_empty CHECK (
        operation <> 'ENFORCE' OR (
            screensaver_enabled IS NOT NULL OR screensaver_timeout_seconds IS NOT NULL
            OR screensaver_secure IS NOT NULL OR screensaver_scr_path IS NOT NULL
            OR wallpaper_enabled IS NOT NULL OR wallpaper_style IS NOT NULL
            OR wallpaper_user_cannot_change IS NOT NULL OR wallpaper_asset_ref IS NOT NULL
            OR wallpaper_asset_sha256 IS NOT NULL OR wallpaper_content_type IS NOT NULL
        )
    ),
    CONSTRAINT fk_edpr_device
        FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id),
    -- org-composite provenance link to the generated maker-checker command
    -- (append-only: slice-2 pre-generates both UUIDs or inserts the command
    -- first, then the revision with command_id already populated).
    CONSTRAINT fk_edpr_command
        FOREIGN KEY (command_id, org_id)
        REFERENCES endpoint_commands (id, org_id)
);

DROP TRIGGER IF EXISTS endpoint_display_policy_revisions_org_id_compat ON endpoint_display_policy_revisions;
CREATE TRIGGER endpoint_display_policy_revisions_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_display_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX idx_edpr_org_device_created
    ON endpoint_display_policy_revisions (org_id, device_id, created_at DESC);

-- One revision per command (a maker-checker command backs exactly one revision).
CREATE UNIQUE INDEX ux_edpr_command
    ON endpoint_display_policy_revisions (org_id, command_id)
    WHERE command_id IS NOT NULL;

-- Append-only guard: reuse the shared append-only trigger if present, else a
-- local BEFORE UPDATE/DELETE RAISE. The revision table must never be mutated.
CREATE OR REPLACE FUNCTION endpoint_display_policy_revisions_appendonly()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_display_policy_revisions is append-only (no % allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_edpr_appendonly ON endpoint_display_policy_revisions;
CREATE TRIGGER trg_edpr_appendonly BEFORE UPDATE OR DELETE ON endpoint_display_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION endpoint_display_policy_revisions_appendonly();

-- ----------------------------------------------------------------------------
-- Current desired-state (one ACTIVE row per device)
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_display_policies (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    org_id                      UUID,
    device_id                   UUID            NOT NULL,
    scope_type                  VARCHAR(16)     NOT NULL,
    operation                   VARCHAR(16)     NOT NULL,
    current_revision_id         UUID            NOT NULL,

    -- denormalised latest desired-state snapshot (NULL when operation=CLEAR)
    screensaver_enabled         BOOLEAN,
    screensaver_timeout_seconds INTEGER,
    screensaver_secure          BOOLEAN,
    screensaver_scr_path        VARCHAR(260),
    wallpaper_enabled           BOOLEAN,
    wallpaper_style             VARCHAR(16),
    wallpaper_user_cannot_change BOOLEAN,
    wallpaper_asset_ref         VARCHAR(512),
    wallpaper_asset_sha256      CHAR(64),
    wallpaper_content_type      VARCHAR(64),

    policy_hash_sha256          CHAR(64)        NOT NULL,

    -- lifecycle: cleared_at IS NULL => active managed policy
    cleared_at                  TIMESTAMPTZ,
    cleared_by_subject          VARCHAR(255),

    -- enforcement-result (separate from desired-state; updated by command result)
    last_enforcement_status     VARCHAR(24),
    last_enforced_at            TIMESTAMPTZ,

    created_by_subject          VARCHAR(255)    NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL,
    last_updated_by_subject     VARCHAR(255)    NOT NULL,
    last_updated_at             TIMESTAMPTZ     NOT NULL,
    version                     BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_display_policies PRIMARY KEY (id),
    CONSTRAINT endpoint_display_policies_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT ck_edp_scope_type CHECK (scope_type IN ('DEVICE')),
    CONSTRAINT ck_edp_operation CHECK (operation IN ('ENFORCE', 'CLEAR')),
    CONSTRAINT ck_edp_style CHECK (wallpaper_style IS NULL
        OR wallpaper_style IN ('CENTER', 'STRETCH', 'FIT', 'FILL', 'SPAN')),
    CONSTRAINT ck_edp_timeout CHECK (screensaver_timeout_seconds IS NULL
        OR (screensaver_timeout_seconds BETWEEN 60 AND 86400)),
    CONSTRAINT ck_edp_sha256 CHECK (wallpaper_asset_sha256 IS NULL
        OR wallpaper_asset_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_edp_policy_hash CHECK (policy_hash_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_edp_cleared_pair CHECK (
        (cleared_at IS NULL AND cleared_by_subject IS NULL)
        OR (cleared_at IS NOT NULL AND cleared_by_subject IS NOT NULL)
    ),
    -- CLEAR row => cleared_at set + no desired-state snapshot
    CONSTRAINT ck_edp_clear_consistency CHECK (
        operation <> 'CLEAR' OR (
            cleared_at IS NOT NULL
            AND screensaver_enabled IS NULL AND screensaver_timeout_seconds IS NULL
            AND screensaver_secure IS NULL AND screensaver_scr_path IS NULL
            AND wallpaper_enabled IS NULL AND wallpaper_style IS NULL
            AND wallpaper_user_cannot_change IS NULL AND wallpaper_asset_ref IS NULL
            AND wallpaper_asset_sha256 IS NULL AND wallpaper_content_type IS NULL
        )
    ),
    -- operation <-> lifecycle inverse: an ENFORCE row is active (not cleared),
    -- a CLEAR row is cleared. Closes the gap where ENFORCE + cleared_at could
    -- masquerade as an inactive policy (CLEAR removes managed keys; an
    -- enabled=false snapshot is a managed-disabled ENFORCE, distinct from CLEAR).
    CONSTRAINT ck_edp_operation_cleared_state CHECK (
        (operation = 'ENFORCE' AND cleared_at IS NULL AND cleared_by_subject IS NULL)
        OR (operation = 'CLEAR' AND cleared_at IS NOT NULL AND cleared_by_subject IS NOT NULL)
    ),
    -- an ENFORCE row must carry at least one desired-state value (no empty no-op)
    CONSTRAINT ck_edp_enforce_not_empty CHECK (
        operation <> 'ENFORCE' OR (
            screensaver_enabled IS NOT NULL OR screensaver_timeout_seconds IS NOT NULL
            OR screensaver_secure IS NOT NULL OR screensaver_scr_path IS NOT NULL
            OR wallpaper_enabled IS NOT NULL OR wallpaper_style IS NOT NULL
            OR wallpaper_user_cannot_change IS NOT NULL OR wallpaper_asset_ref IS NOT NULL
            OR wallpaper_asset_sha256 IS NOT NULL OR wallpaper_content_type IS NOT NULL
        )
    ),
    -- enforcement-result vocabulary (set by future command-result handling;
    -- aligned with CommandResultStatus). Defined now so slice-2 cannot drift.
    CONSTRAINT ck_edp_last_enforcement_status CHECK (
        last_enforcement_status IS NULL
        OR last_enforcement_status IN ('SUCCEEDED', 'FAILED', 'PARTIAL', 'UNSUPPORTED')
    ),
    CONSTRAINT endpoint_display_policies_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_display_policies_org_id_not_null
        CHECK (org_id IS NOT NULL),
    CONSTRAINT fk_edp_device
        FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id),
    CONSTRAINT fk_edp_current_revision
        FOREIGN KEY (current_revision_id, org_id)
        REFERENCES endpoint_display_policy_revisions (id, org_id)
);

DROP TRIGGER IF EXISTS endpoint_display_policies_org_id_compat ON endpoint_display_policies;
CREATE TRIGGER endpoint_display_policies_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_display_policies
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Exactly one ACTIVE (managed, not cleared) policy per device.
CREATE UNIQUE INDEX ux_edp_active_device
    ON endpoint_display_policies (org_id, device_id)
    WHERE scope_type = 'DEVICE' AND cleared_at IS NULL;

CREATE INDEX idx_edp_org_device
    ON endpoint_display_policies (org_id, device_id);

-- ----------------------------------------------------------------------------
-- SET_DISPLAY_POLICY command type — widen the endpoint_commands CHECK.
-- The command is dedicated-path-only: the generic /commands surface rejects it
-- (422) at the service layer (slice-1 code change). This CHECK is the durable
-- DB-level domain widening so the dedicated dispatcher (slice-2) can persist it.
--
-- Follows V12/V32/V53's discover-and-replace pattern: the CHECK constraint name
-- can differ across historical clusters before ck_endpoint_commands_type was
-- stabilized (V53). Discover by definition, drop, re-add the canonical name with
-- the full current set + SET_DISPLAY_POLICY (preserves V53's UPDATE_AGENT).
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    cn text;
BEGIN
    SELECT conname INTO cn
    FROM pg_constraint
    WHERE conrelid = 'endpoint_commands'::regclass
      AND contype = 'c'
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
        'UNINSTALL_SOFTWARE',
        'UPDATE_AGENT',
        'SET_DISPLAY_POLICY'
    ));

COMMENT ON CONSTRAINT ck_endpoint_commands_type ON endpoint_commands IS
    'Faz 22.5 #508: SET_DISPLAY_POLICY is dedicated-path-only; generic admin command creation rejects it (422) in EndpointAdminCommandService. Preserves V53 UPDATE_AGENT.';
