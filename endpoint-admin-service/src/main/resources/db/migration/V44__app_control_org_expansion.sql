-- V44 — Faz 21.1 Cleanup C4 step-4 — app_control org expansion
--       (ORG-DONE-PARENT variant of the V38-V43 leaf-family pattern)
--
-- BOUNDARY: org-expands the app_control family and flips its 2 tenant-composite
-- FKs to org-composite. Differs from the 6 A2 leaf families: the parent
-- endpoint_app_control_snapshots is already ORG-DONE (org_id column + backfill +
-- compat trigger from V29, match + non-null CHECK from V30/V36 — all LIVE), so
-- the parent only needs UNIQUE(id, org_id) + its device-FK flip; the DETAIL
-- endpoint_app_control_probe_errors still needs the full org_id machinery + its
-- FK flip. Both FKs are in the manifest §2 35-FK list:
--   app_control_snapshots* → endpoint_devices   (ac_snap_device_fk)
--   app_control_snapshots  → probe_errors        (ac_pe_snapshot_fk)
-- (* = ORG-DONE parent; its FK stayed tenant-composite until devices org-ready.)
-- No tenant_id drop (A6); reads tenant-keyed (A5). Single-column
-- ac_snap_source_cmd_fk (→ endpoint_command_results, ON DELETE SET NULL) is
-- OUT OF SCOPE (command-hub related, not this app_control flip).
--
-- WHY SOUND: detail org_id NON-NULL + snapshot_id NOT NULL; parent
-- snapshots UNIQUE(id, org_id) (here) + org_id NOT NULL (V36 LIVE). devices
-- UNIQUE(id, org_id) + org_id NOT NULL (V34/V36 LIVE). MATCH SIMPLE composite
-- FK then rejects cross-org rows (23503). Parent already carries
-- org_id = tenant_id (V30 match CHECK), so both flips are lossless; P4
-- preflight is fail-loud incl. parent invariant drift (Codex 019e9477).
--
-- References: V26 (app_control create), V29 (compat trigger fn), V30/V36
--   (parent org_id CHECKs LIVE), V34 (devices UNIQUE), V38-V43 (leaf pattern),
--   manifest §3 step 4.

-- Phase 1: org_id machinery on the DETAIL only (parent already ORG-DONE).
ALTER TABLE endpoint_app_control_probe_errors ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_app_control_probe_errors SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_ac_pe_org_id ON endpoint_app_control_probe_errors(org_id);
DROP TRIGGER IF EXISTS endpoint_ac_pe_org_id_compat ON endpoint_app_control_probe_errors;
CREATE TRIGGER endpoint_ac_pe_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_app_control_probe_errors
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK on the DETAIL.
ALTER TABLE endpoint_app_control_probe_errors ADD CONSTRAINT endpoint_ac_pe_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_app_control_probe_errors VALIDATE CONSTRAINT endpoint_ac_pe_org_id_match;
ALTER TABLE endpoint_app_control_probe_errors ADD CONSTRAINT endpoint_ac_pe_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_app_control_probe_errors VALIDATE CONSTRAINT endpoint_ac_pe_org_id_not_null;

-- Phase 3: parent snapshots UNIQUE(id, org_id) (the only parent-side A3 step).
ALTER TABLE endpoint_app_control_snapshots ADD CONSTRAINT ac_snap_id_org_uq UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud — incl. parent invariant drift guard.
DO $$
DECLARE bad BIGINT;
BEGIN
    -- parent invariant (ORG-DONE): org_id non-null + org_id = tenant_id
    SELECT count(*) INTO bad FROM endpoint_app_control_snapshots WHERE org_id IS NULL;
    IF bad > 0 THEN RAISE EXCEPTION 'V44 preflight: % ac_snapshots rows with NULL org_id (parent not ORG-DONE)', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_app_control_snapshots WHERE org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V44 preflight: % ac_snapshots rows whose org_id <> tenant_id (parent drift)', bad; END IF;
    -- snapshots → endpoint_devices(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_app_control_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V44 preflight: % ac_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    -- probe_errors → snapshots(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_app_control_probe_errors p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_app_control_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V44 preflight: % ac_probe_errors rows have no snapshots(id, org_id) parent', bad; END IF;
    -- probe_error org_id = parent snapshot org_id (redundancy guard)
    SELECT count(*) INTO bad FROM endpoint_app_control_probe_errors p JOIN endpoint_app_control_snapshots s ON s.id = p.snapshot_id WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V44 preflight: % ac_probe_errors rows whose org_id <> parent snapshot org_id', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE
-- preserved. Detail→snapshot first; root snapshot→device LAST.
ALTER TABLE endpoint_app_control_probe_errors
    ADD CONSTRAINT ac_pe_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_app_control_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_app_control_probe_errors VALIDATE CONSTRAINT ac_pe_snapshot_org_fk;
ALTER TABLE endpoint_app_control_probe_errors DROP CONSTRAINT ac_pe_snapshot_fk;

ALTER TABLE endpoint_app_control_snapshots
    ADD CONSTRAINT ac_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_app_control_snapshots VALIDATE CONSTRAINT ac_snap_device_org_fk;
ALTER TABLE endpoint_app_control_snapshots DROP CONSTRAINT ac_snap_device_fk;
