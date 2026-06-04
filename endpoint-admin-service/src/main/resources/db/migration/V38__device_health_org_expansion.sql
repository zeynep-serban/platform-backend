-- V38 — Faz 21.1 Cleanup C4 A2 slice-1 — device_health org expansion (PILOT)
--       (Codex 019e93a1 plan-time AGREE — one pilot leaf family, full vertical)
--
-- BOUNDARY: org-expands the device_health family (root
-- endpoint_device_health_snapshots + detail endpoint_device_health_disks) and
-- flips its 2 tenant-composite FKs to org-composite. This is the C4 A2 PILOT —
-- it establishes the family-vertical pattern the other leaf snapshot families
-- (diagnostics, hardware_inventory, hotfix_posture, services, startup_exposure)
-- will mirror. Because these tables NEVER had org_id (unlike the 7 source
-- tables that got it in V29), there is NO legacy-NULL OR-fallback read coupling,
-- so the full vertical (V29 add+backfill+trigger + V30 match CHECK + V36
-- non-null CHECK + V34 parent UNIQUE + V37 FK flip) ships in ONE migration.
--
-- WHAT THIS IS / IS NOT:
--   - DOES: add org_id (=tenant_id) to both tables, trigger-maintained
--     (reuses the V29 endpoint_org_id_compat_fill function); validate
--     org=tenant + org NOT NULL; add snapshots UNIQUE(id, org_id); flip both
--     FKs to org-composite (atomic add-NOT VALID+VALIDATE+drop, ON DELETE
--     CASCADE preserved).
--   - DOES NOT: drop tenant_id (A6); change the JPA entities / repository
--     queries / DeviceGridQueryBuilder JOIN (reads stay tenant-keyed — org_id
--     = tenant_id, tenant_id + its composite indexes stay, so tenant reads
--     keep working; the read-column switch + org composite mirror indexes are
--     the A5 step, deferred exactly as for the 7 source tables in C1.5). The
--     single-column source_command_result_id FK (-> command_results(id)) is
--     NOT a tenant-composite FK and is out of scope.
--
-- WHY SOUND (the org-composite FKs machine-enforce org isolation):
--   child org_id is made NON-NULL here (CHECK) + device_id/snapshot_id are
--   NOT NULL; the parents already satisfy the org-composite target —
--   endpoint_devices has UNIQUE(id, org_id) (V34) + org_id NOT NULL (V36, LIVE)
--   and this migration adds snapshots UNIQUE(id, org_id) before the disks FK
--   flip. MATCH SIMPLE then requires an exact (col, org_id) -> (id, org_id)
--   match -> a cross-org row is rejected 23503. org_id = tenant_id universally
--   (backfill + trigger + match CHECK) so the flip is lossless vs the old
--   tenant-composite FK.
--
-- ROLLBACK BOUNDARY (Flyway forward-only, F21-R29): tenant_id stays; reads +
--   entities are unchanged; org_id is trigger-maintained = tenant_id. A
--   rollback target that predates V38 simply lacks org_id (additive) — the old
--   tenant-composite FKs are restored only by re-applying the prior schema;
--   in-place rollback of the FK flip is not supported (forward-only). No
--   pre-existing image breaks (the columns/FKs V38 changes did not exist before
--   or kept their tenant form).
--
-- LOCK BUDGET: plain transactional ALTER. ADD COLUMN (nullable) + ADD ... NOT
--   VALID are instant; UPDATE backfill + VALIDATE scan the tiny tables (testai
--   sub-100 rows); endpoint-admin not in prod (D30 BLOCKED) -> V38 runs at first
--   bootstrap on empty tables.
--
-- References:
--   - V17 (device_health create — the 2 tenant-composite FKs flipped here)
--   - V29 (endpoint_org_id_compat_fill trigger fn — reused), V30 (match CHECK),
--     V34 (parent UNIQUE(id, org_id)), V36 (source org_id NOT NULL — LIVE),
--     V37 (cache FK org-composite atomic-swap pattern — mirrored)
--   - platform-k8s-gitops docs/faz-21/c4-a1-fk-graph-manifest.md (§6 pilot)
--   - Codex thread 019e93a1 (A1/A2 slicing; device_health pilot)

-- ============================================================
-- Phase 1: org_id column + backfill + compat trigger + index (both tables).
-- Reuses the V29 endpoint_org_id_compat_fill() function (fills NEW.org_id from
-- NEW.tenant_id when a writer leaves it null) — table-agnostic.
-- ============================================================

ALTER TABLE endpoint_device_health_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_device_health_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_dev_health_snap_org_id ON endpoint_device_health_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_dev_health_snap_org_id_compat ON endpoint_device_health_snapshots;
CREATE TRIGGER endpoint_dev_health_snap_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_device_health_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_device_health_disks ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_device_health_disks SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_dev_health_disk_org_id ON endpoint_device_health_disks(org_id);
DROP TRIGGER IF EXISTS endpoint_dev_health_disk_org_id_compat ON endpoint_device_health_disks;
CREATE TRIGGER endpoint_dev_health_disk_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_device_health_disks
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ============================================================
-- Phase 2: org invariants — match CHECK (org NULL or = tenant) + non-null
-- CHECK (the FK needs non-null org_id so MATCH SIMPLE cannot bypass).
-- ============================================================

ALTER TABLE endpoint_device_health_snapshots
    ADD CONSTRAINT endpoint_dev_health_snap_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_device_health_snapshots
    VALIDATE CONSTRAINT endpoint_dev_health_snap_org_id_match;
ALTER TABLE endpoint_device_health_snapshots
    ADD CONSTRAINT endpoint_dev_health_snap_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_device_health_snapshots
    VALIDATE CONSTRAINT endpoint_dev_health_snap_org_id_not_null;

ALTER TABLE endpoint_device_health_disks
    ADD CONSTRAINT endpoint_dev_health_disk_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_device_health_disks
    VALIDATE CONSTRAINT endpoint_dev_health_disk_org_id_match;
ALTER TABLE endpoint_device_health_disks
    ADD CONSTRAINT endpoint_dev_health_disk_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_device_health_disks
    VALIDATE CONSTRAINT endpoint_dev_health_disk_org_id_not_null;

-- ============================================================
-- Phase 3: snapshots UNIQUE(id, org_id) — the org-composite FK target the
-- detail disks FK binds to (id is PK so this is trivially unique). Coexists
-- with the existing UNIQUE(id, tenant_id).
-- ============================================================

ALTER TABLE endpoint_device_health_snapshots
    ADD CONSTRAINT endpoint_dev_health_snap_id_org_id_key UNIQUE (id, org_id);

-- ============================================================
-- Phase 4: preflight fail-loud. The FK flip is lossless only if every row
-- already org-matches its parent. RAISE per failing class so a drifted target
-- aborts BEFORE any FK swap. (org=tenant + the old tenant FKs make these 0;
-- fail-loud guard + the Codex device-consistency audit.)
-- ============================================================

DO $$
DECLARE
    bad BIGINT;
BEGIN
    -- snapshots: (device_id, org_id) must resolve to an endpoint_devices(id, org_id) parent
    SELECT count(*) INTO bad FROM endpoint_device_health_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V38 preflight: % device_health_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;

    -- disks: (snapshot_id, org_id) must resolve to a snapshots(id, org_id) parent
    SELECT count(*) INTO bad FROM endpoint_device_health_disks k
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_device_health_snapshots s WHERE s.id = k.snapshot_id AND s.org_id = k.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V38 preflight: % device_health_disks rows have no snapshots(id, org_id) parent', bad; END IF;

    -- device-consistency (Codex 019e93a1): a disk's tenant/org must equal its snapshot's
    SELECT count(*) INTO bad FROM endpoint_device_health_disks k
        JOIN endpoint_device_health_snapshots s ON s.id = k.snapshot_id
        WHERE k.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V38 preflight: % device_health_disks rows whose org_id <> parent snapshot org_id (corruption)', bad; END IF;
END $$;

-- ============================================================
-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, ON DELETE
-- CASCADE preserved. Detail (disks -> snapshots) then root (snapshots ->
-- devices); both parents now carry UNIQUE(id, org_id) + org_id NOT NULL.
-- ============================================================

ALTER TABLE endpoint_device_health_disks
    ADD CONSTRAINT dev_health_disk_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_device_health_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_device_health_disks VALIDATE CONSTRAINT dev_health_disk_snapshot_org_fk;
ALTER TABLE endpoint_device_health_disks DROP CONSTRAINT fk_endpoint_device_health_disks_snapshot;

ALTER TABLE endpoint_device_health_snapshots
    ADD CONSTRAINT dev_health_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_device_health_snapshots VALIDATE CONSTRAINT dev_health_snap_device_org_fk;
ALTER TABLE endpoint_device_health_snapshots DROP CONSTRAINT fk_endpoint_device_health_snapshots_device;
