-- BE-024c diff summary cache (Faz 22.5 P2-A v2-c-pre, Codex 019e88b5 iter-5 AGREE).
--
-- v2-c-pre adds a cheap operational summary cache for BE-024 software diff and
-- BE-024b outdated-software diff so that v2-d grid exposure (SCHEMA_VERSION = 5)
-- can `LEFT JOIN` a unique row per (tenant, device) instead of running an inline
-- JSONB / window diff per grid row (which would taint page + export + count
-- preflight paths through DeviceGridQueryBuilder.fromAndJoins).
--
-- Two separate cache tables — Codex 019e88b5 iter-1 must_fix #1:
--   * software cache (BE-024) sources from `endpoint_software_inventory_state_history`
--     because the inventory snapshot table is an upsert-only "latest" row that
--     does NOT carry latest-1 (V18 design); the history append IS the source-of-truth.
--   * outdated cache (BE-024b) sources from `endpoint_outdated_software_snapshots`
--     directly (the canonical AG-036 snapshot table).
-- Putting both into one polymorphic table would force `(diff_type, conditional FK)`
-- gymnastics that PG cannot integrity-check; two tables keep composite FK integrity
-- per source.
--
-- Status enum (mirrors BE-024 / BE-024b DiffStatus, 4-value):
--   OK                    — 2+ source captures, computed delta (any counts).
--   NO_CHANGE             — 2+ captures but identical (all counts = 0).
--   INSUFFICIENT_HISTORY  — exactly 1 capture (only `to_*_id` set).
--   NO_HISTORY            — 0 captures (both source ids NULL).
--
-- Status shape + non-OK counts-zero invariants are enforced by CHECK constraints
-- (Codex 019e88b5 iter-2 must_fix #5: PG-level integrity gate, not application-layer).
-- All composite FKs cascade ON DELETE so a history/snapshot retention sweep cannot
-- leave a stale cache row pointing at a deleted source.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Add UNIQUE (id, tenant_id) on state history for the software cache FK.
--    Codex 019e88b5 iter-1 must_fix #1: composite source FK needs this unique
--    on the parent — `endpoint_software_inventory_state_history` did not have
--    it because it was only ever accessed through `tenant_id + device_id`
--    ordered scans.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT uq_endpoint_software_inventory_state_history_id_tenant
    UNIQUE (id, tenant_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. BE-024 software diff cache.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE endpoint_software_diff_cache (
    id                          UUID                     NOT NULL,
    tenant_id                   UUID                     NOT NULL,
    device_id                   UUID                     NOT NULL,
    -- Source pair refers to history captures (BE-024 algorithm reads
    -- latest two history rows; INSUFFICIENT_HISTORY = single capture with
    -- only `to_history_id` set; NO_HISTORY = zero captures both null).
    from_history_id             UUID                     NULL,
    to_history_id               UUID                     NULL,
    status                      VARCHAR(32)              NOT NULL
        CONSTRAINT swdc_status_ck
        CHECK (status IN ('OK', 'NO_CHANGE', 'INSUFFICIENT_HISTORY', 'NO_HISTORY')),
    added_count                 INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT swdc_added_count_ck CHECK (added_count >= 0),
    removed_count               INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT swdc_removed_count_ck CHECK (removed_count >= 0),
    version_changed_count       INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT swdc_version_changed_count_ck CHECK (version_changed_count >= 0),
    computed_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT swdc_tenant_device_uq UNIQUE (tenant_id, device_id),
    -- device tenant integrity (cascade so device removal sweeps cache).
    CONSTRAINT swdc_device_fk FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,
    -- source pair tenant integrity (cascade so history retention sweeps cache;
    -- Codex 019e88b5 iter-2 must_fix #3: ON DELETE CASCADE not SET NULL —
    -- the status shape invariant forbids OK/NO_CHANGE without both source ids).
    CONSTRAINT swdc_from_history_fk FOREIGN KEY (from_history_id, tenant_id)
        REFERENCES endpoint_software_inventory_state_history (id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT swdc_to_history_fk FOREIGN KEY (to_history_id, tenant_id)
        REFERENCES endpoint_software_inventory_state_history (id, tenant_id)
        ON DELETE CASCADE,
    -- Status shape invariant — pairs `status` with the presence/absence of
    -- the source ids so a row can never claim OK while missing source pair.
    CONSTRAINT swdc_status_shape_ck CHECK (
        (status = 'NO_HISTORY'
            AND from_history_id IS NULL AND to_history_id IS NULL)
        OR (status = 'INSUFFICIENT_HISTORY'
            AND from_history_id IS NULL AND to_history_id IS NOT NULL)
        OR (status IN ('OK', 'NO_CHANGE')
            AND from_history_id IS NOT NULL AND to_history_id IS NOT NULL)
    ),
    -- Non-OK status MUST carry zero counts (NO_CHANGE is identical pair so
    -- all deltas are 0; INSUFFICIENT_HISTORY / NO_HISTORY have no comparison).
    CONSTRAINT swdc_non_ok_counts_zero_ck CHECK (
        status = 'OK'
        OR (added_count = 0 AND removed_count = 0 AND version_changed_count = 0)
    )
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. BE-024b outdated software diff cache.
--    V20 already declares UNIQUE (id, tenant_id) on
--    endpoint_outdated_software_snapshots, so no parent ALTER is needed here.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE endpoint_outdated_software_diff_cache (
    id                              UUID                     NOT NULL,
    tenant_id                       UUID                     NOT NULL,
    device_id                       UUID                     NOT NULL,
    -- Source pair refers to canonical AG-036 snapshots (DTO carries snapshot id,
    -- not history id — outdated has no append-only state history table).
    from_snapshot_id                UUID                     NULL,
    to_snapshot_id                  UUID                     NULL,
    status                          VARCHAR(32)              NOT NULL
        CONSTRAINT osdc_status_ck
        CHECK (status IN ('OK', 'NO_CHANGE', 'INSUFFICIENT_HISTORY', 'NO_HISTORY')),
    added_count                     INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT osdc_added_count_ck CHECK (added_count >= 0),
    removed_count                   INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT osdc_removed_count_ck CHECK (removed_count >= 0),
    version_changed_count           INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT osdc_version_changed_count_ck CHECK (version_changed_count >= 0),
    -- Outdated diff carries a 4th count for availableVersionBumped (canonical
    -- packageId.toLowerCase + installedVersion unchanged + availableVersion
    -- changed; mirrors BE-024b DTO).
    available_version_bumped_count  INTEGER                  NOT NULL DEFAULT 0
        CONSTRAINT osdc_available_version_bumped_count_ck
        CHECK (available_version_bumped_count >= 0),
    computed_at                     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT osdc_tenant_device_uq UNIQUE (tenant_id, device_id),
    CONSTRAINT osdc_device_fk FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT osdc_from_snapshot_fk FOREIGN KEY (from_snapshot_id, tenant_id)
        REFERENCES endpoint_outdated_software_snapshots (id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT osdc_to_snapshot_fk FOREIGN KEY (to_snapshot_id, tenant_id)
        REFERENCES endpoint_outdated_software_snapshots (id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT osdc_status_shape_ck CHECK (
        (status = 'NO_HISTORY'
            AND from_snapshot_id IS NULL AND to_snapshot_id IS NULL)
        OR (status = 'INSUFFICIENT_HISTORY'
            AND from_snapshot_id IS NULL AND to_snapshot_id IS NOT NULL)
        OR (status IN ('OK', 'NO_CHANGE')
            AND from_snapshot_id IS NOT NULL AND to_snapshot_id IS NOT NULL)
    ),
    CONSTRAINT osdc_non_ok_counts_zero_ck CHECK (
        status = 'OK'
        OR (added_count = 0 AND removed_count = 0
            AND version_changed_count = 0 AND available_version_bumped_count = 0)
    )
);
