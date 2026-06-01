-- BE — Endpoint Hotfix Posture (Faz 22.5, AG-037 hotfix-posture ingest).
-- Mirrors the BE-024/AG-036 V20 outdated-software append-only precedent + the
-- BE-022 V13 hardware-inventory composite-FK tenant-integrity pattern EXACTLY,
-- but extends both to:
--   (a) THREE child facet tables instead of one (installed hotfixes + pending
--       updates + per-category pending rollup) plus ONE grand-child KB list
--       under the pending table (composite-FK normalized);
--   (b) flat agent-health scalars at the snapshot root (no 1:1 child table —
--       eliminates Hibernate @OneToOne fetch hazards while preserving DB-level
--       CHECK / query capability);
--   (c) BOTH a partial UNIQUE on source_command_result_id AND a full UNIQUE
--       on (tenant_id, device_id, payload_hash_sha256) so the canonical-form
--       payload-hash idempotency contract is enforced by the DB at the same
--       physical layer as the source_command_result_id idempotency — both
--       are caught race-clean by the service's targetless
--       INSERT ... ON CONFLICT DO NOTHING write path (Codex 019e81fe iter-3
--       AGREE).
--
-- Wire contract: platform-agent docs/COMMAND-CONTRACT.md §16 (AG-037 PR #45
-- merged 2026-06-01 commit ae1b98e + e306967 + 2b0f3b5). The AG-037 hotfix
-- posture probe block is carried under the COLLECT_INVENTORY result at
-- details.inventory.hotfixPosture (schemaVersion=1). Source of truth =
-- platform-agent internal/inventory/hotfix_posture.go HotfixPostureResult.
--
-- The probe is read-only (pinned PowerShell + Microsoft.Update.Session.*
-- WUA COM Search/QueryHistory + Get-CimInstance Win32_Service + AU policy
-- registry reads + Get-HotFix installed-only fallback) — it NEVER mutates
-- Windows Update state (no wuauclt /detectnow, no Install-WindowsUpdate, no
-- service start/stop/enable/disable, no policy write). Backend ingest is a
-- persist/query path; it MUST NOT trigger any agent-side mutation from this
-- payload.
--
-- REDACTION BOUNDARY (security invariant — machine-enforced at the agent +
-- the backend HotfixPosturePayloadPolicy):
-- the per-hotfix wire shape is EXACTLY {kbId, installedOn, description}, the
-- per-pending wire shape is EXACTLY {kbIds, primaryCategory, severity}, and
-- the agent-health shape is bounded to seven scalars
-- {wuaServiceState, bitsServiceState, lastDetectAt, lastInstallAt,
-- autoUpdatePolicyEnabled, autoUpdateEffectiveEnabled, notificationLevel}.
-- The HotfixPosturePayloadPolicy fail-closed rejects forbidden Microsoft-
-- update fields (productCode, msiGuid, supersedence, installClient,
-- installedBy, commandLine, accountName, title) BEFORE the parent
-- command-result row is persisted. probeError.summary is bounded operator
-- text (no raw errno / path / KB description / update title).
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) and
-- probe_complete=false (any probeError or no-evidence run) are persisted AS
-- evidence (the agent still emits canonical metadata so the backend records
-- "probe not supported / incomplete here" instead of treating absence as a
-- failed ingest). Consumers MUST NOT render an incomplete probe as "fully
-- patched / no pending updates".
--
-- This migration delivers FIVE DDL slices:
--
--   1. endpoint_hotfix_posture_snapshots — append-only history of hotfix
--      posture observations (counts + truncation provenance + 3 source
--      attributions + flat agent-health scalars) harvested via the existing
--      COLLECT_INVENTORY agent contract. Each row captures the scalar
--      summary at one point in time plus an opaque redacted_payload JSONB
--      carrying the full validated block (within the redaction boundary).
--
--   2. endpoint_hotfix_posture_installed — per-installed-hotfix facets
--      (kbId / installedOn / description) for a snapshot.
--
--   3. endpoint_hotfix_posture_pending — per-pending-update facets
--      (primaryCategory / severity, plus normalized child kbIds — see slice
--      4).
--
--   4. endpoint_hotfix_posture_pending_kbs — composite-FK normalized child
--      of slice 3 carrying KB ids associated with each pending update (so
--      KB-bazlı fleet / compliance queries can index without JSONB array
--      contains scans).
--
--   5. endpoint_hotfix_posture_pending_categories — composite-FK normalized
--      child carrying the full pre-truncation pendingByCategory rollup
--      (preserved even when the per-item pending list is capped at 20).
--
-- Append-only history is intentional (parity with V13/V17/V18/V20). The
-- latest snapshot is a query (ORDER BY collected_at DESC, created_at DESC,
-- id DESC), NOT an "upsert into current row" pattern.
--
-- IDEMPOTENCY + ATOMICITY: the snapshot insert uses a native
-- INSERT ... ON CONFLICT DO NOTHING (targetless — Codex 019e81fe iter-3
-- P1.1) against BOTH the partial UNIQUE on source_command_result_id AND the
-- full UNIQUE on (tenant_id, device_id, payload_hash_sha256) below; a
-- duplicate on either is a clean no-op (the service then re-looks up the
-- winner row sequentially), and every other CHECK / FK / child-UNIQUE
-- violation propagates and rolls the whole ingest transaction back (no
-- broad-catch swallow). The canonical-form payload hash composition is
-- deterministic over the policy-projected normalized tree EXCLUDING wire
-- collectedAt + probeDurationMs (timing-only); lastDetectAt + lastInstallAt
-- are INCLUDED in the hash because they represent posture evidence, not
-- probe timing (Codex 019e81fe iter-3 ANSWER).
--
-- MIGRATION SEQUENCE GUARD: V21 (catalog_detection_rule_agent_schema, #102)
-- was the last applied migration on origin/main commit c1824089. V22 claims
-- this slot exclusively for BE AG-037 hotfix posture. The V12 (id, tenant_id)
-- UNIQUE on endpoint_devices is reused here for the composite FK — it is
-- NOT recreated.

-- ---------------------------------------------------------------------
-- 1. endpoint_hotfix_posture_snapshots — append-only history
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hotfix_posture_snapshots (
    id                              UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_id                       UUID            NOT NULL,
    -- Pointer to the agent command-result that delivered this snapshot.
    -- NULL is allowed so result cleanup (retention) does not cascade
    -- delete the hotfix posture history; UNIQUE (partial) because the
    -- agent SUBMIT result path must be idempotent — re-submitting the same
    -- command result must NOT create a second snapshot (the targetless
    -- ON CONFLICT DO NOTHING write path is the authoritative race-safe
    -- guard, complemented by the canonical-hash UNIQUE below).
    source_command_result_id        UUID,
    schema_version                  SMALLINT        NOT NULL,

    -- supported = false on non-Windows runtimes (the agent still emits
    -- canonical metadata so the backend persists "probe not supported here"
    -- instead of treating absence as a failed ingest).
    supported                       BOOLEAN         NOT NULL,
    -- probe_complete = false when any probeError is present OR a fallback
    -- path could not produce a snapshot (fail-closed: treat as "evidence
    -- incomplete", never render an incomplete probe as "fully patched").
    probe_complete                  BOOLEAN         NOT NULL,

    -- Pre-truncation totals (mirror V20 upgradeCount semantics — what the
    -- source claims existed, regardless of agent cap). The persisted child
    -- row counts are post-cap (<= 512 installed; <= 20 pending). The
    -- per-snapshot invariants
    --     installed_truncated=false => installed_count == installed_children
    --     installed_truncated=true  => installed_count >= installed_children
    --                                  AND installed_children <= 512
    -- are enforced by HotfixPosturePayloadPolicy (the DB CHECK only caps
    -- max_installed / max_pending themselves at the agent contract
    -- constants).
    installed_count                 INTEGER         NOT NULL,
    max_installed                   INTEGER         NOT NULL,
    installed_truncated             BOOLEAN         NOT NULL,

    -- pending_total_count has NO upper CHECK relative to max_pending: the
    -- pendingByCategory rollup may legitimately exceed the per-item cap.
    -- The (sum(pendingByCategory.count) == pending_total_count) invariant
    -- is enforced by HotfixPosturePayloadPolicy.
    pending_total_count             INTEGER         NOT NULL,
    max_pending                     INTEGER         NOT NULL,
    pending_truncated               BOOLEAN         NOT NULL,

    -- Source attribution: each section attributes the authoritative source
    -- it actually queried (wire contract §16.7).
    -- installed: 'wua' (WUA QueryHistory primary) | 'getHotfix' (PowerShell
    --            installed-only fallback) | 'none' (probe failed before any
    --            source).
    -- pending:   'wua' (WUA Search; no fallback) | 'none'.
    -- health:    composite — 'service' (SCM via Get-CimInstance Win32_Service)
    --            | 'registry' (timestamps + AU policy) | 'composite' (both)
    --            | 'none'.
    installed_source_used           VARCHAR(16)     NOT NULL,
    pending_source_used             VARCHAR(16)     NOT NULL,
    health_source_used              VARCHAR(16)     NOT NULL,

    probe_duration_ms               INTEGER,

    -- Canonical-form payload hash (SHA-256 of the policy-projected
    -- normalized tree). EXCLUDES wire collectedAt + probeDurationMs
    -- (timing-only); INCLUDES lastDetectAt + lastInstallAt (posture
    -- evidence). Stored as 64-char lowercase hex.
    payload_hash_sha256             VARCHAR(64)     NOT NULL,

    -- Flat agent-health scalars at snapshot root (Codex 019e81fe iter-2
    -- P1.3 — agentHealth 1:1 child eliminated to avoid @OneToOne fetch
    -- hazards). All seven fields nullable EXCEPT the two ServiceState
    -- enums, which carry UNKNOWN as the fail-closed "could not read"
    -- sentinel (parity with the wire contract typed enum).
    wua_service_state               VARCHAR(8)      NOT NULL,
    bits_service_state              VARCHAR(8)      NOT NULL,
    last_detect_at                  TIMESTAMPTZ,
    last_install_at                 TIMESTAMPTZ,
    -- 3-state nullable bool: TRUE (registry NoAutoUpdate disabled or AU
    -- policy enabled) / FALSE (policy disabled) / NULL (registry path
    -- unreadable). Mirrors the wire *bool.
    auto_update_policy_enabled      BOOLEAN,
    -- 3-state nullable bool: TRUE (effective AU policy on +
    -- service running) / FALSE / NULL.
    auto_update_effective_enabled   BOOLEAN,
    -- AUOptions registry value verbatim. Bounded by regex below
    -- (typically '1' / '2' / '3' / '4' per Microsoft AU policy docs);
    -- some GPO variants emit '0' or padded values, so the contract
    -- accepts a bounded numeric string rather than a closed enum.
    -- Empty string is normalized to NULL by the policy BEFORE write.
    notification_level              VARCHAR(4),

    -- Integrity + provenance.
    redacted_payload                JSONB           NOT NULL DEFAULT '{}'::jsonb,
    probe_errors                    JSONB           NOT NULL DEFAULT '[]'::jsonb,

    collected_at                    TIMESTAMPTZ     NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version                         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_hotfix_posture_snapshots PRIMARY KEY (id),

    -- (id, tenant_id) UNIQUE so the child facet tables can bind via
    -- composite FK and the tenant column is physically enforced
    -- (parity with V13/V17/V20 snapshots).
    CONSTRAINT uq_endpoint_hotfix_post_snap_id_tnt
        UNIQUE (id, tenant_id),

    -- payload_hash_sha256 stored as 64-char lowercase SHA-256 hex.
    -- The CHECK anchors the exact shape; the dedupe query relies on a
    -- direct VARCHAR `=` via cast(:hash as string) (never lower(bytea)).
    -- Mirrors V20 BE-022Q lesson.
    CONSTRAINT ck_endpoint_hotfix_post_snap_hash_format
        CHECK (payload_hash_sha256 ~ '^[a-f0-9]{64}$'),

    -- HARD same-hash idempotency invariant (Codex 019e81fe iter-2 P1.7 +
    -- iter-3 P1.1): the canonical-form payload hash is unique per
    -- (tenant, device). Concurrent SUBMIT-result calls carrying byte-
    -- identical posture under different source_command_result_id race-
    -- safely no-op via the targetless ON CONFLICT DO NOTHING service
    -- write path. Append-only model: new posture hash appends, identical
    -- posture returns the existing snapshot.
    CONSTRAINT uq_endpoint_hotfix_post_snap_tnt_dev_hash
        UNIQUE (tenant_id, device_id, payload_hash_sha256),

    CONSTRAINT ck_endpoint_hotfix_post_snap_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_hotfix_post_snap_probe_errors_shape
        CHECK (jsonb_typeof(probe_errors) = 'array'),

    -- Schema version is a positive integer (agent SUBMIT-result sends 1
    -- today; a genuinely new shape bumps schemaVersion + a new schema $id,
    -- never silently mutates v1).
    CONSTRAINT ck_endpoint_hotfix_post_snap_schema_version_range
        CHECK (schema_version >= 1),

    -- sourceUsed enums on the wire (16-char column = generous buffer for
    -- 'composite' + future additions; 'getHotfix' is 9 chars).
    CONSTRAINT ck_endpoint_hotfix_post_snap_installed_source_used
        CHECK (installed_source_used IN ('wua', 'getHotfix', 'none')),
    CONSTRAINT ck_endpoint_hotfix_post_snap_pending_source_used
        CHECK (pending_source_used IN ('wua', 'none')),
    CONSTRAINT ck_endpoint_hotfix_post_snap_health_source_used
        CHECK (health_source_used IN ('service', 'registry', 'composite', 'none')),

    -- Pre-truncation total counts are non-negative; pending_total_count
    -- has no upper CHECK (pendingByCategory rollup may exceed per-item
    -- cap). max_installed / max_pending agent constants.
    CONSTRAINT ck_endpoint_hotfix_post_snap_installed_count_range
        CHECK (installed_count >= 0),
    CONSTRAINT ck_endpoint_hotfix_post_snap_max_installed_range
        CHECK (max_installed >= 0),
    CONSTRAINT ck_endpoint_hotfix_post_snap_pending_total_count_range
        CHECK (pending_total_count >= 0),
    CONSTRAINT ck_endpoint_hotfix_post_snap_max_pending_range
        CHECK (max_pending >= 0),
    CONSTRAINT ck_endpoint_hotfix_post_snap_probe_duration_ms
        CHECK (probe_duration_ms IS NULL OR probe_duration_ms >= 0),

    -- Agent-health typed enums (parity with wire ServiceState — 4-state
    -- contract; UNKNOWN is the fail-closed "could not read" sentinel).
    CONSTRAINT ck_endpoint_hotfix_post_snap_wua_service_state
        CHECK (wua_service_state IN ('RUNNING', 'STOPPED', 'DISABLED', 'UNKNOWN')),
    CONSTRAINT ck_endpoint_hotfix_post_snap_bits_service_state
        CHECK (bits_service_state IN ('RUNNING', 'STOPPED', 'DISABLED', 'UNKNOWN')),

    -- notification_level: AUOptions registry value verbatim, bounded
    -- numeric string. NULL allowed; empty string normalized to NULL by the
    -- policy BEFORE write. Bounded regex prevents unbounded text leak
    -- while accepting all observed legitimate GPO variants.
    CONSTRAINT ck_endpoint_hotfix_post_snap_notification_level
        CHECK (notification_level IS NULL
               OR notification_level ~ '^[0-9]{1,4}$'),

    -- Composite FK to endpoint_devices — tenant column enforced at the
    -- DB layer (parity with V13/V17/V20). ON DELETE CASCADE: device
    -- removal removes its hotfix posture history.
    CONSTRAINT fk_endpoint_hotfix_post_snap_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    -- Pointer to the originating agent command-result. ON DELETE SET NULL
    -- preserves the history even if command-result retention cleans up
    -- the command-result row (parity with V13/V17/V20).
    CONSTRAINT fk_endpoint_hotfix_post_snap_command_result
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL
);

-- Partial UNIQUE on source_command_result_id (NULL allowed; agent SUBMIT
-- path enforces 1-snapshot-per-command-result, manual/test ingest paths
-- leave it NULL). The targetless ON CONFLICT DO NOTHING write path
-- catches both this partial UNIQUE and the (tenant, device, hash) UNIQUE
-- without aborting the transaction (Codex 019e81fe iter-3 P1.1).
CREATE UNIQUE INDEX uq_endpoint_hotfix_post_snap_source_cmd_result
    ON endpoint_hotfix_posture_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- Latest snapshot per device — primary query path for the WEB hotfix
-- posture view + future compliance evaluator. DESC order on collected_at +
-- created_at + id provides a stable tiebreaker the index can satisfy
-- without sorting in PG. Index name is 42 bytes (well under PostgreSQL's
-- 63-byte identifier limit).
CREATE INDEX idx_endpoint_hotfix_post_snap_tnt_dev_time
    ON endpoint_hotfix_posture_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- 2. endpoint_hotfix_posture_installed — per-installed-hotfix facets
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hotfix_posture_installed (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    -- KB identifier ('KB' + 4..10 digit MS knowledge-base id). The ONLY
    -- per-hotfix correlation key on the wire — NO update title, install
    -- client, command line, account name (the redaction boundary's
    -- forbidden fields are NOT columns here).
    kb_id                           VARCHAR(32)     NOT NULL,
    -- Wire contract permits null (Get-HotFix legitimately returns
    -- update entries without a parseable installed-on date for legacy
    -- patches).
    installed_on                    TIMESTAMPTZ,
    description                     VARCHAR(512),
    -- Stable replay ordinal so child rows reload in the same order they
    -- were emitted (deterministic test compare + future feature parity
    -- with V20 pattern).
    row_ordinal                     INTEGER         NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hotfix_post_installed PRIMARY KEY (id),

    CONSTRAINT ck_endpoint_hotfix_post_installed_kb_id_format
        CHECK (kb_id ~ '^KB[0-9]{4,10}$'),

    CONSTRAINT ck_endpoint_hotfix_post_installed_row_ordinal_range
        CHECK (row_ordinal >= 0),

    CONSTRAINT uq_endpoint_hotfix_post_installed_snap_ordinal
        UNIQUE (snapshot_id, row_ordinal),

    -- Composite FK to snapshots(id, tenant_id) ON DELETE CASCADE: tenant
    -- enforced at the DB layer; snapshot delete cleans up children.
    CONSTRAINT fk_endpoint_hotfix_post_installed_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_endpoint_hotfix_post_installed_snap
    ON endpoint_hotfix_posture_installed (snapshot_id, row_ordinal);

-- ---------------------------------------------------------------------
-- 3. endpoint_hotfix_posture_pending — per-pending-update facets
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hotfix_posture_pending (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    -- Reduced via deterministic precedence at the agent (wire contract
    -- §16.5): SECURITY > DEFINITION > CRITICAL > IMPORTANT > DRIVER >
    -- UPDATE_ROLLUP > FEATURE_PACK > SERVICE_PACK > OPTIONAL > TOOLS >
    -- UNCATEGORIZED.
    primary_category                VARCHAR(16)     NOT NULL,
    -- MSRC severity rating; UNSPECIFIED for non-security updates.
    severity                        VARCHAR(16)     NOT NULL,
    row_ordinal                     INTEGER         NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hotfix_post_pending PRIMARY KEY (id),

    -- (id, tenant_id) UNIQUE so the grand-child pending_kbs table can bind
    -- via composite FK (Codex 019e81fe iter-2 P1.2 — pending_kbs
    -- (pending_id, tenant_id) FK requires this matching parent unique).
    CONSTRAINT uq_endpoint_hotfix_post_pending_id_tnt
        UNIQUE (id, tenant_id),

    CONSTRAINT ck_endpoint_hotfix_post_pending_primary_category
        CHECK (primary_category IN (
            'SECURITY', 'DEFINITION', 'CRITICAL', 'IMPORTANT', 'DRIVER',
            'UPDATE_ROLLUP', 'FEATURE_PACK', 'SERVICE_PACK', 'OPTIONAL',
            'TOOLS', 'UNCATEGORIZED')),

    CONSTRAINT ck_endpoint_hotfix_post_pending_severity
        CHECK (severity IN (
            'CRITICAL', 'IMPORTANT', 'MODERATE', 'LOW', 'UNSPECIFIED')),

    CONSTRAINT ck_endpoint_hotfix_post_pending_row_ordinal_range
        CHECK (row_ordinal >= 0),

    CONSTRAINT uq_endpoint_hotfix_post_pending_snap_ordinal
        UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT fk_endpoint_hotfix_post_pending_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_endpoint_hotfix_post_pending_snap
    ON endpoint_hotfix_posture_pending (snapshot_id, row_ordinal);

-- ---------------------------------------------------------------------
-- 4. endpoint_hotfix_posture_pending_kbs — composite-FK normalized KB list
-- ---------------------------------------------------------------------
-- KB ids per pending update — normalized (NOT JSONB) so fleet / compliance
-- KB-based queries can index without JSONB array contains scans
-- (Codex 019e81fe iter-2 P1.5).
CREATE TABLE endpoint_hotfix_posture_pending_kbs (
    id                              UUID            NOT NULL,
    pending_id                      UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    kb_id                           VARCHAR(32)     NOT NULL,
    row_ordinal                     INTEGER         NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hotfix_post_pending_kbs PRIMARY KEY (id),

    CONSTRAINT ck_endpoint_hotfix_post_pending_kbs_kb_id_format
        CHECK (kb_id ~ '^KB[0-9]{4,10}$'),

    CONSTRAINT ck_endpoint_hotfix_post_pending_kbs_row_ordinal_range
        CHECK (row_ordinal >= 0),

    CONSTRAINT uq_endpoint_hotfix_post_pending_kbs_pending_ordinal
        UNIQUE (pending_id, row_ordinal),

    CONSTRAINT fk_endpoint_hotfix_post_pending_kbs_pending
        FOREIGN KEY (pending_id, tenant_id)
        REFERENCES endpoint_hotfix_posture_pending (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_endpoint_hotfix_post_pending_kbs_pending
    ON endpoint_hotfix_posture_pending_kbs (pending_id, row_ordinal);

-- KB-based fleet lookup index (compliance future: "which devices have
-- KB5036899 pending?"). tenant_id leads so a tenant scan is index-only.
CREATE INDEX idx_endpoint_hotfix_post_pending_kbs_tnt_kb
    ON endpoint_hotfix_posture_pending_kbs (tenant_id, kb_id);

-- ---------------------------------------------------------------------
-- 5. endpoint_hotfix_posture_pending_categories — pendingByCategory rollup
-- ---------------------------------------------------------------------
-- Full pre-truncation pendingByCategory distribution per snapshot
-- (preserved even when the per-item pending list is capped at 20 — Codex
-- 019e81fe iter-2 P1.1). UNIQUE (snapshot_id, category) enforces one row
-- per category per snapshot; row_ordinal lets ordered replay match the
-- agent emission order even though the category is the natural key.
CREATE TABLE endpoint_hotfix_posture_pending_categories (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    category                        VARCHAR(16)     NOT NULL,
    cnt                             INTEGER         NOT NULL,
    row_ordinal                     INTEGER         NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hotfix_post_pending_categories PRIMARY KEY (id),

    CONSTRAINT ck_endpoint_hotfix_post_pending_cats_category
        CHECK (category IN (
            'SECURITY', 'DEFINITION', 'CRITICAL', 'IMPORTANT', 'DRIVER',
            'UPDATE_ROLLUP', 'FEATURE_PACK', 'SERVICE_PACK', 'OPTIONAL',
            'TOOLS', 'UNCATEGORIZED')),

    CONSTRAINT ck_endpoint_hotfix_post_pending_cats_cnt_range
        CHECK (cnt >= 0),

    CONSTRAINT ck_endpoint_hotfix_post_pending_cats_row_ordinal_range
        CHECK (row_ordinal >= 0),

    -- One row per category per snapshot.
    CONSTRAINT uq_endpoint_hotfix_post_pending_cats_snap_category
        UNIQUE (snapshot_id, category),

    -- Stable replay ordinal (P2 from iter-3).
    CONSTRAINT uq_endpoint_hotfix_post_pending_cats_snap_ordinal
        UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT fk_endpoint_hotfix_post_pending_cats_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_endpoint_hotfix_post_pending_cats_snap
    ON endpoint_hotfix_posture_pending_categories (snapshot_id, row_ordinal);
