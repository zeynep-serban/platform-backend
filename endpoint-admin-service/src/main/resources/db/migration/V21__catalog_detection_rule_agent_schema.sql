-- V21 — reconcile endpoint_software_catalog_items.detection_rule to the AG-027
-- agent canonical schema (agent PR #43 / Codex 019e7d82 verdict A).
--
-- The agent's REGISTRY_UNINSTALL detector (productCode | displayName+publisher)
-- is now the canonical, dispatchable shape. The deprecated BE-020 shape
-- (hive / uninstallKeyName / displayNameRegex) never reached the agent —
-- buildAgentDetectionRule rejected every non-WINGET type until #42 — so no
-- REGISTRY_UNINSTALL row was ever agent-dispatchable. The WINGET authoring
-- field wingetPackageId is renamed to the agent-canonical packageId.
--
-- This is a one-time, DATA-AGNOSTIC sweep (no assumption about which rows
-- exist) and is safe to run on an empty table:
--   1. WINGET_PACKAGE: wingetPackageId -> packageId (only where packageId is
--      absent, so it is idempotent and never clobbers a freshly-authored rule).
--   2. REGISTRY_UNINSTALL with a GUID uninstallKeyName: lift it to productCode
--      and drop the deprecated keys.
--   3. REGISTRY_UNINSTALL WITHOUT a GUID uninstallKeyName (non-GUID leaf, or a
--      displayNameRegex-only rule) CANNOT be auto-converted: regex is
--      intentionally unsupported, and a non-GUID leaf is not a productCode.
--      These rows are LEFT INTACT for manual reauthoring. They were never
--      agent-dispatchable and now fail closed at dispatch with a clear 422
--      (detection_rule_type_not_supported_by_agent) until reauthored.
--   FILE_EXISTS / FILE_SHA256 rows are untouched: still valid catalog rules,
--   just not yet agent-installable.

-- 1. WINGET_PACKAGE: wingetPackageId -> packageId
UPDATE endpoint_software_catalog_items
SET detection_rule =
        (detection_rule - 'wingetPackageId')
        || jsonb_build_object('packageId', detection_rule -> 'wingetPackageId')
WHERE detection_rule ->> 'type' = 'WINGET_PACKAGE'
  AND detection_rule ? 'wingetPackageId'
  AND NOT detection_rule ? 'packageId';

-- 2. REGISTRY_UNINSTALL: GUID uninstallKeyName -> productCode (+ drop legacy
--    keys). The NOT ? 'productCode' guard prevents clobbering an already-
--    canonical productCode with a divergent legacy uninstallKeyName when a row
--    happens to carry both (Codex 019e7dce).
UPDATE endpoint_software_catalog_items
SET detection_rule =
        (detection_rule - 'hive' - 'uninstallKeyName' - 'displayNameRegex')
        || jsonb_build_object('productCode', detection_rule -> 'uninstallKeyName')
WHERE detection_rule ->> 'type' = 'REGISTRY_UNINSTALL'
  AND detection_rule ? 'uninstallKeyName'
  AND NOT detection_rule ? 'productCode'
  AND detection_rule ->> 'uninstallKeyName'
        ~* '^\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\}$';

-- 3. REGISTRY_UNINSTALL rows that ALREADY carry a canonical productCode but
--    still have leftover legacy keys: strip the legacy keys, keep productCode
--    untouched (the complement of #2's guard — Codex 019e7dce).
UPDATE endpoint_software_catalog_items
SET detection_rule = detection_rule - 'hive' - 'uninstallKeyName' - 'displayNameRegex'
WHERE detection_rule ->> 'type' = 'REGISTRY_UNINSTALL'
  AND detection_rule ? 'productCode'
  AND (detection_rule ? 'hive'
       OR detection_rule ? 'uninstallKeyName'
       OR detection_rule ? 'displayNameRegex');
