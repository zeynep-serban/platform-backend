-- BE — Endpoint Outdated Software (Faz 22.5, AG-036 outdated-software ingest).
-- Mirrors the BE-022 hardware-inventory (V13) + AG-033 device-health (V17) +
-- BE-024 software-state history (V18) append-only precedents EXACTLY: an
-- append-only snapshot table with a composite-FK child facet table, a partial
-- UNIQUE idempotency index on source_command_result_id, a payload-hash dedupe
-- column, and a latest-per-device composite index.
--
-- Wire contract: schema/endpoint-outdated-software-payload-v1.schema.json
-- (gitops PR #1145, merged commit 73f0db0f). The AG-036 outdated-software
-- probe block is carried under the COLLECT_INVENTORY result at
-- details.inventory.outdatedSoftware (schemaVersion=1). Source of truth =
-- platform-agent internal/inventory/outdated_software.go OutdatedSoftwareResult
-- (platform-agent PR #38, merged sha a29eef49).
--
-- The probe is read-only ('winget upgrade --include-returning-apps --source
-- winget') — it NEVER mutates package state. Backend ingest is a persist/query
-- path; it MUST NOT trigger any agent-side mutation from this payload.
--
-- REDACTION BOUNDARY (security invariant — machine-enforced at the agent +
-- the backend OutdatedSoftwarePayloadPolicy):
-- the per-package wire shape is EXACTLY {packageId, installedVersion,
-- availableVersion} — NO display name, publisher, install location, license,
-- or download URL. None of those forbidden fields have a column in the child
-- packages table; the OutdatedSoftwarePayloadPolicy fail-closed rejects any
-- off-contract package key BEFORE the parent command-result row is persisted.
-- probeError.summary is bounded operator text (no raw errno / path / display
-- name).
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) and
-- probe_complete=false (any probeError or a no-source run) are persisted AS
-- evidence (the agent still emits canonical metadata so the backend records
-- "probe not supported / incomplete here" instead of treating absence as a
-- failed ingest). Consumers MUST NOT render an incomplete probe as "fully up
-- to date".
--
-- This migration delivers two DDL slices:
--
--   1. endpoint_outdated_software_snapshots — append-only history of
--      outdated-software observations (upgradeable package count + truncation
--      provenance + probe source) harvested via the existing COLLECT_INVENTORY
--      agent contract. Each row captures the scalar summary at one point in
--      time plus an opaque redacted_payload JSONB carrying the full validated
--      block (within the redaction boundary).
--
--   2. endpoint_outdated_software_packages — per-upgradeable-package facets
--      for a snapshot. Composite (snapshot_id, tenant_id) FK enforces tenant
--      integrity at the DB layer (parity with V13/V17). ON DELETE CASCADE
--      means cleanup is a single DELETE on the snapshot row.
--
-- Append-only history is intentional (parity with V13/V17/V18). The latest
-- snapshot is a query (ORDER BY collected_at DESC, created_at DESC, id DESC),
-- NOT an "upsert into current row" pattern.
--
-- IDEMPOTENCY + ATOMICITY (reuse BE-024): the snapshot insert uses a native
-- INSERT ... ON CONFLICT (source_command_result_id) WHERE
-- source_command_result_id IS NOT NULL DO NOTHING against the partial-unique
-- index below — a duplicate command-result is a clean no-op; every other
-- CHECK / FK violation propagates and rolls the whole ingest transaction back
-- (no broad-catch swallow).
--
-- MIGRATION SEQUENCE GUARD: V19 (endpoint_prohibited_software_rules, BE-025)
-- was the last applied migration on origin/main. V20 claims this slot
-- exclusively for BE AG-036 outdated-software. The V12 (id, tenant_id) UNIQUE
-- on endpoint_devices is reused here for the composite FK — it is NOT
-- recreated.

-- ---------------------------------------------------------------------
-- 1. endpoint_outdated_software_snapshots — append-only history
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_outdated_software_snapshots (
    id                              UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_id                       UUID            NOT NULL,
    -- Pointer to the agent command-result that delivered this snapshot.
    -- NULL is allowed so result cleanup (retention) does not cascade
    -- delete the outdated-software history; UNIQUE (partial) because the
    -- agent SUBMIT result path must be idempotent — re-submitting the same
    -- command result must NOT create a second snapshot (the native ON
    -- CONFLICT DO NOTHING write path is the authoritative race-safe guard).
    source_command_result_id        UUID,
    schema_version                  SMALLINT        NOT NULL,

    -- supported = false on non-Windows runtimes (the agent still emits
    -- canonical metadata so the backend persists "probe not supported here"
    -- instead of treating absence as a failed ingest).
    supported                       BOOLEAN         NOT NULL,
    -- probe_complete = false when any probeError is present OR sourceUsed =
    -- none (fail-closed: treat as "evidence incomplete", never render an
    -- incomplete probe as "fully up to date").
    probe_complete                  BOOLEAN         NOT NULL,

    -- Number of upgradeable packages reported (== upgrade[] length). Capped
    -- at max_upgrade (512); upgrade_count == max_upgrade signals "possibly
    -- truncated" (the agent parser caps before upgrade_truncated is
    -- evaluated — known v1 limitation tracked as a follow-up).
    upgrade_count                   INTEGER         NOT NULL,
    upgrade_truncated               BOOLEAN         NOT NULL,
    max_upgrade                     INTEGER         NOT NULL,

    -- Probe source: winget = read-only 'winget upgrade
    -- --include-returning-apps --source winget'; none = no probe ran
    -- (unsupported runtime).
    source_used                     VARCHAR(8)      NOT NULL,
    probe_duration_ms               INTEGER,

    -- Integrity + provenance.
    payload_hash_sha256             VARCHAR(64)     NOT NULL,
    redacted_payload                JSONB           NOT NULL DEFAULT '{}'::jsonb,
    probe_errors                    JSONB           NOT NULL DEFAULT '[]'::jsonb,

    collected_at                    TIMESTAMPTZ     NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version                         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_outdated_software_snapshots PRIMARY KEY (id),

    -- (id, tenant_id) UNIQUE so the child packages table can bind via
    -- composite FK and the tenant column is physically enforced
    -- (parity with V13/V17 snapshots).
    CONSTRAINT uq_endpoint_outdated_software_snapshots_id_tenant
        UNIQUE (id, tenant_id),

    -- payload_hash_sha256 is stored as 64-char lowercase SHA-256 hex.
    -- The CHECK anchors the exact shape; the dedupe query relies on a
    -- direct VARCHAR `=` via cast(:hash as string) (never lower(bytea)).
    CONSTRAINT ck_endpoint_outdated_software_snapshots_hash_format
        CHECK (payload_hash_sha256 ~ '^[a-f0-9]{64}$'),

    CONSTRAINT ck_endpoint_outdated_software_snapshots_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_outdated_software_snapshots_probe_errors_shape
        CHECK (jsonb_typeof(probe_errors) = 'array'),

    -- Schema version is a positive integer (agent SUBMIT-result sends 1
    -- today; a genuinely new shape bumps schemaVersion + a new schema $id,
    -- never silently mutates v1).
    CONSTRAINT ck_endpoint_outdated_software_snapshots_schema_version_range
        CHECK (schema_version >= 1),

    -- sourceUsed enum on the wire is {winget, none}.
    CONSTRAINT ck_endpoint_outdated_software_snapshots_source_used
        CHECK (source_used IN ('winget', 'none')),

    -- upgrade_count is non-negative and never exceeds the agent cap.
    CONSTRAINT ck_endpoint_outdated_software_snapshots_upgrade_count_range
        CHECK (upgrade_count >= 0 AND upgrade_count <= max_upgrade),

    -- max_upgrade is the agent-side const (MaxOutdatedPackages = 512). Pinned
    -- to a positive value; the policy fail-closed rejects any non-512 value
    -- on the wire (contract const).
    CONSTRAINT ck_endpoint_outdated_software_snapshots_max_upgrade_range
        CHECK (max_upgrade >= 0),

    CONSTRAINT ck_endpoint_outdated_software_snapshots_probe_duration_ms
        CHECK (probe_duration_ms IS NULL OR probe_duration_ms >= 0),

    -- Composite FK to endpoint_devices — tenant column enforced at the
    -- DB layer (parity with V13/V17). ON DELETE CASCADE: device removal
    -- removes its outdated-software history.
    CONSTRAINT fk_endpoint_outdated_software_snapshots_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    -- Pointer to the originating agent command-result. ON DELETE SET NULL
    -- preserves the history even if command-result retention cleans up the
    -- command-result row (parity with V13/V17).
    CONSTRAINT fk_endpoint_outdated_software_snapshots_command_result
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL
);

-- Partial UNIQUE on source_command_result_id (NULL allowed; agent SUBMIT
-- path enforces 1-snapshot-per-command-result, manual/test ingest paths
-- leave it NULL). The native ON CONFLICT write path repeats this predicate
-- as the conflict target. Mirrors V13/V17/V18.
CREATE UNIQUE INDEX uq_endpoint_outdated_software_snapshots_source_cmd_result
    ON endpoint_outdated_software_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- Latest snapshot per device — primary query path for the WEB outdated-
-- software view + future compliance evaluator. DESC order on collected_at +
-- created_at + id provides a stable tiebreaker the index can satisfy without
-- sorting in PG. The unabbreviated "..._tenant_device_time" name is 59 bytes
-- (under PostgreSQL's 63-byte identifier limit), so no abbreviation is needed.
CREATE INDEX idx_endpoint_outdated_software_snapshots_tenant_device_time
    ON endpoint_outdated_software_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- 2. endpoint_outdated_software_packages — per-package facets per snapshot
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_outdated_software_packages (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    -- Stable winget package id (e.g. '7zip.7zip'). The ONLY package-level
    -- correlation key on the wire — NO display name / publisher / install
    -- location / license / download URL (the redaction boundary's package
    -- row is exactly {packageId, installedVersion, availableVersion}). The
    -- contract caps packageId at 256 chars.
    package_id                      VARCHAR(256)    NOT NULL,
    -- Currently-installed version string (public, non-PII). Contract maxLength
    -- 128.
    installed_version               VARCHAR(128)    NOT NULL,
    -- Available (newer) version string (public, non-PII). The from/to pair is
    -- what makes the "outdated" signal actionable. Contract maxLength 128.
    available_version               VARCHAR(128)    NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_outdated_software_packages PRIMARY KEY (id),

    -- packageId has no whitespace (contract pattern ^\S+$) — it is an id, not
    -- a human-facing display name. Enforced at the DB layer so a stray
    -- display-name leak (which would contain spaces) is rejected.
    CONSTRAINT ck_endpoint_outdated_software_packages_package_id
        CHECK (package_id ~ '^\S+$'),

    -- Composite FK to the parent snapshot. (snapshot_id, tenant_id) forces
    -- the package row to share the tenant of its snapshot — a service bug
    -- cannot persist a package under the wrong tenant.
    CONSTRAINT fk_endpoint_outdated_software_packages_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_outdated_software_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

-- Per-snapshot lookup (latest-snapshot rendering joins packages by
-- snapshot_id; tenant_id is also indexed for tenant-scoped pruning).
CREATE INDEX idx_endpoint_outdated_software_packages_snapshot
    ON endpoint_outdated_software_packages (snapshot_id, tenant_id);
