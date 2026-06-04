-- V41 — Faz 21.1 Cleanup C4 A2 slice-4 — services org expansion
--       (mirrors V40 hardware_inventory 2-detail pattern)
--
-- BOUNDARY: org-expands the services family (root endpoint_services_snapshots
-- + details endpoint_services_entries + endpoint_services_probe_errors) and
-- flips its 3 tenant-composite FKs to org-composite. Same family-vertical
-- pattern (no legacy-NULL coupling). Does NOT drop tenant_id (A6);
-- entities/repository/grid JOIN unchanged (reads tenant-keyed = org_id; A5).
-- The single-column source_command_result_id FK is out of scope.
--
-- WHY SOUND: identical to V38/V39/V40 — child org_id NON-NULL + device_id/
-- snapshot_id NOT NULL; parents UNIQUE(id, org_id) (devices V34 + snapshots
-- added here) + org_id NOT NULL (devices V36 LIVE) → MATCH SIMPLE rejects
-- cross-org 23503; org_id = tenant_id universal → lossless.
--
-- References: V24 (services create — 3 tenant FKs flipped), V29 (compat trigger
--   fn), V34/V36 (devices, LIVE), V38/V39/V40 (leaf pattern), manifest §6.

-- Phase 1: org_id + backfill + compat trigger + index (3 tables).
ALTER TABLE endpoint_services_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_services_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_svcs_snap_org_id ON endpoint_services_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_svcs_snap_org_id_compat ON endpoint_services_snapshots;
CREATE TRIGGER endpoint_svcs_snap_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_services_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_services_entries ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_services_entries SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_svcs_entry_org_id ON endpoint_services_entries(org_id);
DROP TRIGGER IF EXISTS endpoint_svcs_entry_org_id_compat ON endpoint_services_entries;
CREATE TRIGGER endpoint_svcs_entry_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_services_entries
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_services_probe_errors ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_services_probe_errors SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_svcs_pe_org_id ON endpoint_services_probe_errors(org_id);
DROP TRIGGER IF EXISTS endpoint_svcs_pe_org_id_compat ON endpoint_services_probe_errors;
CREATE TRIGGER endpoint_svcs_pe_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_services_probe_errors
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (3 tables).
ALTER TABLE endpoint_services_snapshots ADD CONSTRAINT endpoint_svcs_snap_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_services_snapshots VALIDATE CONSTRAINT endpoint_svcs_snap_org_id_match;
ALTER TABLE endpoint_services_snapshots ADD CONSTRAINT endpoint_svcs_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_services_snapshots VALIDATE CONSTRAINT endpoint_svcs_snap_org_id_not_null;

ALTER TABLE endpoint_services_entries ADD CONSTRAINT endpoint_svcs_entry_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_services_entries VALIDATE CONSTRAINT endpoint_svcs_entry_org_id_match;
ALTER TABLE endpoint_services_entries ADD CONSTRAINT endpoint_svcs_entry_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_services_entries VALIDATE CONSTRAINT endpoint_svcs_entry_org_id_not_null;

ALTER TABLE endpoint_services_probe_errors ADD CONSTRAINT endpoint_svcs_pe_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_services_probe_errors VALIDATE CONSTRAINT endpoint_svcs_pe_org_id_match;
ALTER TABLE endpoint_services_probe_errors ADD CONSTRAINT endpoint_svcs_pe_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_services_probe_errors VALIDATE CONSTRAINT endpoint_svcs_pe_org_id_not_null;

-- Phase 3: snapshots UNIQUE(id, org_id).
ALTER TABLE endpoint_services_snapshots ADD CONSTRAINT endpoint_svcs_snap_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud.
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_services_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V41 preflight: % services_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_services_entries e
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_services_snapshots s WHERE s.id = e.snapshot_id AND s.org_id = e.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V41 preflight: % services_entries rows have no snapshots(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_services_probe_errors p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_services_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V41 preflight: % services_probe_errors rows have no snapshots(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_services_entries e JOIN endpoint_services_snapshots s ON s.id = e.snapshot_id WHERE e.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V41 preflight: % services_entries rows whose org_id <> parent snapshot org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_services_probe_errors p JOIN endpoint_services_snapshots s ON s.id = p.snapshot_id WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V41 preflight: % services_probe_errors rows whose org_id <> parent snapshot org_id', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE preserved.
ALTER TABLE endpoint_services_entries
    ADD CONSTRAINT svcs_entry_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_services_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_services_entries VALIDATE CONSTRAINT svcs_entry_snapshot_org_fk;
ALTER TABLE endpoint_services_entries DROP CONSTRAINT svcs_ent_snapshot_fk;

ALTER TABLE endpoint_services_probe_errors
    ADD CONSTRAINT svcs_pe_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_services_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_services_probe_errors VALIDATE CONSTRAINT svcs_pe_snapshot_org_fk;
ALTER TABLE endpoint_services_probe_errors DROP CONSTRAINT svcs_pe_snapshot_fk;

ALTER TABLE endpoint_services_snapshots
    ADD CONSTRAINT svcs_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_services_snapshots VALIDATE CONSTRAINT svcs_snap_device_org_fk;
ALTER TABLE endpoint_services_snapshots DROP CONSTRAINT svcs_snap_device_fk;
