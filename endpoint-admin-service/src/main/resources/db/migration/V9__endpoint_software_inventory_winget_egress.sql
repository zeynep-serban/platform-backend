-- BE-021A — Install preflight (Faz 22.5) — additive extension to the
-- BE-020I `endpoint_software_inventory_snapshots` read model so the
-- AG-026A `inventory.wingetEgress` evidence (read-only WinGet source
-- list parser + fixed 7zip.7zip package query + DNS/TCP/HTTPS
-- reachability rollup) gets materialized alongside the BE-020I summary
-- payload it ships with.
--
-- Why "in place" instead of a new table (Codex 019e6b88 plan-time AGREE):
--
--   The egress evidence belongs to the same per-device, per-tenant
--   inventory snapshot the BE-020I summary already owns — every
--   COLLECT_INVENTORY result that carries `includeWinGetEgress=true`
--   also carries the `software` summary, and the install preflight
--   service needs both joined in a single row read. A sibling table
--   would force an extra join on every preflight GET (BE-021A) without
--   buying schema flexibility, because the egress shape is already
--   version-pinned via `winget_egress_schema_version` and the JSONB
--   blob.
--
-- Why these four columns (not more):
--
--   * `winget_egress JSONB` — full AG-026A SourceEgressReadiness
--     payload (sanitised, fail-closed validated by
--     `WinGetEgressPayloadPolicy`). The full blob is preserved so
--     BE-021A and (future) BE-023 can read source list / package
--     query / DNS / TCP / HTTPS sub-fields without re-shaping the
--     read model.
--   * `winget_egress_collected_at TIMESTAMPTZ` — agent-side timestamp
--     of when the preflight ran. Used to detect stale evidence
--     (BE-021A WARN `inventory_stale` is computed against this and
--     `apps_collected_at` independently).
--   * `latest_winget_egress_command_result_id UUID` — FK to the
--     `endpoint_command_results` row whose `result_payload` carried
--     this evidence. ON DELETE SET NULL so result cleanup does NOT
--     wipe the snapshot evidence (matches the BE-020I summary /
--     full result FK semantics).
--   * `winget_egress_schema_version INTEGER` — pin against AG-026A
--     wire-shape drift. BE-021A treats a missing/different version
--     as `BLOCK winget_egress_schema_unsupported` (Codex 019e6b88
--     P1 risk control). NULL means "evidence not yet ingested" —
--     not "version 0".
--
-- Backward compat: every column is NULL-able. Existing
-- BE-020I snapshots stay valid; an ingest path that does NOT carry
-- `includeWinGetEgress=true` leaves all four fields untouched.
--
-- Migration sequence guard: V8 (BE-020I) is the last applied
-- migration on origin/main. V9 claims this slot exclusively for
-- BE-021A.
--
-- Codex plan-time consensus: thread 019e6b88 AGREE.

ALTER TABLE endpoint_software_inventory_snapshots
    ADD COLUMN winget_egress                          JSONB,
    ADD COLUMN winget_egress_collected_at             TIMESTAMPTZ,
    ADD COLUMN latest_winget_egress_command_result_id UUID,
    ADD COLUMN winget_egress_schema_version           INTEGER;

ALTER TABLE endpoint_software_inventory_snapshots
    ADD CONSTRAINT fk_endpoint_software_inventory_snapshots_egress_result
        FOREIGN KEY (latest_winget_egress_command_result_id)
        REFERENCES endpoint_command_results (id)
        ON DELETE SET NULL;

-- Schema version sanity: NULL is allowed (evidence not yet ingested),
-- but a non-NULL value must be the canonical AG-026A schema version 1.
-- A future schema bump SHOULD ship as a separate Flyway migration that
-- relaxes this CHECK (or recompiles it) so the constraint stays
-- authoritative for the current contract.
ALTER TABLE endpoint_software_inventory_snapshots
    ADD CONSTRAINT ck_endpoint_software_inventory_snapshots_egress_schema
        CHECK (winget_egress_schema_version IS NULL
            OR winget_egress_schema_version = 1);

-- Pairing invariant: when egress evidence is present (JSONB non-null)
-- the collected-at timestamp MUST also be present. Prevents partial
-- ingests that would silently confuse BE-021A's freshness check.
ALTER TABLE endpoint_software_inventory_snapshots
    ADD CONSTRAINT ck_endpoint_software_inventory_snapshots_egress_pair
        CHECK ((winget_egress IS NULL AND winget_egress_collected_at IS NULL)
            OR (winget_egress IS NOT NULL AND winget_egress_collected_at IS NOT NULL));

-- BE-021A admin install-preflight GET joins inventory + (future)
-- catalog by tenant_id + device_id and reads the egress evidence
-- inline; the existing `idx_endpoint_software_inventory_snapshots_device`
-- already covers the device lookup. No new index needed at this
-- stage — the egress columns are read alongside the rest of the
-- snapshot row.
