-- V36 — Faz 21.1 Cleanup C1.5 — source org_id NOT NULL invariant flip
--       (Codex 019e92a7 AGREE narrow + Option A; refines V34's anticipated
--        "future coupled PR" by SPLITTING the read-path work out to A5)
--
-- BOUNDARY: this migration does exactly ONE invariant flip — it makes the
-- legacy org_id-NULL row PHYSICALLY UNCONSTRUCTABLE on the 7 source tables
-- via CHECK (org_id IS NOT NULL) NOT VALID + VALIDATE. Combined with V30's
-- CHECK (org_id IS NULL OR org_id = tenant_id), the two together prove
-- org_id = tenant_id UNIVERSALLY on the source side (no NULL + equal). It
-- intentionally does NOT remove the effective-org OR-fallback reads, does
-- NOT add composite org_id mirror indexes, and does NOT touch tenant_id.
--
-- WHY THE READ-PATH WORK IS DEFERRED TO A5 (Codex 019e92a7, index evidence):
--   V34's header anticipated a single coupled PR bundling the non-null CHECK
--   WITH the OR-fallback removal. Authoring this migration surfaced new
--   evidence that splits that bundle: the 7 source tables carry RICH
--   composite tenant_id indexes (e.g. install_audit has 4:
--   (tenant_id, device_id, catalog_item_id, created_at DESC), etc.;
--   state_history (tenant_id, device_id, captured_at, created_at, id);
--   app_control (tenant_id, device_id, collected_at DESC, ...)) but ONLY
--   single-column (org_id) indexes (V29). Simplifying the OR-fallback read
--   `(org_id = :org OR (org_id IS NULL AND tenant_id = :org))` down to
--   `org_id = :org` is a PHYSICAL ACCESS-PATH switch (leading column
--   tenant_id -> org_id) that loses every composite index → ORDER BY/range
--   queries fall to a sort = a silent 6-month perf regression. So the
--   read-column switch has TWO preconditions (this CHECK + a composite
--   org_id mirror index set); the CHECK has ZERO. The clean cut is: CHECK
--   now (C1.5), and OR-removal + composite org_id mirrors + the dead-fixture
--   retire together in C4/A5 (query canonicalize), where the index work is
--   mandatory anyway. The OR-fallback's `org_id IS NULL` branch becomes
--   provably dead the moment this VALIDATE passes, but leaving it one phase
--   longer is safe ordering (the read plan is byte-identical to today's), not
--   debt — A5 is the explicitly-named home that removes it WITH its indexes.
--
-- WHY THIS UNBLOCKS C2b: a cache FK (child_col, org_id) -> parent(id, org_id)
--   MATCH SIMPLE silently bypasses enforcement on a NULL org_id component.
--   C2a/V35 deliberately kept the 6 cache FKs tenant-composite precisely
--   because the parents (devices, sw_inv_state_history, outdated_sw_snap)
--   still permitted legacy-NULL org_id. This migration removes that — once
--   the source parents are org_id NOT NULL, the cache FK org-composite flip
--   (C2b) is sound. C1.5 is the parent-side invariant C2b waits on.
--
-- SCOPE — the 7 source org_id-bearing tables (the 2 cache tables
--   endpoint_software_diff_cache + endpoint_outdated_software_diff_cache
--   ALREADY got their non-null CHECK in V35; this is the remaining 7 of the
--   9 org_id-bearing set):
--     endpoint_devices
--     endpoint_software_inventory_state_history
--     endpoint_outdated_software_snapshots
--     endpoint_outdated_software_packages
--     endpoint_install_audit
--     endpoint_compliance_evaluations
--     endpoint_app_control_snapshots
--
-- ROLLBACK BOUNDARY (Flyway forward-only, charter F21-R29): once V36 commits,
--   the source tables reject org_id-NULL writes. An older image whose legacy
--   writers emit tenant_id-only rows STILL succeeds — the V29 BEFORE
--   INSERT/UPDATE trigger fills org_id = tenant_id before the CHECK fires, so
--   legacy writers remain compatible. The only thing newly rejected is an
--   EXPLICIT org_id = NULL write (no legacy or canonical writer does this).
--   Rollback target = redeploy a prior digest; no schema un-apply needed
--   (the constraint is inert for all real writers). endpoint-admin not yet in
--   prod (Faz 22.5 P2-A D30 cutover BLOCKED) → V36 runs at the FIRST prod
--   bootstrap on empty tables; the DO audit is a no-op on zero rows.
--
-- LOCK BUDGET: plain transactional ALTER. ADD CONSTRAINT ... NOT VALID is
--   instant (no scan; does not block concurrent DML beyond a brief ACCESS
--   EXCLUSIVE for the catalog update). VALIDATE CONSTRAINT takes a SHARE
--   UPDATE EXCLUSIVE lock (concurrent reads + writes allowed) and scans the
--   table once. Acceptable here because endpoint-admin is not yet in prod
--   (Flyway applies at bootstrap before traffic, empty tables) and testai is
--   sub-100 rows. ESCALATION (durable note): if ever applied incrementally to
--   an already-large source table, the NOT VALID + later VALIDATE split (as
--   written) is already the online-friendly form — new/updated rows enforce
--   immediately on ADD, and VALIDATE backfills under a non-blocking lock.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring): all 7
--   source tables org_id NULL = 0, tenant_id NULL = 0, tenant_id<>org_id = 0
--   → the DO audit passes and VALIDATE succeeds. (Recorded as a PRECONDITION
--   proof; the DO audit below is the deployed enforcement on any target.)
--
-- References:
--   - V29 (org_id compat layer + BEFORE INSERT/UPDATE trigger + single-col
--     (org_id) indexes), V30 (org_id = tenant_id match CHECK), V34 (C1 parent
--     UNIQUE(id, org_id)), V35 (C2a cache org-key identity + cache non-null
--     CHECK — same NOT VALID + VALIDATE pattern).
--   - platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md (C1.5 phase)
--   - Codex thread 019e92a7 (C1.5 narrow AGREE + Option A test disposition)

-- ============================================================
-- Phase 0: preflight fail-loud. The non-null flip is only sound if the
-- source side is already free of legacy NULLs and tenant/org drift. RAISE
-- per table (with the offending count) so a drifted deploy target ABORTS the
-- whole migration transaction BEFORE the CHECK lands — never a partial flip.
-- ============================================================

DO $$
DECLARE
    bad BIGINT;
    source_tables TEXT[] := ARRAY[
        'endpoint_devices',
        'endpoint_software_inventory_state_history',
        'endpoint_outdated_software_snapshots',
        'endpoint_outdated_software_packages',
        'endpoint_install_audit',
        'endpoint_compliance_evaluations',
        'endpoint_app_control_snapshots'
    ];
    t TEXT;
BEGIN
    FOREACH t IN ARRAY source_tables LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE org_id IS NULL', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V36 precondition failed: % has % org_id NULL rows (run V29 backfill / fix legacy writers before C1.5)', t, bad;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V36 precondition failed: % has % tenant_id NULL rows', t, bad;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NOT NULL AND org_id IS NOT NULL AND tenant_id <> org_id', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V36 precondition failed: % has % tenant_id<>org_id rows (V30 match CHECK drift)', t, bad;
        END IF;
    END LOOP;
END $$;

-- ============================================================
-- Phase 1: source org_id non-null invariant (CHECK NOT VALID + VALIDATE).
-- The VALIDATED CHECK is the enforcement surface; the column stays NULLABLE
-- (column-level SET NOT NULL + the OR-fallback read removal + composite
-- org_id mirror indexes are deferred to C4/A5 — see header). Combined with
-- V30's match CHECK, this proves org_id = tenant_id universally on the
-- source side, which C2b's cache FK org-composite flip depends on.
-- ============================================================

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_devices
    VALIDATE CONSTRAINT endpoint_devices_org_id_not_null;

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT endpoint_sw_inv_state_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_software_inventory_state_history
    VALIDATE CONSTRAINT endpoint_sw_inv_state_org_id_not_null;

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT endpoint_outdated_sw_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_outdated_software_snapshots
    VALIDATE CONSTRAINT endpoint_outdated_sw_snap_org_id_not_null;

ALTER TABLE endpoint_outdated_software_packages
    ADD CONSTRAINT endpoint_outdated_sw_pkg_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_outdated_software_packages
    VALIDATE CONSTRAINT endpoint_outdated_sw_pkg_org_id_not_null;

ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT endpoint_install_audit_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_install_audit
    VALIDATE CONSTRAINT endpoint_install_audit_org_id_not_null;

ALTER TABLE endpoint_compliance_evaluations
    ADD CONSTRAINT endpoint_compliance_eval_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_compliance_evaluations
    VALIDATE CONSTRAINT endpoint_compliance_eval_org_id_not_null;

ALTER TABLE endpoint_app_control_snapshots
    ADD CONSTRAINT endpoint_app_control_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_app_control_snapshots
    VALIDATE CONSTRAINT endpoint_app_control_snap_org_id_not_null;
