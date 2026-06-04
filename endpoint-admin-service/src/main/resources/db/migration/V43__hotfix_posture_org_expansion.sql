-- V43 — Faz 21.1 Cleanup C4 A2 slice-6 — hotfix_posture org expansion
--       (GRANDCHILD variant of the V38-V42 leaf-family pattern; LAST A2 leaf)
--
-- BOUNDARY: org-expands the hotfix_posture family (5 tables) and flips its 5
-- tenant-composite FKs to org-composite. Unlike the prior 5 simple families
-- (1 root + 1-3 direct children), this one has a GRANDCHILD:
--   endpoint_hotfix_posture_snapshots            (root, child of endpoint_devices)
--   endpoint_hotfix_posture_installed            (child of snapshots)
--   endpoint_hotfix_posture_pending              (child of snapshots AND parent of pending_kbs)
--   endpoint_hotfix_posture_pending_kbs          (GRANDCHILD, child of pending)
--   endpoint_hotfix_posture_pending_categories   (child of snapshots)
-- => TWO FK parents need UNIQUE(id, org_id): snapshots AND pending.
-- No tenant_id drop (A6); entities/repository/grid JOIN unchanged (reads
-- tenant-keyed = org_id; A5). Single-column fk_endpoint_hotfix_post_snap_command_result
-- (→ endpoint_command_results, ON DELETE SET NULL) is OUT OF SCOPE (untouched).
--
-- WHY SOUND (identical proof model to V38-V42, extended one level):
-- child org_id NON-NULL + child FK-col (device_id/snapshot_id/pending_id) NOT
-- NULL; each parent UNIQUE(id, org_id) + org_id NOT NULL. MATCH SIMPLE composite
-- FK then rejects cross-org rows (23503). pending is simultaneously a child
-- (of snapshots) and a parent (of pending_kbs): once pending.org_id is non-null
-- (P2) + pending UNIQUE(id, org_id) exists (P3), the grandchild edge has a valid
-- parent key — independent of whether pending's own snap-FK has flipped yet
-- (Codex 019e945e AGREE).
--
-- References: V22 (hotfix_posture create), V29 (compat trigger fn), V34/V36
--   (devices UNIQUE(id,org_id) + org_id NOT NULL, LIVE), V38-V42 (leaf pattern),
--   manifest §6 (c4-a1-fk-graph-manifest.md).

-- Phase 1: org_id + backfill + compat trigger + index (5 tables).
ALTER TABLE endpoint_hotfix_posture_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hotfix_posture_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hfp_snap_org_id ON endpoint_hotfix_posture_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_hfp_snap_org_id_compat ON endpoint_hotfix_posture_snapshots;
CREATE TRIGGER endpoint_hfp_snap_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_hotfix_posture_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hotfix_posture_installed ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hotfix_posture_installed SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hfp_installed_org_id ON endpoint_hotfix_posture_installed(org_id);
DROP TRIGGER IF EXISTS endpoint_hfp_installed_org_id_compat ON endpoint_hotfix_posture_installed;
CREATE TRIGGER endpoint_hfp_installed_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_hotfix_posture_installed
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hotfix_posture_pending ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hotfix_posture_pending SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hfp_pending_org_id ON endpoint_hotfix_posture_pending(org_id);
DROP TRIGGER IF EXISTS endpoint_hfp_pending_org_id_compat ON endpoint_hotfix_posture_pending;
CREATE TRIGGER endpoint_hfp_pending_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_hotfix_posture_pending
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hotfix_posture_pending_kbs ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hotfix_posture_pending_kbs SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hfp_pending_kbs_org_id ON endpoint_hotfix_posture_pending_kbs(org_id);
DROP TRIGGER IF EXISTS endpoint_hfp_pending_kbs_org_id_compat ON endpoint_hotfix_posture_pending_kbs;
CREATE TRIGGER endpoint_hfp_pending_kbs_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_hotfix_posture_pending_kbs
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_hotfix_posture_pending_categories ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_hotfix_posture_pending_categories SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_hfp_pending_cats_org_id ON endpoint_hotfix_posture_pending_categories(org_id);
DROP TRIGGER IF EXISTS endpoint_hfp_pending_cats_org_id_compat ON endpoint_hotfix_posture_pending_categories;
CREATE TRIGGER endpoint_hfp_pending_cats_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_hotfix_posture_pending_categories
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (5 tables).
ALTER TABLE endpoint_hotfix_posture_snapshots ADD CONSTRAINT endpoint_hfp_snap_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_snapshots VALIDATE CONSTRAINT endpoint_hfp_snap_org_id_match;
ALTER TABLE endpoint_hotfix_posture_snapshots ADD CONSTRAINT endpoint_hfp_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_snapshots VALIDATE CONSTRAINT endpoint_hfp_snap_org_id_not_null;

ALTER TABLE endpoint_hotfix_posture_installed ADD CONSTRAINT endpoint_hfp_installed_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_installed VALIDATE CONSTRAINT endpoint_hfp_installed_org_id_match;
ALTER TABLE endpoint_hotfix_posture_installed ADD CONSTRAINT endpoint_hfp_installed_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_installed VALIDATE CONSTRAINT endpoint_hfp_installed_org_id_not_null;

ALTER TABLE endpoint_hotfix_posture_pending ADD CONSTRAINT endpoint_hfp_pending_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending VALIDATE CONSTRAINT endpoint_hfp_pending_org_id_match;
ALTER TABLE endpoint_hotfix_posture_pending ADD CONSTRAINT endpoint_hfp_pending_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending VALIDATE CONSTRAINT endpoint_hfp_pending_org_id_not_null;

ALTER TABLE endpoint_hotfix_posture_pending_kbs ADD CONSTRAINT endpoint_hfp_pending_kbs_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_kbs VALIDATE CONSTRAINT endpoint_hfp_pending_kbs_org_id_match;
ALTER TABLE endpoint_hotfix_posture_pending_kbs ADD CONSTRAINT endpoint_hfp_pending_kbs_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_kbs VALIDATE CONSTRAINT endpoint_hfp_pending_kbs_org_id_not_null;

ALTER TABLE endpoint_hotfix_posture_pending_categories ADD CONSTRAINT endpoint_hfp_pending_cats_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_categories VALIDATE CONSTRAINT endpoint_hfp_pending_cats_org_id_match;
ALTER TABLE endpoint_hotfix_posture_pending_categories ADD CONSTRAINT endpoint_hfp_pending_cats_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_categories VALIDATE CONSTRAINT endpoint_hfp_pending_cats_org_id_not_null;

-- Phase 3: UNIQUE(id, org_id) on BOTH FK parents — snapshots AND pending.
ALTER TABLE endpoint_hotfix_posture_snapshots ADD CONSTRAINT endpoint_hfp_snap_id_org_id_key UNIQUE (id, org_id);
ALTER TABLE endpoint_hotfix_posture_pending ADD CONSTRAINT endpoint_hfp_pending_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud — every child row has an (id, org_id) parent
-- AND child.org_id = parent.org_id, for all 5 edges incl. the grandchild.
DO $$
DECLARE bad BIGINT;
BEGIN
    -- snapshots → endpoint_devices
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    -- installed → snapshots
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_installed i
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hotfix_posture_snapshots s WHERE s.id = i.snapshot_id AND s.org_id = i.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_installed rows have no snapshots(id, org_id) parent', bad; END IF;
    -- pending → snapshots
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hotfix_posture_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending rows have no snapshots(id, org_id) parent', bad; END IF;
    -- pending_categories → snapshots
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending_categories c
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hotfix_posture_snapshots s WHERE s.id = c.snapshot_id AND s.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending_categories rows have no snapshots(id, org_id) parent', bad; END IF;
    -- pending_kbs → pending (GRANDCHILD)
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending_kbs k
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_hotfix_posture_pending p WHERE p.id = k.pending_id AND p.org_id = k.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending_kbs rows have no pending(id, org_id) parent', bad; END IF;
    -- org_id-equality redundancy guards (defense-in-depth, mirrors V42)
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_installed i JOIN endpoint_hotfix_posture_snapshots s ON s.id = i.snapshot_id WHERE i.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_installed rows whose org_id <> parent snapshot org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending p JOIN endpoint_hotfix_posture_snapshots s ON s.id = p.snapshot_id WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending rows whose org_id <> parent snapshot org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending_categories c JOIN endpoint_hotfix_posture_snapshots s ON s.id = c.snapshot_id WHERE c.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending_categories rows whose org_id <> parent snapshot org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_hotfix_posture_pending_kbs k JOIN endpoint_hotfix_posture_pending p ON p.id = k.pending_id WHERE k.org_id <> p.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V43 preflight: % hfp_pending_kbs rows whose org_id <> parent pending org_id', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE
-- preserved. Children of snapshots first; grandchild pending_kbs→pending
-- (independent, parent UNIQUE ready from P3); root snapshots→device LAST.
ALTER TABLE endpoint_hotfix_posture_installed
    ADD CONSTRAINT hfp_installed_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hotfix_posture_installed VALIDATE CONSTRAINT hfp_installed_snapshot_org_fk;
ALTER TABLE endpoint_hotfix_posture_installed DROP CONSTRAINT fk_endpoint_hotfix_post_installed_snapshot;

ALTER TABLE endpoint_hotfix_posture_pending
    ADD CONSTRAINT hfp_pending_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending VALIDATE CONSTRAINT hfp_pending_snapshot_org_fk;
ALTER TABLE endpoint_hotfix_posture_pending DROP CONSTRAINT fk_endpoint_hotfix_post_pending_snapshot;

ALTER TABLE endpoint_hotfix_posture_pending_categories
    ADD CONSTRAINT hfp_pending_cats_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_hotfix_posture_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_categories VALIDATE CONSTRAINT hfp_pending_cats_snapshot_org_fk;
ALTER TABLE endpoint_hotfix_posture_pending_categories DROP CONSTRAINT fk_endpoint_hotfix_post_pending_cats_snapshot;

ALTER TABLE endpoint_hotfix_posture_pending_kbs
    ADD CONSTRAINT hfp_pending_kbs_pending_org_fk FOREIGN KEY (pending_id, org_id)
        REFERENCES endpoint_hotfix_posture_pending (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hotfix_posture_pending_kbs VALIDATE CONSTRAINT hfp_pending_kbs_pending_org_fk;
ALTER TABLE endpoint_hotfix_posture_pending_kbs DROP CONSTRAINT fk_endpoint_hotfix_post_pending_kbs_pending;

ALTER TABLE endpoint_hotfix_posture_snapshots
    ADD CONSTRAINT hfp_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_hotfix_posture_snapshots VALIDATE CONSTRAINT hfp_snap_device_org_fk;
ALTER TABLE endpoint_hotfix_posture_snapshots DROP CONSTRAINT fk_endpoint_hotfix_post_snap_device;
