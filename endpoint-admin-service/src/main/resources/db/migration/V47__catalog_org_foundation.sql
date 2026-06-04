-- V47 — Faz 21.1 Cleanup C4 step-7 — endpoint_software_catalog_items HUB org
--       foundation (HUB-FOUNDATION variant; the second of the two hub expansions)
--
-- BOUNDARY: org-expands the endpoint_software_catalog_items HUB so its inbound
-- composite FKs can later flip from (catalog_item_id, tenant_id) ->
-- catalog(id, tenant_id) to (catalog_item_id, org_id) -> catalog(id, org_id).
-- catalog was NOT in the V29 7-table compat set, so it carries no org_id yet
-- (live testai evidence below). Catalog A2 (org machinery) + A3 (UNIQUE(id,
-- org_id)) MUST land before ANY catalog-consumer FK flip (V48 install_audit
-- catalog FK, V49 uninstall family catalog FKs, plus the deferred
-- compliance_policy_items + catalog_uninstall_settings_change_requests). This
-- migration mirrors V46 (the commands hub) exactly, adapted for catalog:
--   1. org_id column + backfill + compat trigger (endpoint_org_id_compat_fill)
--   2. match + non-null CHECK (the org_id = tenant_id invariant gate)
--   3. UNIQUE(id, org_id)  -- composite-FK target for the consumer flips
--   4. business UNIQUE single-arbiter swap:
--        (tenant_id, catalog_item_id) -> (org_id, catalog_item_id)
-- No tenant_id drop (A6 deferred); reads stay tenant-keyed (A5 deferred:
-- EndpointSoftwareCatalogItemRepository.findByTenantIdAndCatalogItemId /
-- existsByTenantIdAndCatalogItemId are unchanged — org_id = tenant_id makes the
-- org-keyed unique logically equivalent).
--
-- WHY THE BUSINESS UNIQUE IS A SINGLE-ARBITER SWAP, NOT add-and-keep
-- (manifest §3 step 7 structural risk; V35 cache-concurrency lesson, same as
-- the V46 commands idempotency unique):
--   Two redundant unique constraints on the same logical key (org_id =
--   tenant_id) are the V35 anti-pattern: ON CONFLICT handles exactly ONE
--   arbiter index, and a racing speculative insertion can conflict on the
--   NON-arbiter unique -> unhandled unique_violation. catalog today has NO
--   ON CONFLICT writer (EndpointSoftwareCatalogService does service-layer
--   existsByTenantIdAndCatalogItemId + save, with this DB UNIQUE as the
--   concurrent-insert race backstop), so it would not hit that exact failure
--   today — but leaving two redundant uniques is a latent trap for any future
--   ON CONFLICT writer and an A6 cleanup debt. So V47 makes (org_id,
--   catalog_item_id) the SOLE arbiter via an atomic add-new / drop-old swap.
--   Lossless because the trigger + match/non-null CHECK guarantee org_id =
--   tenant_id (non-null) on every row.
--
-- ROLLBACK BOUNDARY (softer than V35 — stated precisely, no overclaim):
--   Like the V46 commands hub and unlike the diff-cache (V35),
--   endpoint_software_catalog_items has NO ON CONFLICT (tenant_id,
--   catalog_item_id) writer, so dropping that unique does NOT create a runtime
--   "no unique matching" failure for an older image. A pre-V47 pod overlapping
--   the rolling deploy inserts catalog rows with tenant_id only; the V47
--   BEFORE-trigger fills org_id = tenant_id, the new (org_id, catalog_item_id)
--   unique enforces the same race backstop, and the tenant-keyed read methods
--   still resolve via the intact tenant_id column. So old-writer overlap AND
--   rollback to a pre-V47 image are both safe. Standard Flyway forward-only
--   still applies; app-side rollback = redeploy prior digest.
--
-- WHY THE non-null CHECK IS SAFE on catalog (unlike the V34 source tables):
--   V34 withheld a non-null CHECK on the 9 source tables because their reads
--   carry a legacy-NULL OR-fallback contract defended by the *EffectiveOrg
--   test suite. catalog is NOT one of those tables and has NO effective-org
--   OR-fallback read — catalog reads use tenant_id directly. Org-keyed
--   identity REQUIRES non-null org_id (UNIQUE treats multiple (NULL, key) as
--   distinct), so V47 machine-enforces catalog org_id non-null via VALIDATED
--   CHECK (column-level SET NOT NULL still deferred to A6).
--
-- LOCK BUDGET: plain transactional ALTERs (CHECK NOT VALID instant; VALIDATE +
--   ADD UNIQUE scan a tiny table). endpoint-admin-service is NOT yet in
--   production (Faz 22.5 P2-A D30 cutover BLOCKED); Flyway applies pending
--   migrations at bootstrap before traffic; testai catalog = 3 rows.
--   CONCURRENTLY escalation path == V34 header.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring):
--   endpoint_software_catalog_items: org_id column absent; constraints =
--   PK(id) + UNIQUE(id, tenant_id) [uq_endpoint_software_catalog_items_id_tenant,
--   V10] + UNIQUE(tenant_id, catalog_item_id)
--   [uq_endpoint_software_catalog_items_tenant_catalog_item, V7]; rows = 3;
--   dup(tenant_id, catalog_item_id) = 0. => backfill org_id = tenant_id yields
--   0 NULLs, 0 dups on (org_id, catalog_item_id); VALIDATE + swap succeed.
--
-- References: V7 (catalog create + business unique), V10 (UNIQUE(id, tenant_id)),
--   V29 (compat trigger fn endpoint_org_id_compat_fill), V30/V36 (org_id match +
--   non-null CHECK pattern), V34 (devices UNIQUE(id, org_id)), V35 (single-arbiter
--   swap lesson), V44/V46 (org-expansion machinery pattern), manifest §3 step 7
--   (HUB catalog foundation).

-- Phase 1: org_id machinery — ADD COLUMN + backfill + index + compat trigger.
ALTER TABLE endpoint_software_catalog_items ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_software_catalog_items SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_software_catalog_items_org_id ON endpoint_software_catalog_items(org_id);
DROP TRIGGER IF EXISTS endpoint_software_catalog_items_org_id_compat ON endpoint_software_catalog_items;
CREATE TRIGGER endpoint_software_catalog_items_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_software_catalog_items
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (NOT VALID + VALIDATE).
ALTER TABLE endpoint_software_catalog_items ADD CONSTRAINT endpoint_software_catalog_items_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_software_catalog_items VALIDATE CONSTRAINT endpoint_software_catalog_items_org_id_match;
ALTER TABLE endpoint_software_catalog_items ADD CONSTRAINT endpoint_software_catalog_items_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_software_catalog_items VALIDATE CONSTRAINT endpoint_software_catalog_items_org_id_not_null;

-- Phase 3: UNIQUE(id, org_id) — composite-FK target for the catalog-consumer
-- flips (V48 install_audit catalog FK [RESTRICT], V49 uninstall family catalog
-- FKs, + deferred compliance_policy_items / catalog_uninstall_settings_change_
-- requests). A composite FK (catalog_item_id, org_id) -> parent(id, org_id)
-- REQUIRES a UNIQUE on exactly (id, org_id); PK(id) alone does NOT satisfy it.
ALTER TABLE endpoint_software_catalog_items ADD CONSTRAINT endpoint_software_catalog_items_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud (specific errors + the dup-guard for Phase 5).
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_software_catalog_items WHERE org_id IS NULL;
    IF bad > 0 THEN RAISE EXCEPTION 'V47 preflight: % catalog rows with NULL org_id after backfill', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_software_catalog_items WHERE org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V47 preflight: % catalog rows whose org_id <> tenant_id (backfill drift)', bad; END IF;
    SELECT count(*) INTO bad FROM (SELECT 1 FROM endpoint_software_catalog_items GROUP BY org_id, catalog_item_id HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V47 preflight: % duplicate (org_id, catalog_item_id) groups — org-keyed business arbiter not constructable', bad; END IF;
END $$;

-- Phase 5: business UNIQUE single-arbiter swap — ADD new (org_id,
-- catalog_item_id) before DROP old (tenant_id, catalog_item_id). Add-new-before-
-- drop-old: a failed new-unique build leaves the old unique intact on rollback.
-- DDL is transactional; the transient dual-unique state is never a committed
-- runtime state — after COMMIT only the org-keyed unique exists.
ALTER TABLE endpoint_software_catalog_items ADD CONSTRAINT uq_endpoint_software_catalog_items_org_catalog_item UNIQUE (org_id, catalog_item_id);
ALTER TABLE endpoint_software_catalog_items DROP CONSTRAINT uq_endpoint_software_catalog_items_tenant_catalog_item;
