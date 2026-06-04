-- V34 — Faz 21.1 Cleanup C1 Source Org-Key Foundation (B-only)
--       (Codex 019e919e: PARTIAL → AGREE for THIS reduced, purely-additive scope)
--
-- BOUNDARY (Codex 019e919e verbatim):
--   This migration only establishes org-composite FK target eligibility for
--   later cache flips. It intentionally does NOT rewrite tenant-scoped FKs,
--   remove effective-org fallback reads, add a non-null CHECK, or commit to
--   the final tenant_id drop strategy because live FK dependency closure
--   extends beyond the 9 org_id-bearing tables.
--
-- This migration does exactly ONE additive, non-breaking thing:
--
--   ADD CONSTRAINT UNIQUE (id, org_id) on the 3 cache parents
--   (endpoint_devices, endpoint_software_inventory_state_history,
--   endpoint_outdated_software_snapshots). A composite FK
--   (child_col, org_id) -> parent (id, org_id) REQUIRES a UNIQUE/PK on
--   exactly parent (id, org_id); PK(id) alone does NOT satisfy it. C2
--   (cache org-key flip) recreates the cache FKs against this target, so
--   the UNIQUE must land first (charter F21-R31 parent-before-child).
--
-- WHY NO non-null CHECK here (CI-driven correction, 2026-06-04):
--   The first draft of V34 also added CHECK (org_id IS NOT NULL) NOT VALID
--   + VALIDATE on the 9 tables as an "evidence gate". CI proved that is a
--   SCHEMA CONTRACT FLIP, not an additive gate: it makes the legacy
--   org_id-NULL row UNCONSTRUCTABLE, which breaks the entire PR2b-iv
--   *EffectiveOrgPostgresIntegrationTest suite (those tests disable the
--   V29 trigger, insert org_id = NULL, and assert the effective-org
--   OR-fallback read still returns the row). The non-null CHECK and the
--   OR-fallback read removal are TWO FACES OF ONE INVARIANT FLIP and must
--   ship together in a future coupled PR (prod-shaped evidence gated):
--     - preflight: 9 tables org_id NULL = 0, tenant_id<>org_id = 0
--     - CHECK (org_id IS NOT NULL) NOT VALID + VALIDATE
--     - repository effective-org OR-fallback removal -> direct org_id
--     - legacy-NULL fixtures retired / replaced with "NULL rejected" tests
--     - rollback / read-contract note
--   V34 deliberately leaves org_id NULLABLE and the OR-fallback intact so
--   it commits to none of that. (Live testai evidence that org_id NULL = 0
--   today is preserved in the PR/plan as a PRECONDITION proof, NOT as a
--   deployed invariant.)
--
-- WHY THE SCOPE IS BOUNDED (live FK-web discovery, 2026-06-04, testai):
--   Only these 9 tables carry org_id. endpoint_devices is referenced by
--   14 inbound composite FKs (child_col, tenant_id) -> devices(id,
--   tenant_id), ~10 of whose children have NO org_id (device-rooted
--   snapshot tree). endpoint_install_audit FKs to endpoint_commands +
--   endpoint_software_catalog_items, which have NO org_id. So the
--   dependency closure of "drop endpoint_devices.tenant_id" is the whole
--   device-rooted tree, NOT 9 tables. The final FK-web / C4 drop strategy
--   is REOPENED (platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md
--   + risk F21-R32). This migration commits to none of it.
--
-- LOCK BUDGET (Codex 019e919e — "açık lock-budget yazılmalı"):
--   Plain transactional `ALTER TABLE ADD CONSTRAINT UNIQUE` (table scan
--   under ACCESS EXCLUSIVE). Acceptable here because:
--     - endpoint-admin-service is NOT yet in production (Faz 22.5 P2-A D30
--       cutover BLOCKED; prod DB never bootstrapped). Flyway applies
--       pending migrations at startup BEFORE traffic, so V34 runs during
--       the FIRST prod bootstrap on an empty/tiny endpoint_devices.
--     - testai data is sub-100 rows (devices=6).
--     - The constraint is unfailable: id is PRIMARY KEY, so (id, org_id)
--       is trivially unique for ANY org_id values (NULLs included).
--   ESCALATION (durable note): if ever applied incrementally to an
--   already-large endpoint_devices, replace with the online pattern:
--     CREATE UNIQUE INDEX CONCURRENTLY ux_..._id_org_id ON <t>(id, org_id);
--     ALTER TABLE <t> ADD CONSTRAINT <t>_id_org_id_key UNIQUE USING INDEX ux_...;
--   (requires a non-transactional Flyway script). Keeping V34 transactional
--   gives all-or-nothing rollback safety, which for sub-100-row tables
--   strictly dominates the CONCURRENTLY complexity.
--
-- Anti-pattern guards:
--   - No non-null CHECK (coupled to OR-fallback removal — future PR).
--   - No DROP COLUMN tenant_id (C4 scope).
--   - No FK rewrite (deferred; final strategy unresolved).
--   - No repository read-path change (separate code PR).
--   - org_id stays NULLABLE; the OR-fallback read path stays intact.
--   - Flyway forward-only; app-side rollback = redeploy prior digest.
--
-- References:
--   - V29 (compat layer + trigger), V30 (org_id=tenant_id CHECK pattern),
--     V33 (cache org_id compat) — same 9-table set.
--   - platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md (C1 phase)
--   - Codex thread 019e919e (this bounded scope; B-only AGREE)
--   - Codex thread 019e8f95 (C0 plan parent-before-child ordering)

-- ============================================================
-- FK-target enabler — UNIQUE (id, org_id) on the 3 cache parents.
-- Enables C2 composite cache FK (child_col, org_id) -> parent(id, org_id).
-- Additive: coexists with existing PRIMARY KEY (id) + UNIQUE (id, tenant_id).
-- See LOCK BUDGET above for the transactional-vs-CONCURRENTLY rationale.
-- ============================================================

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_id_org_id_key UNIQUE (id, org_id);

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT endpoint_sw_inv_state_id_org_id_key UNIQUE (id, org_id);

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT endpoint_outdated_sw_snap_id_org_id_key UNIQUE (id, org_id);
