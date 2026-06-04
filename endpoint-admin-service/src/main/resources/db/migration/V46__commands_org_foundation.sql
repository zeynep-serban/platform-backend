-- V46 — Faz 21.1 Cleanup C4 step-6 — endpoint_commands HUB org foundation
--       (HUB-FOUNDATION variant; the first of the two hub org expansions)
--
-- BOUNDARY: org-expands the endpoint_commands HUB so its inbound composite
-- FKs can later flip from (child_col, tenant_id) -> commands(id, tenant_id)
-- to (child_col, org_id) -> commands(id, org_id). endpoint_commands was NOT
-- in the V29 7-table compat set, so it carries no org_id yet (live testai
-- evidence below). This migration adds the FULL org_id machinery + the two
-- FK-target / arbiter enablers the consumer flips (V48 install_audit, V49
-- uninstall family) require:
--   1. org_id column + backfill + compat trigger (endpoint_org_id_compat_fill)
--   2. match + non-null CHECK (the org_id = tenant_id invariant gate)
--   3. UNIQUE(id, org_id)  -- composite-FK target for the consumer flips
--   4. idempotency UNIQUE single-arbiter swap:
--        (tenant_id, idempotency_key) -> (org_id, idempotency_key)
-- No tenant_id drop (A6 deferred); reads stay tenant-keyed (A5 deferred:
-- EndpointCommandRepository.findByTenantIdAndIdempotencyKey is unchanged —
-- org_id = tenant_id makes the org-keyed unique logically equivalent).
--
-- WHY THE IDEMPOTENCY UNIQUE IS A SINGLE-ARBITER SWAP, NOT add-and-keep
-- (manifest §3 step 6 structural risk; V35 cache-concurrency lesson):
--   Two redundant unique constraints on the same logical key (org_id =
--   tenant_id) are the anti-pattern V35 proved unsafe: PostgreSQL ON
--   CONFLICT handles exactly ONE arbiter index, and a racing speculative
--   insertion can conflict on the NON-arbiter unique -> unhandled
--   unique_violation. endpoint_commands today has NO ON CONFLICT writer
--   (EndpointAdminCommandService does service-layer
--   findByTenantIdAndIdempotencyKey + saveAndFlush, with this DB UNIQUE as
--   the concurrent-insert race backstop), so it would not hit that exact
--   failure today — but leaving two redundant uniques is a latent trap for
--   any future ON CONFLICT writer and an A6 cleanup debt. So V46 makes
--   (org_id, idempotency_key) the SOLE arbiter via an atomic add-new /
--   drop-old swap (the V35 Phase-2 pattern). The swap is lossless because
--   the trigger + match/non-null CHECK guarantee org_id = tenant_id (non
--   null) on every row, so (org_id, idempotency_key) enforces exactly the
--   uniqueness the dropped (tenant_id, idempotency_key) did.
--
-- ROLLBACK BOUNDARY (softer than V35 — stated precisely, no overclaim):
--   Unlike the diff-cache (V35), endpoint_commands has NO ON CONFLICT
--   (tenant_id, idempotency_key) writer, so dropping that unique does NOT
--   create a runtime "no unique matching" failure for an older image. A
--   pre-V46 pod overlapping the rolling deploy inserts commands with
--   tenant_id only; the V46 BEFORE-trigger fills org_id = tenant_id, the
--   new (org_id, idempotency_key) unique enforces the same race backstop,
--   and findByTenantIdAndIdempotencyKey (tenant_id column intact) still
--   reads. So old-writer overlap AND rollback to a pre-V46 image are both
--   safe here. Standard Flyway forward-only still applies; app-side
--   rollback = redeploy prior digest.
--
-- WHY THE non-null CHECK IS SAFE on commands (unlike the V34 source tables):
--   V34 deliberately withheld a non-null CHECK on the 9 source tables
--   because their reads carry a legacy-NULL OR-fallback contract defended
--   by the *EffectiveOrg test suite. endpoint_commands is NOT one of those
--   tables and has NO effective-org OR-fallback read — command reads use
--   tenant_id directly. The two install-audit *EffectiveOrg tests insert
--   their command fixtures with a non-null tenant_id (the trigger fills
--   org_id = tenant_id), so the non-null CHECK is satisfied. Org-keyed
--   identity REQUIRES non-null org_id anyway: with nullable org_id, UNIQUE
--   treats multiple (NULL, key) as distinct. So V46 machine-enforces
--   commands org_id non-null via VALIDATED CHECK (column-level SET NOT NULL
--   still deferred to A6).
--
-- LOCK BUDGET: plain transactional ALTERs (CHECK NOT VALID instant;
--   VALIDATE + ADD UNIQUE scan a tiny table). endpoint-admin-service is NOT
--   yet in production (Faz 22.5 P2-A D30 cutover BLOCKED); Flyway applies
--   pending migrations at bootstrap before traffic; testai commands = 61
--   rows. CONCURRENTLY escalation path == V34 header (CREATE UNIQUE INDEX
--   CONCURRENTLY + ADD CONSTRAINT ... USING INDEX in a non-transactional
--   Flyway script) if ever applied incrementally to a large table.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring):
--   endpoint_commands: org_id column absent; constraints = PK(id) +
--   UNIQUE(id, tenant_id) [uq_endpoint_commands_id_tenant, V12] +
--   UNIQUE(tenant_id, idempotency_key) [uq_endpoint_commands_tenant_idempotency,
--   V2]; rows = 61; dup(tenant_id, idempotency_key) = 0; tenant_id NULL = 0.
--   => backfill org_id = tenant_id yields 0 NULLs, 0 dups on
--   (org_id, idempotency_key); VALIDATE + swap succeed.
--
-- References: V2 (commands create + idempotency unique), V12 (UNIQUE(id,
--   tenant_id)), V29 (compat trigger fn endpoint_org_id_compat_fill), V30/V36
--   (org_id match + non-null CHECK pattern), V34 (devices UNIQUE(id, org_id)),
--   V35 (single-arbiter swap lesson), V44 (org-expansion machinery pattern),
--   manifest §3 step 6 (HUB commands foundation).

-- Phase 1: org_id machinery — ADD COLUMN + backfill + index + compat trigger.
ALTER TABLE endpoint_commands ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_commands SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_commands_org_id ON endpoint_commands(org_id);
DROP TRIGGER IF EXISTS endpoint_commands_org_id_compat ON endpoint_commands;
CREATE TRIGGER endpoint_commands_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_commands
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (NOT VALID + VALIDATE). Together they pin
-- org_id = tenant_id AND org_id non-null. The match CHECK permits NULL so it
-- composes with the separate non-null CHECK (V30/V44 two-CHECK pattern).
ALTER TABLE endpoint_commands ADD CONSTRAINT endpoint_commands_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_commands VALIDATE CONSTRAINT endpoint_commands_org_id_match;
ALTER TABLE endpoint_commands ADD CONSTRAINT endpoint_commands_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_commands VALIDATE CONSTRAINT endpoint_commands_org_id_not_null;

-- Phase 3: UNIQUE(id, org_id) — composite-FK target for the consumer flips
-- (V48 install_audit command FK, V49 uninstall family command FKs). A
-- composite FK (child_col, org_id) -> parent(id, org_id) REQUIRES a UNIQUE
-- on exactly (id, org_id); PK(id) alone does NOT satisfy it. Coexists with
-- the existing PK(id) + UNIQUE(id, tenant_id).
ALTER TABLE endpoint_commands ADD CONSTRAINT endpoint_commands_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud. Redundant with the VALIDATED CHECKs but
-- explicit (specific, actionable errors) and the dup-guard for the Phase 5
-- new unique build.
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_commands WHERE org_id IS NULL;
    IF bad > 0 THEN RAISE EXCEPTION 'V46 preflight: % endpoint_commands rows with NULL org_id after backfill', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_commands WHERE org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V46 preflight: % endpoint_commands rows whose org_id <> tenant_id (backfill drift)', bad; END IF;
    SELECT count(*) INTO bad FROM (SELECT 1 FROM endpoint_commands GROUP BY org_id, idempotency_key HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V46 preflight: % duplicate (org_id, idempotency_key) groups — org-keyed idempotency arbiter not constructable', bad; END IF;
END $$;

-- Phase 5: idempotency UNIQUE single-arbiter swap — ADD new (org_id,
-- idempotency_key) before DROP old (tenant_id, idempotency_key). Add-new-
-- before-drop-old: a failed new-unique build leaves the old unique intact on
-- rollback. DDL is transactional; the transient dual-unique state is never a
-- committed runtime state — after COMMIT only the org-keyed unique exists,
-- so it is the SOLE ON CONFLICT arbiter for any future upsert writer.
ALTER TABLE endpoint_commands ADD CONSTRAINT uq_endpoint_commands_org_idempotency UNIQUE (org_id, idempotency_key);
ALTER TABLE endpoint_commands DROP CONSTRAINT uq_endpoint_commands_tenant_idempotency;
