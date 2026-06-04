-- V39 — Faz 21.1 Cleanup C4 A2 slice-2 — diagnostics org expansion
--       (mirrors the V38 device_health pilot; Codex 019e93cd pattern AGREE)
--
-- BOUNDARY: org-expands the diagnostics family (root
-- endpoint_diagnostics_snapshots + detail endpoint_diagnostics_probe_errors)
-- and flips its 2 tenant-composite FKs to org-composite. Second leaf family
-- after the V38 device_health pilot — same family-vertical pattern (no
-- legacy-NULL coupling: these tables never had org_id, so the full vertical
-- ships in one migration). Does NOT drop tenant_id (A6) and does NOT change
-- JPA entities / repository queries / DeviceGridQueryBuilder JOIN (reads stay
-- tenant-keyed = org_id; read-column switch + org mirror indexes are A5). The
-- single-column source_command_result_id FK (-> command_results(id) ON DELETE
-- SET NULL) is NOT tenant-composite and is out of scope.
--
-- WHY SOUND: child org_id is made NON-NULL (CHECK) + device_id/snapshot_id are
-- NOT NULL; parents carry UNIQUE(id, org_id) (devices V34 + snapshots added
-- here) + org_id NOT NULL (devices V36 LIVE) → MATCH SIMPLE rejects cross-org
-- rows 23503. org_id = tenant_id universal (backfill + V29 trigger + match
-- CHECK) → lossless flip vs the old tenant-composite FK.
--
-- ROLLBACK / LOCK: identical to V38 — forward-only (F21-R29); tenant_id stays;
-- additive org_id is trigger-maintained; ADD ... NOT VALID instant + VALIDATE
-- scans tiny tables; endpoint-admin not in prod (D30 BLOCKED).
--
-- References: V23 (diagnostics create), V29 (compat trigger fn — reused),
--   V34/V36 (devices parent UNIQUE + non-null, LIVE), V37/V38 (FK-flip
--   pattern), docs/faz-21/c4-a1-fk-graph-manifest.md, Codex 019e93a1/019e93cd.

-- ============================================================
-- Phase 1: org_id column + backfill + compat trigger + index (both tables).
-- ============================================================

ALTER TABLE endpoint_diagnostics_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_diagnostics_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_diag_snap_org_id ON endpoint_diagnostics_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_diag_snap_org_id_compat ON endpoint_diagnostics_snapshots;
CREATE TRIGGER endpoint_diag_snap_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_diagnostics_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_diagnostics_probe_errors ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_diagnostics_probe_errors SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_diag_pe_org_id ON endpoint_diagnostics_probe_errors(org_id);
DROP TRIGGER IF EXISTS endpoint_diag_pe_org_id_compat ON endpoint_diagnostics_probe_errors;
CREATE TRIGGER endpoint_diag_pe_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_diagnostics_probe_errors
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ============================================================
-- Phase 2: org invariants — match CHECK + non-null CHECK (both tables).
-- ============================================================

ALTER TABLE endpoint_diagnostics_snapshots
    ADD CONSTRAINT endpoint_diag_snap_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_diagnostics_snapshots
    VALIDATE CONSTRAINT endpoint_diag_snap_org_id_match;
ALTER TABLE endpoint_diagnostics_snapshots
    ADD CONSTRAINT endpoint_diag_snap_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_diagnostics_snapshots
    VALIDATE CONSTRAINT endpoint_diag_snap_org_id_not_null;

ALTER TABLE endpoint_diagnostics_probe_errors
    ADD CONSTRAINT endpoint_diag_pe_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_diagnostics_probe_errors
    VALIDATE CONSTRAINT endpoint_diag_pe_org_id_match;
ALTER TABLE endpoint_diagnostics_probe_errors
    ADD CONSTRAINT endpoint_diag_pe_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_diagnostics_probe_errors
    VALIDATE CONSTRAINT endpoint_diag_pe_org_id_not_null;

-- ============================================================
-- Phase 3: snapshots UNIQUE(id, org_id) — the org-composite FK target.
-- ============================================================

ALTER TABLE endpoint_diagnostics_snapshots
    ADD CONSTRAINT endpoint_diag_snap_id_org_id_key UNIQUE (id, org_id);

-- ============================================================
-- Phase 4: preflight fail-loud (parent org-match + child org-consistency).
-- ============================================================

DO $$
DECLARE
    bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_diagnostics_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V39 preflight: % diagnostics_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_diagnostics_probe_errors p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_diagnostics_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V39 preflight: % diagnostics_probe_errors rows have no snapshots(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_diagnostics_probe_errors p
        JOIN endpoint_diagnostics_snapshots s ON s.id = p.snapshot_id
        WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V39 preflight: % diagnostics_probe_errors rows whose org_id <> parent snapshot org_id (corruption)', bad; END IF;
END $$;

-- ============================================================
-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, ON DELETE
-- CASCADE preserved. Detail (probe_errors -> snapshots) then root
-- (snapshots -> devices).
-- ============================================================

ALTER TABLE endpoint_diagnostics_probe_errors
    ADD CONSTRAINT diag_pe_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_diagnostics_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_diagnostics_probe_errors VALIDATE CONSTRAINT diag_pe_snapshot_org_fk;
ALTER TABLE endpoint_diagnostics_probe_errors DROP CONSTRAINT diag_pe_snapshot_fk;

ALTER TABLE endpoint_diagnostics_snapshots
    ADD CONSTRAINT diag_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_diagnostics_snapshots VALIDATE CONSTRAINT diag_snap_device_org_fk;
ALTER TABLE endpoint_diagnostics_snapshots DROP CONSTRAINT diag_snap_device_fk;
