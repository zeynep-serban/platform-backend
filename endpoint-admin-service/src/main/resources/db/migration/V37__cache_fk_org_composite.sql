-- V37 — Faz 21.1 Cleanup C2b — diff-cache FK org-composite flip
--       (Codex 019e9346 plan-time AGREE / ready_for_impl)
--
-- BOUNDARY: this migration recreates the 6 diff-cache FOREIGN KEYs from
-- tenant-composite to org-composite — (child_col, org_id) -> parent(id, org_id)
-- — for the 2 cache tables. It does NOT drop tenant_id (C4), does NOT touch
-- any repository @Query / DeviceGridQueryBuilder SQL / index (C4/A5), and does
-- NOT change the cache UNIQUE identity (already org-keyed in V35). It only
-- moves the referential-integrity surface from tenant-canonical to
-- org-canonical.
--
-- WHY NOW (C2b was gated on C1.5/V36): a composite FK
-- (child_col, org_id) -> parent(id, org_id) MATCH SIMPLE silently bypasses
-- enforcement on a NULL org_id component. C2a/V35 deliberately KEPT these 6
-- FKs tenant-composite because the parents (endpoint_devices,
-- endpoint_software_inventory_state_history,
-- endpoint_outdated_software_snapshots) still permitted legacy-NULL org_id.
-- C1.5/V36 added CHECK (org_id IS NOT NULL) (VALIDATED) on those 7 source
-- tables, so parent org_id is now provably non-null. Combined with:
--   - V34: parent UNIQUE (id, org_id) on the 3 parents (the FK target),
--   - V35: cache org_id NOT NULL CHECK (child org_id non-null) + org-keyed
--     UNIQUE,
--   - V33: cache backfill org_id = tenant_id + trigger; every DiffCacheService
--     UPSERT writes org_id = tenantId canonically,
-- the org-composite FK is now SOUND: child org_id + child device_id are
-- non-null and parent (id, org_id) is unique + non-null, so MATCH SIMPLE
-- requires an exact composite match and machine-enforces
-- child.org_id == parent.org_id (cross-tenant/cross-org rows are rejected
-- 23503). Nullable source ids (from_/to_history_id, from_/to_snapshot_id NULL
-- for the NO_HISTORY / INSUFFICIENT_HISTORY cache shape) keep MATCH SIMPLE's
-- intended bypass — the FK only fires when the source id is present.
--
-- ATOMIC SWAP per FK (Codex 019e9346): ADD the org-composite FK NOT VALID ->
-- VALIDATE CONSTRAINT (gives the convalidated=true acceptance + matches the
-- V35/V36 fail-loud+validate discipline; NOT VALID skips the initial scan,
-- VALIDATE scans existing rows) -> DROP the old tenant-composite FK.
-- Add-before-drop: a failed new-FK build leaves the old tenant FK intact on
-- rollback. ON DELETE CASCADE is preserved on every FK (a parent
-- device/history/snapshot delete still cascades the dependent cache row;
-- Codex 019e88b5 cache-retention invariant).
--
-- ROLLBACK BOUNDARY (Flyway forward-only, charter F21-R29): V37 does NOT create
-- a new old-image blocker beyond the one V35 already established. tenant_id
-- stays; V33 trigger + V35 cache CHECK still write org_id = tenant_id; repo /
-- query / index are unchanged; so a rollback target that is >= a V35-aware
-- (C2a) digest stays schema-compatible (the org-composite FK validates its
-- writes because org_id = tenant_id). A pre-V35 image is NOT rollback-safe —
-- that boundary is V35's (it dropped the old UNIQUE(tenant_id, device_id), so
-- a pre-V35 ON CONFLICT(tenant_id, device_id) writer breaks at runtime). In
-- short: rollback target must remain C2a/V35-aware or newer; in-place tenant FK
-- rollback is not supported.
--
-- LOCK BUDGET: plain transactional ALTER. ADD ... NOT VALID is instant (no
-- scan); VALIDATE scans the tiny cache tables (testai = 6 rows each) under a
-- SHARE UPDATE EXCLUSIVE lock (concurrent reads/writes allowed); DROP is a
-- catalog update. endpoint-admin not yet in prod (D30 BLOCKED) -> V37 runs at
-- first bootstrap on empty tables.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring): the 6
-- existing tenant-composite FKs are present (swdc_device_fk, swdc_from/to
-- _history_fk, osdc_device_fk, osdc_from/to_snapshot_fk); the cache rows are
-- canonical (org_id = tenant_id, org_id non-null) and every source id resolves
-- to a same-org parent -> the Phase 0 audit passes and every VALIDATE succeeds.
--
-- References:
--   - V27 (cache create + the 6 original tenant-composite FKs)
--   - V33 (cache org_id compat), V34 (parent UNIQUE(id, org_id)),
--     V35 (cache org-key identity + cache org_id NOT NULL CHECK),
--     V36 (source org_id NOT NULL — the C1.5 unblock)
--   - platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md (C2b phase)
--   - Codex thread 019e9346 (this plan; ready_for_impl)

-- ============================================================
-- Phase 0: preflight fail-loud. The org-composite FK flip is only lossless if
-- every cache row already org-matches its parents AND is device-consistent.
-- RAISE per failing class (with count) so a drifted deploy target ABORTS the
-- whole migration BEFORE any FK is swapped. (org_id non-null + tenant=org are
-- already enforced by V35/V33; re-asserted here as fail-loud rollback/debug
-- evidence. The device-consistency audits are the Codex 019e9346 extra: the FK
-- — old or new — never enforced source.device_id == cache.device_id, so a
-- corrupted cache row could survive; this catches it before the swap.)
-- ============================================================

DO $$
DECLARE
    bad BIGINT;
BEGIN
    -- ---- endpoint_software_diff_cache ----
    SELECT count(*) INTO bad FROM endpoint_software_diff_cache WHERE org_id IS NULL;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % org_id NULL rows', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_diff_cache
        WHERE tenant_id IS NOT NULL AND tenant_id <> org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % tenant_id<>org_id rows', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_diff_cache c
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices p WHERE p.id = c.device_id AND p.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % rows whose (device_id, org_id) has no endpoint_devices(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_diff_cache c
        WHERE c.from_history_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM endpoint_software_inventory_state_history h
                          WHERE h.id = c.from_history_id AND h.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % rows whose (from_history_id, org_id) has no state_history(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_diff_cache c
        WHERE c.to_history_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM endpoint_software_inventory_state_history h
                          WHERE h.id = c.to_history_id AND h.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % rows whose (to_history_id, org_id) has no state_history(id, org_id) parent', bad; END IF;

    -- device-consistency audit (Codex 019e9346 extra; not FK-enforced)
    SELECT count(*) INTO bad FROM endpoint_software_diff_cache c
        JOIN endpoint_software_inventory_state_history h ON h.id = c.from_history_id
        WHERE c.from_history_id IS NOT NULL AND h.device_id <> c.device_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % rows whose from_history device_id <> cache device_id (corruption)', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_diff_cache c
        JOIN endpoint_software_inventory_state_history h ON h.id = c.to_history_id
        WHERE c.to_history_id IS NOT NULL AND h.device_id <> c.device_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_software_diff_cache has % rows whose to_history device_id <> cache device_id (corruption)', bad; END IF;

    -- ---- endpoint_outdated_software_diff_cache ----
    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache WHERE org_id IS NULL;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % org_id NULL rows', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache
        WHERE tenant_id IS NOT NULL AND tenant_id <> org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % tenant_id<>org_id rows', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache c
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices p WHERE p.id = c.device_id AND p.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % rows whose (device_id, org_id) has no endpoint_devices(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache c
        WHERE c.from_snapshot_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM endpoint_outdated_software_snapshots s
                          WHERE s.id = c.from_snapshot_id AND s.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % rows whose (from_snapshot_id, org_id) has no outdated_snapshots(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache c
        WHERE c.to_snapshot_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM endpoint_outdated_software_snapshots s
                          WHERE s.id = c.to_snapshot_id AND s.org_id = c.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % rows whose (to_snapshot_id, org_id) has no outdated_snapshots(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache c
        JOIN endpoint_outdated_software_snapshots s ON s.id = c.from_snapshot_id
        WHERE c.from_snapshot_id IS NOT NULL AND s.device_id <> c.device_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % rows whose from_snapshot device_id <> cache device_id (corruption)', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_outdated_software_diff_cache c
        JOIN endpoint_outdated_software_snapshots s ON s.id = c.to_snapshot_id
        WHERE c.to_snapshot_id IS NOT NULL AND s.device_id <> c.device_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V37 preflight: endpoint_outdated_software_diff_cache has % rows whose to_snapshot device_id <> cache device_id (corruption)', bad; END IF;
END $$;

-- ============================================================
-- Phase 1: per-FK atomic swap — ADD org-composite NOT VALID -> VALIDATE ->
-- DROP old tenant-composite. ON DELETE CASCADE preserved on every FK.
-- ============================================================

-- endpoint_software_diff_cache: device + from_history + to_history
ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_software_diff_cache VALIDATE CONSTRAINT swdc_device_org_fk;
ALTER TABLE endpoint_software_diff_cache DROP CONSTRAINT swdc_device_fk;

ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_from_history_org_fk FOREIGN KEY (from_history_id, org_id)
        REFERENCES endpoint_software_inventory_state_history (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_software_diff_cache VALIDATE CONSTRAINT swdc_from_history_org_fk;
ALTER TABLE endpoint_software_diff_cache DROP CONSTRAINT swdc_from_history_fk;

ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_to_history_org_fk FOREIGN KEY (to_history_id, org_id)
        REFERENCES endpoint_software_inventory_state_history (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_software_diff_cache VALIDATE CONSTRAINT swdc_to_history_org_fk;
ALTER TABLE endpoint_software_diff_cache DROP CONSTRAINT swdc_to_history_fk;

-- endpoint_outdated_software_diff_cache: device + from_snapshot + to_snapshot
ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_outdated_software_diff_cache VALIDATE CONSTRAINT osdc_device_org_fk;
ALTER TABLE endpoint_outdated_software_diff_cache DROP CONSTRAINT osdc_device_fk;

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_from_snapshot_org_fk FOREIGN KEY (from_snapshot_id, org_id)
        REFERENCES endpoint_outdated_software_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_outdated_software_diff_cache VALIDATE CONSTRAINT osdc_from_snapshot_org_fk;
ALTER TABLE endpoint_outdated_software_diff_cache DROP CONSTRAINT osdc_from_snapshot_fk;

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_to_snapshot_org_fk FOREIGN KEY (to_snapshot_id, org_id)
        REFERENCES endpoint_outdated_software_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_outdated_software_diff_cache VALIDATE CONSTRAINT osdc_to_snapshot_org_fk;
ALTER TABLE endpoint_outdated_software_diff_cache DROP CONSTRAINT osdc_to_snapshot_fk;
