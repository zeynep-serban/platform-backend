-- BE — Endpoint Device Health (Faz 22.5, AG-033 device-health ingest).
-- Mirrors the BE-022 hardware-inventory precedent (V13) EXACTLY: an
-- append-only snapshot table with a composite-FK child facet table,
-- a partial UNIQUE idempotency index on source_command_result_id, a
-- payload-hash dedupe column, and a latest-per-device composite index.
--
-- Wire contract: schema/endpoint-device-health-payload-v1.schema.json
-- (gitops PR #1143, merged commit ddd5e326). The AG-033 device-health
-- probe block is carried under the COLLECT_INVENTORY result at
-- details.inventory.deviceHealth (schemaVersion=1). Source of truth =
-- platform-agent internal/inventory/device_health.go DeviceHealthResult
-- (platform-agent PR #36, MERGED).
--
-- Redaction boundary (machine-enforced at the agent + the backend
-- HardwareInventoryPayloadPolicy-style DeviceHealthPayloadPolicy):
-- disk facets carry ONLY driveLetter (^[A-Z]:$) — NO volume label,
-- serial, filesystem, mount path, or GUID. lastBootEpochSec is unix
-- seconds (no tz/locale string). probeError.summary is bounded operator
-- text. None of those forbidden fields have a column here.
--
-- This migration delivers two DDL slices:
--
--   1. endpoint_device_health_snapshots — append-only history of
--      device-health observations (disk free %, memory utilization %,
--      uptime / last-boot, warning booleans) harvested via the existing
--      COLLECT_INVENTORY agent contract. Each row captures the scalar
--      health summary at one point in time plus an opaque
--      `redacted_payload` JSONB carrying the full validated block
--      (memory commit fields, per-disk facets, etc.) within the
--      redaction boundary.
--
--   2. endpoint_device_health_disks — per-fixed-disk facets for a
--      snapshot. Composite (snapshot_id, tenant_id) FK enforces tenant
--      integrity at the DB layer (parity with V13 hardware-inventory
--      disks). ON DELETE CASCADE means cleanup is a single DELETE on
--      the snapshot row.
--
-- Append-only history is intentional (parity with V13). The latest
-- snapshot is a query (ORDER BY collected_at DESC, created_at DESC,
-- id DESC), NOT an "upsert into current row" pattern.
--
-- Migration sequence guard: V16 (policy_change_approvals decision/status
-- columns) was the last applied migration on origin/main. V17 claims
-- this slot exclusively for BE device-health. The V12 (id, tenant_id)
-- UNIQUEs on endpoint_devices / endpoint_commands are reused here for
-- composite FKs — they are NOT recreated.

-- ---------------------------------------------------------------------
-- 1. endpoint_device_health_snapshots — append-only history
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_device_health_snapshots (
    id                              UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_id                       UUID            NOT NULL,
    -- Pointer to the agent command-result that delivered this snapshot.
    -- NULL is allowed so result cleanup (retention) does not cascade
    -- delete the device-health history; UNIQUE (partial) because the
    -- agent SUBMIT result path must be idempotent — re-submitting the
    -- same command result must NOT create a second snapshot
    -- (EndpointDeviceHealthService catches DataIntegrityViolationException
    -- and returns the existing row).
    source_command_result_id        UUID,
    schema_version                  SMALLINT        NOT NULL,

    -- supported = false on non-Windows runtimes (the agent still emits
    -- canonical metadata so the backend persists "probe not supported
    -- here" instead of treating absence as a failed ingest).
    supported                       BOOLEAN         NOT NULL,
    -- probe_complete = false when any probeError is present (fail-closed:
    -- treat as "evidence incomplete", never render zero-values as a
    -- healthy device).
    probe_complete                  BOOLEAN         NOT NULL,

    -- OR'd over the FULL pre-truncation disk enumeration (not just the
    -- post-truncation fixed_disks array).
    any_low_disk                    BOOLEAN         NOT NULL,

    -- Disk enumeration provenance.
    fixed_disk_count                INTEGER         NOT NULL,
    fixed_disks_truncated           BOOLEAN         NOT NULL,
    max_fixed_disks                 INTEGER         NOT NULL,

    -- Memory summary scalars (web view: used % + pressure badge). Full
    -- byte totals + commit fields live in redacted_payload.
    memory_used_percent             SMALLINT,
    memory_high_pressure            BOOLEAN,

    -- Uptime summary scalars (web view: uptime + long-uptime badge).
    uptime_days                     INTEGER,
    uptime_seconds                  BIGINT,
    -- Unix seconds, NOT a local-time string (no tz/locale leak).
    last_boot_epoch_sec             BIGINT,
    long_uptime_warning             BOOLEAN,

    -- Probe source: win32 = direct Win32 syscalls; none = no probe ran
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

    CONSTRAINT pk_endpoint_device_health_snapshots PRIMARY KEY (id),

    -- (id, tenant_id) UNIQUE so the child disks table can bind via
    -- composite FK and the tenant column is physically enforced
    -- (parity with V13 hardware-inventory snapshots).
    CONSTRAINT uq_endpoint_device_health_snapshots_id_tenant
        UNIQUE (id, tenant_id),

    -- payload_hash_sha256 is stored as 64-char lowercase SHA-256 hex.
    -- The CHECK anchors the exact shape; the dedupe query relies on a
    -- direct VARCHAR `=` (never lower(bytea)).
    CONSTRAINT ck_endpoint_device_health_snapshots_hash_format
        CHECK (payload_hash_sha256 ~ '^[a-f0-9]{64}$'),

    CONSTRAINT ck_endpoint_device_health_snapshots_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_device_health_snapshots_probe_errors_shape
        CHECK (jsonb_typeof(probe_errors) = 'array'),

    -- Schema version is a positive integer (agent SUBMIT-result sends 1
    -- today; a genuinely new shape bumps schemaVersion + a new schema
    -- $id, never silently mutates v1).
    CONSTRAINT ck_endpoint_device_health_snapshots_schema_version_range
        CHECK (schema_version >= 1),

    -- sourceUsed enum on the wire is {win32, none}.
    CONSTRAINT ck_endpoint_device_health_snapshots_source_used
        CHECK (source_used IN ('win32', 'none')),

    -- Percentages are 0..100 when present (agent caps at 100).
    CONSTRAINT ck_endpoint_device_health_snapshots_memory_used_percent
        CHECK (memory_used_percent IS NULL
               OR (memory_used_percent >= 0 AND memory_used_percent <= 100)),

    -- Non-negative enumeration / duration scalars.
    CONSTRAINT ck_endpoint_device_health_snapshots_fixed_disk_count
        CHECK (fixed_disk_count >= 0),

    CONSTRAINT ck_endpoint_device_health_snapshots_max_fixed_disks
        CHECK (max_fixed_disks >= 0),

    CONSTRAINT ck_endpoint_device_health_snapshots_uptime_days
        CHECK (uptime_days IS NULL OR uptime_days >= 0),

    CONSTRAINT ck_endpoint_device_health_snapshots_uptime_seconds
        CHECK (uptime_seconds IS NULL OR uptime_seconds >= 0),

    CONSTRAINT ck_endpoint_device_health_snapshots_last_boot_epoch_sec
        CHECK (last_boot_epoch_sec IS NULL OR last_boot_epoch_sec >= 0),

    CONSTRAINT ck_endpoint_device_health_snapshots_probe_duration_ms
        CHECK (probe_duration_ms IS NULL OR probe_duration_ms >= 0),

    -- Composite FK to endpoint_devices — tenant column enforced at the
    -- DB layer (parity with V13). ON DELETE CASCADE: device removal
    -- removes its device-health history.
    CONSTRAINT fk_endpoint_device_health_snapshots_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    -- Pointer to the originating agent command-result. ON DELETE SET
    -- NULL preserves the history even if command-result retention
    -- cleans up the command-result row (parity with V13).
    CONSTRAINT fk_endpoint_device_health_snapshots_command_result
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL
);

-- Partial UNIQUE on source_command_result_id (NULL allowed; agent
-- SUBMIT path enforces 1-snapshot-per-command-result, manual/test
-- ingest paths leave it NULL).
CREATE UNIQUE INDEX uq_endpoint_device_health_snapshots_source_cmd_result
    ON endpoint_device_health_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- Latest snapshot per device — primary query path for the WEB-013-style
-- device-health view + future device-health compliance evaluator. DESC
-- order on collected_at + created_at + id provides a stable tiebreaker
-- the index can satisfy without sorting in PG.
CREATE INDEX idx_endpoint_device_health_snapshots_tenant_device_time
    ON endpoint_device_health_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- 2. endpoint_device_health_disks — per-fixed-disk facets per snapshot
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_device_health_disks (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    -- Uppercase drive letter only (^[A-Z]:$). NO label / serial /
    -- filesystem / mount path / GUID — the redaction boundary's disk
    -- row is exactly {driveLetter, totalBytes, freeBytes, freePercent,
    -- lowDiskWarning}.
    drive_letter                    VARCHAR(2)      NOT NULL,
    total_bytes                     BIGINT,
    -- freeBytesAvailableToCaller (LocalSystem-writable) — the correct
    -- denominator for a "can this install succeed?" gate.
    free_bytes                      BIGINT,
    free_percent                    SMALLINT,
    low_disk_warning                BOOLEAN,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_device_health_disks PRIMARY KEY (id),

    -- driveLetter shape enforced at the DB layer — the redaction
    -- boundary's only disk identifier.
    CONSTRAINT ck_endpoint_device_health_disks_drive_letter
        CHECK (drive_letter ~ '^[A-Z]:$'),

    CONSTRAINT ck_endpoint_device_health_disks_total_bytes
        CHECK (total_bytes IS NULL OR total_bytes >= 0),

    CONSTRAINT ck_endpoint_device_health_disks_free_bytes
        CHECK (free_bytes IS NULL OR free_bytes >= 0),

    CONSTRAINT ck_endpoint_device_health_disks_free_percent
        CHECK (free_percent IS NULL
               OR (free_percent >= 0 AND free_percent <= 100)),

    -- Composite FK to the parent snapshot. (snapshot_id, tenant_id)
    -- forces the disk row to share the tenant of its snapshot — a
    -- service bug cannot persist a disk under the wrong tenant.
    CONSTRAINT fk_endpoint_device_health_disks_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_device_health_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

-- Per-snapshot lookup (latest-snapshot rendering joins disks by
-- snapshot_id; tenant_id is also indexed for tenant-scoped pruning).
CREATE INDEX idx_endpoint_device_health_disks_snapshot
    ON endpoint_device_health_disks (snapshot_id, tenant_id);
