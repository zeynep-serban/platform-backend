-- V33 — Faz 21.1 PR2c endpoint diff cache org_id canonicalize
--
-- Adds `org_id` UUID column alongside existing `tenant_id` column on the
-- two diff-cache tables (V27/V28 lineage). Mirrors V29 (which migrated
-- the 7 source tables); V30 CHECK constraint pattern (org_id IS NULL OR
-- org_id = tenant_id) also extended here.
--
-- Why a SEPARATE migration from V29:
--   - V29 + V30 landed in the PR1 → PR2 sequence as the source-side
--     dual-read foundation. Cache tables were added by V27 (Faz 22.5
--     P2-A) and V28 (Faz 22.5 v2-c-pre-2 source-order tuple) — after
--     V29 was already merged. Folding cache tables into V29 retroactively
--     would have required rebasing the BE-024c sprint chain.
--   - PR2b-iv source-side read migration arc (10 sub-slices, this
--     session) covered cache-aware READS via COALESCE in
--     DiffCacheBackfillWorker + AdminDiffCacheController (PR2b-iii).
--     PR2c is the cache-side WRITE foundation: V33 ADD COLUMN +
--     BACKFILL + TRIGGER + CHECK, then code-side canonical write.
--
-- Pre-flight evidence requirements (per V29 charter):
--   - row count parity (before/after) per cache table
--   - tenant_id != org_id mismatch count = 0 immediately post-migration
--   - distinct tenant count preserved
--   - old-writer (DiffCacheService.upsert calling with tenantId only)
--     INSERT/UPDATE → trigger fills org_id
--   - new-writer (Faz 21.1 PR2c follow-up; canonical org_id at source)
--     INSERT/UPDATE → trigger leaves it alone
--
-- Anti-pattern guards (same as V29):
--   - org_id stays NULLABLE until cleanup PR
--   - trigger purely additive (same endpoint_org_id_compat_fill function
--     declared in V29 — reused; do NOT redeclare; the function is
--     CREATE OR REPLACE-safe at function level, but redeclaring here
--     would obscure the dependency on V29)
--   - V30-style CHECK org_id IS NULL OR org_id = tenant_id (NOT VALID +
--     VALIDATE two-phase, matches V30 pattern; cache rows are small and
--     VALIDATE is O(rows) — single online scan)
--   - no DROP COLUMN tenant_id (cleanup PR scope after stable window)
--   - Flyway forward-only; rollback path is app-side (revert deploy +
--     legacy code still reads tenant_id via COALESCE)
--
-- Tables migrated:
--   1. endpoint_software_diff_cache (V27)
--   2. endpoint_outdated_software_diff_cache (V27)
--
-- References:
--   - V29__add_org_id_compat_layer.sql (source-table pattern)
--   - V30__org_id_check_constraint.sql (CHECK pattern)
--   - charter §1.1 + ADR-0032 §3.2 (Faz 21.1 endpoint org_id charter)
--   - Faz 21.1 PR2b-iv source-side read migration arc closure 2026-06-03
--     (10 sub-slices: a/b1-b4/c/d-A/d-B/e-A/e-B/f)

-- ============================================================
-- Table 1: endpoint_software_diff_cache (V27)
-- ============================================================

ALTER TABLE endpoint_software_diff_cache ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_software_diff_cache SET org_id = tenant_id
    WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_swdc_org_id
    ON endpoint_software_diff_cache(org_id);

DROP TRIGGER IF EXISTS endpoint_swdc_org_id_compat ON endpoint_software_diff_cache;
CREATE TRIGGER endpoint_swdc_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_software_diff_cache
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- V30-style CHECK org_id IS NULL OR org_id = tenant_id (two-phase NOT
-- VALID + VALIDATE — atomic per-table; cache tables are small so
-- VALIDATE is fast).
ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_org_id_eq_tenant_id_ck
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_software_diff_cache
    VALIDATE CONSTRAINT swdc_org_id_eq_tenant_id_ck;

-- ============================================================
-- Table 2: endpoint_outdated_software_diff_cache (V27)
-- ============================================================

ALTER TABLE endpoint_outdated_software_diff_cache ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_outdated_software_diff_cache SET org_id = tenant_id
    WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_osdc_org_id
    ON endpoint_outdated_software_diff_cache(org_id);

DROP TRIGGER IF EXISTS endpoint_osdc_org_id_compat ON endpoint_outdated_software_diff_cache;
CREATE TRIGGER endpoint_osdc_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_outdated_software_diff_cache
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_org_id_eq_tenant_id_ck
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_outdated_software_diff_cache
    VALIDATE CONSTRAINT osdc_org_id_eq_tenant_id_ck;
