-- V40 — Faz 21.1 Cleanup C4 A2 slice-3 — hardware_inventory org expansion
--       (mirrors V38/V39 leaf-family pattern; 3 tables, 3 FK flips)
--
-- BOUNDARY: org-expands the hardware_inventory family (root
-- endpoint_hardware_inventory_snapshots + 2 details:
-- endpoint_hardware_inventory_disks + endpoint_hardware_inventory_network_interfaces)
-- and flips its 3 tenant-composite FKs to org-composite. Third leaf family —
-- first with two detail tables. No legacy-NULL coupling → full vertical in one
-- migration. Does NOT drop tenant_id (A6); entities/repository/grid JOIN
-- unchanged (reads tenant-keyed = org_id; A5). The single-column
-- source_command_result_id FK (-> command_results(id) ON DELETE SET NULL) is
-- not tenant-composite and is out of scope.
--
-- WHY SOUND: identical to V38/V39 — child org_id NON-NULL (CHECK) +
-- device_id/snapshot_id NOT NULL; parents carry UNIQUE(id, org_id) (devices
-- V34 + snapshots added here) + org_id NOT NULL (devices V36 LIVE) → MATCH
-- SIMPLE rejects cross-org rows 23503; org_id = tenant_id universal → lossless.
--
-- References: V13 (hardware_inventory create — the 3 tenant FKs flipped),
--   V29 (compat trigger fn reused), V34/V36 (devices parent, LIVE), V38/V39
--   (leaf-family pattern), docs/faz-21/c4-a1-fk-graph-manifest.md, Codex
--   019e93a1/019e93cd/019e93fb.

-- Phase 1: org_id + backfill + compat trigger + index (3 tables).
ALTER TABLE endpoint_hardware_inventory_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hardware_inventory_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hw_inv_snap_org_id ON endpoint_hardware_inventory_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_hw_inv_snap_org_id_compat ON endpoint_hardware_inventory_snapshots;
CREATE TRIGGER endpoint_hw_inv_snap_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_hardware_inventory_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hardware_inventory_disks ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hardware_inventory_disks SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hw_inv_disk_org_id ON endpoint_hardware_inventory_disks(org_id);
DROP TRIGGER IF EXISTS endpoint_hw_inv_disk_org_id_compat ON endpoint_hardware_inventory_disks;
CREATE TRIGGER endpoint_hw_inv_disk_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_hardware_inventory_disks
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hardware_inventory_network_interfaces ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hardware_inventory_network_interfaces SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hw_inv_ni_org_id ON endpoint_hardware_inventory_network_interfaces(org_id);
DROP TRIGGER IF EXISTS endpoint_hw_inv_ni_org_id_compat ON endpoint_hardware_inventory_network_interfaces;
CREATE TRIGGER endpoint_hw_inv_ni_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_hardware_inventory_network_interfaces
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (3 tables).
ALTER TABLE endpoint_hardware_inventory_snapshots ADD CONSTRAINT endpoint_hw_inv_snap_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_snapshots VALIDATE CONSTRAINT endpoint_hw_inv_snap_org_id_match;
ALTER TABLE endpoint_hardware_inventory_snapshots ADD CONSTRAINT endpoint_hw_inv_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_snapshots VALIDATE CONSTRAINT endpoint_hw_inv_snap_org_id_not_null;

ALTER TABLE endpoint_hardware_inventory_disks ADD CONSTRAINT endpoint_hw_inv_disk_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_disks VALIDATE CONSTRAINT endpoint_hw_inv_disk_org_id_match;
ALTER TABLE endpoint_hardware_inventory_disks ADD CONSTRAINT endpoint_hw_inv_disk_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_disks VALIDATE CONSTRAINT endpoint_hw_inv_disk_org_id_not_null;

ALTER TABLE endpoint_hardware_inventory_network_interfaces ADD CONSTRAINT endpoint_hw_inv_ni_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_network_interfaces VALIDATE CONSTRAINT endpoint_hw_inv_ni_org_id_match;
ALTER TABLE endpoint_hardware_inventory_network_interfaces ADD CONSTRAINT endpoint_hw_inv_ni_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hardware_inventory_network_interfaces VALIDATE CONSTRAINT endpoint_hw_inv_ni_org_id_not_null;

-- Phase 3: snapshots UNIQUE(id, org_id) — the org-composite FK target.
ALTER TABLE endpoint_hardware_inventory_snapshots ADD CONSTRAINT endpoint_hw_inv_snap_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud (parent org-match + child org-consistency).
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_hardware_inventory_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V40 preflight: % hw_inv_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_hardware_inventory_disks k
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hardware_inventory_snapshots s WHERE s.id = k.snapshot_id AND s.org_id = k.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V40 preflight: % hw_inv_disks rows have no snapshots(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_hardware_inventory_network_interfaces ni
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hardware_inventory_snapshots s WHERE s.id = ni.snapshot_id AND s.org_id = ni.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V40 preflight: % hw_inv_network_interfaces rows have no snapshots(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_hardware_inventory_disks k
        JOIN endpoint_hardware_inventory_snapshots s ON s.id = k.snapshot_id WHERE k.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V40 preflight: % hw_inv_disks rows whose org_id <> parent snapshot org_id', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_hardware_inventory_network_interfaces ni
        JOIN endpoint_hardware_inventory_snapshots s ON s.id = ni.snapshot_id WHERE ni.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V40 preflight: % hw_inv_network_interfaces rows whose org_id <> parent snapshot org_id', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE preserved.
ALTER TABLE endpoint_hardware_inventory_disks
    ADD CONSTRAINT hw_inv_disk_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_hardware_inventory_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hardware_inventory_disks VALIDATE CONSTRAINT hw_inv_disk_snapshot_org_fk;
ALTER TABLE endpoint_hardware_inventory_disks DROP CONSTRAINT fk_endpoint_hardware_inventory_disks_snapshot;

ALTER TABLE endpoint_hardware_inventory_network_interfaces
    ADD CONSTRAINT hw_inv_ni_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_hardware_inventory_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hardware_inventory_network_interfaces VALIDATE CONSTRAINT hw_inv_ni_snapshot_org_fk;
ALTER TABLE endpoint_hardware_inventory_network_interfaces DROP CONSTRAINT fk_endpoint_hardware_inventory_network_interfaces_snapshot;

ALTER TABLE endpoint_hardware_inventory_snapshots
    ADD CONSTRAINT hw_inv_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hardware_inventory_snapshots VALIDATE CONSTRAINT hw_inv_snap_device_org_fk;
ALTER TABLE endpoint_hardware_inventory_snapshots DROP CONSTRAINT fk_endpoint_hardware_inventory_snapshots_device;
