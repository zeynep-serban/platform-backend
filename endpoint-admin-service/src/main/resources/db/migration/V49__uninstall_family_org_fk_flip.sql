-- V49 — Faz 21.1 Cleanup C4 step-9 — uninstall family org foundation + 7 FK flips
--       (FOUNDATION + FLIP variant; the last hub-dependent consumer slice)
--
-- BOUNDARY: org-expands BOTH uninstall tables (created in V32, NOT in the V29
-- 7-table compat set, so neither carries org_id) and flips their 7
-- tenant-composite FKs to org-composite, once both hubs (V46 commands + V47
-- catalog) and the device root (V34) carry UNIQUE(id, org_id). Also swaps the 2
-- uninstall_requests business partial-unique INDEXES to org-keyed (the
-- single-arbiter discipline of V46 commands idempotency + V47 catalog business
-- unique — Codex plan-time AGREE: deferring these to A6 would leave the
-- uninstall family non-canonical while commands/catalog are org-keyed).
--   endpoint_uninstall_requests: org machinery + UNIQUE(id, org_id) +
--     3 FK flips (device, catalog, command[DEFERRABLE]) +
--     2 partial-unique swaps (idempotency, one_inflight).
--   endpoint_uninstall_audit: org machinery (append-only backfill bracket) +
--     4 FK flips (request, device, catalog, command).
-- No tenant_id drop (A6); reads tenant-keyed (A5). uninstall_audit needs NO
-- UNIQUE(id, org_id) (no inbound FK); uninstall_requests needs it (the
-- audit->request FK rebinds to uninstall_requests(id, org_id)).
--
-- CRITICAL — request->command FK stays DEFERRABLE INITIALLY DEFERRED: the
-- approve/dispatch flow INSERTs the command then UPDATEs the request's
-- command_id in the SAME transaction; a non-deferrable FK would break that
-- same-tx chain. command_id is NULLABLE on uninstall_requests, so its preflight
-- + new FK both tolerate NULL (a not-yet-dispatched request).
--
-- CRITICAL — append-only backfill: endpoint_uninstall_audit has a BEFORE UPDATE
-- OR DELETE append-only trigger (trg_endpoint_uninstall_audit_append_only, V32)
-- that RAISEs on any UPDATE. The org_id backfill is an UPDATE, so it is
-- bracketed by a NAMED DISABLE/ENABLE of ONLY that trigger (never DISABLE
-- TRIGGER USER). This is a one-time schema-canonicalization exception, not an
-- application write path; after the migration the trigger still rejects every
-- UPDATE/DELETE (asserted by the V49 IT).
--
-- WHY THE PARTIAL-UNIQUE SWAPS ARE SINGLE-ARBITER + SAFE: uninstall has NO
-- ON CONFLICT writer (EndpointUninstallService does service-layer idempotency
-- replay + in-flight guard reads + saveAndFlush race backstop), so — like V46/
-- V47 — old/new pod overlap and pre-V49 rollback are both safe (the V35 hazard
-- was specifically ON CONFLICT arbiter mismatch, absent here). The swaps keep
-- the SAME final index names (the names don't say "tenant"; no code parses
-- them) via CREATE-new / DROP-old / RENAME. Partial unique => INDEX, not a
-- table constraint (ALTER TABLE ADD CONSTRAINT UNIQUE cannot express a WHERE).
--
-- LOCK BUDGET: plain transactional ALTERs + CREATE UNIQUE INDEX (non-
-- CONCURRENT; transactional Flyway forbids CONCURRENTLY and both tables are
-- tiny — testai requests=2, audit=2). endpoint-admin not yet in prod; Flyway
-- runs at bootstrap before traffic.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring): both
--   uninstall tables have NO org_id; requests=2 rows, audit=2 rows; tenant_id
--   NULL=0 on both. The 7 FKs are still (child_col, tenant_id)->parent(id,
--   tenant_id); request->command is DEFERRABLE INITIALLY DEFERRED. devices/
--   commands/catalog all carry UNIQUE(id, org_id). => backfill yields 0 NULLs /
--   0 mismatch; all VALIDATEs + swaps succeed.
--
-- References: V32 (uninstall surface create + append-only trigger + partial
--   uniques + DEFERRABLE command FK), V29 (compat trigger fn), V30/V36 (org_id
--   CHECK pattern), V34 (devices UNIQUE(id,org_id)), V46 (commands hub), V47
--   (catalog hub), V35 (single-arbiter lesson), V44-V48 (machinery/flip
--   pattern), manifest §3 step 9. Codex plan-time AGREE thread 019e94bc.

-- ════════════════════════════════════════════════════════════════════
-- Phase 1: org_id machinery — endpoint_uninstall_requests.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_uninstall_requests ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_uninstall_requests SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_uninstall_requests_org_id ON endpoint_uninstall_requests(org_id);
DROP TRIGGER IF EXISTS endpoint_uninstall_requests_org_id_compat ON endpoint_uninstall_requests;
CREATE TRIGGER endpoint_uninstall_requests_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_uninstall_requests
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ════════════════════════════════════════════════════════════════════
-- Phase 2: org_id machinery — endpoint_uninstall_audit (append-only bracket).
-- The backfill UPDATE must bypass the append-only trigger; disable ONLY that
-- named trigger, backfill, re-enable. One-time canonicalization, not an app
-- write path.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_uninstall_audit ADD COLUMN IF NOT EXISTS org_id UUID;
ALTER TABLE endpoint_uninstall_audit DISABLE TRIGGER trg_endpoint_uninstall_audit_append_only;
UPDATE endpoint_uninstall_audit SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
ALTER TABLE endpoint_uninstall_audit ENABLE TRIGGER trg_endpoint_uninstall_audit_append_only;
CREATE INDEX IF NOT EXISTS idx_endpoint_uninstall_audit_org_id ON endpoint_uninstall_audit(org_id);
DROP TRIGGER IF EXISTS endpoint_uninstall_audit_org_id_compat ON endpoint_uninstall_audit;
CREATE TRIGGER endpoint_uninstall_audit_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_uninstall_audit
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ════════════════════════════════════════════════════════════════════
-- Phase 3: match + non-null CHECK (NOT VALID + VALIDATE) — both tables.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_uninstall_requests ADD CONSTRAINT endpoint_uninstall_requests_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_uninstall_requests VALIDATE CONSTRAINT endpoint_uninstall_requests_org_id_match;
ALTER TABLE endpoint_uninstall_requests ADD CONSTRAINT endpoint_uninstall_requests_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_uninstall_requests VALIDATE CONSTRAINT endpoint_uninstall_requests_org_id_not_null;

ALTER TABLE endpoint_uninstall_audit ADD CONSTRAINT endpoint_uninstall_audit_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT endpoint_uninstall_audit_org_id_match;
ALTER TABLE endpoint_uninstall_audit ADD CONSTRAINT endpoint_uninstall_audit_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT endpoint_uninstall_audit_org_id_not_null;

-- ════════════════════════════════════════════════════════════════════
-- Phase 4: UNIQUE(id, org_id) on uninstall_requests — the audit->request FK
-- target. (uninstall_audit needs none: no inbound FK.)
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_uninstall_requests ADD CONSTRAINT endpoint_uninstall_requests_id_org_id_key UNIQUE (id, org_id);

-- ════════════════════════════════════════════════════════════════════
-- Phase 5: preflight fail-loud — org invariants + 7 FK parent existence
-- (command_id NULLABLE on requests) + 2 partial-unique dup groups.
-- ════════════════════════════════════════════════════════════════════
DO $$
DECLARE bad BIGINT;
BEGIN
    -- org invariants (both tables; redundant with VALIDATED CHECKs, explicit)
    SELECT count(*) INTO bad FROM endpoint_uninstall_requests WHERE org_id IS NULL OR org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_requests rows with NULL/mismatched org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_audit WHERE org_id IS NULL OR org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_audit rows with NULL/mismatched org_id', bad; END IF;

    -- requests -> devices / catalog / commands(NULLABLE)
    SELECT count(*) INTO bad FROM endpoint_uninstall_requests r
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = r.device_id AND d.org_id = r.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_requests rows have no devices(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_requests r
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_software_catalog_items ci WHERE ci.id = r.catalog_item_id AND ci.org_id = r.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_requests rows have no catalog(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_requests r
        WHERE r.command_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM endpoint_commands c WHERE c.id = r.command_id AND c.org_id = r.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_requests rows have no commands(id, org_id) parent', bad; END IF;

    -- audit -> requests / devices / catalog / commands(NOT NULL)
    SELECT count(*) INTO bad FROM endpoint_uninstall_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_uninstall_requests r WHERE r.id = a.request_id AND r.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_audit rows have no uninstall_requests(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = a.device_id AND d.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_audit rows have no devices(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_software_catalog_items ci WHERE ci.id = a.catalog_item_id AND ci.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_audit rows have no catalog(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_uninstall_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_commands c WHERE c.id = a.command_id AND c.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % uninstall_audit rows have no commands(id, org_id) parent', bad; END IF;

    -- partial-unique dup guards (org-keyed predicates)
    SELECT count(*) INTO bad FROM (SELECT 1 FROM endpoint_uninstall_requests WHERE idempotency_key IS NOT NULL GROUP BY org_id, idempotency_key HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % duplicate (org_id, idempotency_key) groups', bad; END IF;
    SELECT count(*) INTO bad FROM (SELECT 1 FROM endpoint_uninstall_requests WHERE state <> 'TERMINAL' GROUP BY org_id, device_id, catalog_item_id HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V49 preflight: % duplicate open (org_id, device_id, catalog_item_id) groups', bad; END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- Phase 6: partial-unique INDEX single-arbiter swaps (CREATE new / DROP old /
-- RENAME to the original name). Partial uniques cannot be table constraints.
-- ════════════════════════════════════════════════════════════════════
CREATE UNIQUE INDEX uq_endpoint_uninstall_idempotency_org_tmp
    ON endpoint_uninstall_requests (org_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
DROP INDEX uq_endpoint_uninstall_idempotency;
ALTER INDEX uq_endpoint_uninstall_idempotency_org_tmp RENAME TO uq_endpoint_uninstall_idempotency;

CREATE UNIQUE INDEX uq_endpoint_uninstall_one_inflight_org_tmp
    ON endpoint_uninstall_requests (org_id, device_id, catalog_item_id) WHERE state <> 'TERMINAL';
DROP INDEX uq_endpoint_uninstall_one_inflight;
ALTER INDEX uq_endpoint_uninstall_one_inflight_org_tmp RENAME TO uq_endpoint_uninstall_one_inflight;

-- ════════════════════════════════════════════════════════════════════
-- Phase 7: FK flips (add-NOT VALID + VALIDATE + drop-old). ON DELETE preserved
-- (all NO ACTION). request->command STAYS DEFERRABLE INITIALLY DEFERRED.
-- ════════════════════════════════════════════════════════════════════
-- requests -> devices / catalog / commands(DEFERRABLE)
ALTER TABLE endpoint_uninstall_requests
    ADD CONSTRAINT uninstall_req_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_requests VALIDATE CONSTRAINT uninstall_req_device_org_fk;
ALTER TABLE endpoint_uninstall_requests DROP CONSTRAINT fk_endpoint_uninstall_requests_device;

ALTER TABLE endpoint_uninstall_requests
    ADD CONSTRAINT uninstall_req_catalog_org_fk FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_requests VALIDATE CONSTRAINT uninstall_req_catalog_org_fk;
ALTER TABLE endpoint_uninstall_requests DROP CONSTRAINT fk_endpoint_uninstall_requests_catalog;

ALTER TABLE endpoint_uninstall_requests
    ADD CONSTRAINT uninstall_req_command_org_fk FOREIGN KEY (command_id, org_id)
        REFERENCES endpoint_commands (id, org_id) DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE endpoint_uninstall_requests VALIDATE CONSTRAINT uninstall_req_command_org_fk;
ALTER TABLE endpoint_uninstall_requests DROP CONSTRAINT fk_endpoint_uninstall_requests_command;

-- audit -> requests / devices / catalog / commands
ALTER TABLE endpoint_uninstall_audit
    ADD CONSTRAINT uninstall_audit_request_org_fk FOREIGN KEY (request_id, org_id)
        REFERENCES endpoint_uninstall_requests (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT uninstall_audit_request_org_fk;
ALTER TABLE endpoint_uninstall_audit DROP CONSTRAINT fk_endpoint_uninstall_audit_request;

ALTER TABLE endpoint_uninstall_audit
    ADD CONSTRAINT uninstall_audit_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT uninstall_audit_device_org_fk;
ALTER TABLE endpoint_uninstall_audit DROP CONSTRAINT fk_endpoint_uninstall_audit_device;

ALTER TABLE endpoint_uninstall_audit
    ADD CONSTRAINT uninstall_audit_catalog_org_fk FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT uninstall_audit_catalog_org_fk;
ALTER TABLE endpoint_uninstall_audit DROP CONSTRAINT fk_endpoint_uninstall_audit_catalog;

ALTER TABLE endpoint_uninstall_audit
    ADD CONSTRAINT uninstall_audit_command_org_fk FOREIGN KEY (command_id, org_id)
        REFERENCES endpoint_commands (id, org_id) NOT VALID;
ALTER TABLE endpoint_uninstall_audit VALIDATE CONSTRAINT uninstall_audit_command_org_fk;
ALTER TABLE endpoint_uninstall_audit DROP CONSTRAINT fk_endpoint_uninstall_audit_command;
