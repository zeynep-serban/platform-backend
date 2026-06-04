-- V45 — Faz 21.1 Cleanup C4 step-5 — outdated_software + software_inventory
--       source FK flips (PURE-FLIP variant; 2 independent device-rooted families)
--
-- BOUNDARY: flips 3 tenant-composite FKs to org-composite across two independent,
-- hub-free, device-rooted families:
--   outdated_software: packages→snapshots + snapshots→devices (2 FKs)
--   software_inventory_state_history→devices (1 FK)
-- All three child tables are already ORG-DONE (org_id column + backfill + compat
-- trigger + match/non-null CHECK from the C1/V36 source-foundation arc), and both
-- FK parents already carry UNIQUE(id, org_id): endpoint_outdated_software_snapshots
-- (C1, used by the V37 cache flip) + endpoint_devices (V34). So NO org_id machinery
-- and NO new UNIQUE are needed here — this is a pure FK flip. No tenant_id drop
-- (A6); reads tenant-keyed (A5). These families have no hub dependency, so they
-- land before the commands/catalog hub work.
--
-- WHY SOUND: child org_id NON-NULL + device_id/snapshot_id NOT NULL; parent
-- UNIQUE(id, org_id) + org_id NOT NULL. MATCH SIMPLE composite FK then rejects
-- cross-org rows (23503). Parents carry org_id = tenant_id (match CHECK), so the
-- flip is lossless; P4 preflight is fail-loud on any residual drift.
--
-- References: V34 (devices UNIQUE), V36 (source org_id NOT NULL), V37 (cache
--   flip used the snapshots UNIQUE), V38-V44 (leaf/flip pattern), manifest §3 step 5.

-- Phase 4: preflight fail-loud (3 edges — all enablers already present).
DO $$
DECLARE bad BIGINT;
BEGIN
    -- outdated_software_snapshots → endpoint_devices(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_outdated_software_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V45 preflight: % outdated_sw_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    -- outdated_software_packages → snapshots(id, org_id) + org equality
    SELECT count(*) INTO bad FROM endpoint_outdated_software_packages p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_outdated_software_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V45 preflight: % outdated_sw_packages rows have no snapshots(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_outdated_software_packages p JOIN endpoint_outdated_software_snapshots s ON s.id = p.snapshot_id WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V45 preflight: % outdated_sw_packages rows whose org_id <> parent snapshot org_id', bad; END IF;
    -- software_inventory_state_history → endpoint_devices(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_software_inventory_state_history h
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = h.device_id AND d.org_id = h.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V45 preflight: % sw_inventory_state_history rows have no endpoint_devices(id, org_id) parent', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE
-- preserved. outdated_software: packages→snapshots (child) first, then
-- snapshots→devices (root). software_inventory→devices independent.
ALTER TABLE endpoint_outdated_software_packages
    ADD CONSTRAINT osw_pkg_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_outdated_software_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_outdated_software_packages VALIDATE CONSTRAINT osw_pkg_snapshot_org_fk;
ALTER TABLE endpoint_outdated_software_packages DROP CONSTRAINT fk_endpoint_outdated_software_packages_snapshot;

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT osw_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_outdated_software_snapshots VALIDATE CONSTRAINT osw_snap_device_org_fk;
ALTER TABLE endpoint_outdated_software_snapshots DROP CONSTRAINT fk_endpoint_outdated_software_snapshots_device;

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT sw_inv_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_software_inventory_state_history VALIDATE CONSTRAINT sw_inv_device_org_fk;
ALTER TABLE endpoint_software_inventory_state_history DROP CONSTRAINT fk_endpoint_software_inventory_state_history_device;
