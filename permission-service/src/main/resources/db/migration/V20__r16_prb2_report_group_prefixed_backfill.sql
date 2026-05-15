-- R16 PR-B-2 (Codex 019e2a13 REVISE P1 absorb) — report_group prefixed backfill.
--
-- Bağlam:
-- V18 migration raw report group keys (HR_REPORTS, FINANCE_REPORTS,
-- ANALYTICS_REPORTS) per-dashboard expansion yaptı ama raw rows'ları
-- intentionally korudu (legacy report-service static report.json
-- "reportGroup" field referansı). PR-B-2 prefixed format `reports.<GROUP>`
-- granule'larını ekledi (PermissionDataInitializer). Bu migration:
--
-- 1. Mevcut raw granule'ları olan role'ler için `reports.<GROUP>` prefixed
--    granule rows'larını idempotent backfill eder (ON CONFLICT DO NOTHING)
-- 2. tuple_sync_outbox PENDING enqueue affected role_id'ler için (existing
--    `report:*` OpenFGA tuple'lar `report_group:*` olarak resync edilsin)
-- 3. authz_sync_version bump (FE cache invalidation tetikler)
--
-- Codex 019e2a13 REVISE P1 absorb:
-- > "Direct granule pass missing prefixed granule'ları ekler ve role event
-- >  publish eder; ama dedupe sadece PermissionType + raw key üstünden; raw/
-- >  prefixed logical equivalence yok. PR yalnızca Java mapping'i değiştirir;
-- >  boot sırasında yeni row eklenmezse event/outbox oluşmaz ve eski
-- >  `report:*` OpenFGA tuple'ları `report_group:*` olarak yeniden yazılmaz.
-- >  V18 bunun için doğru örneği göstermiş: DB mutation + tuple_sync_outbox enqueue."

-- Step 1. Insert reports.<GROUP> granule rows for every role that already has
-- the raw <GROUP> granule. ON CONFLICT idempotent — re-run safe.
INSERT INTO role_permissions (role_id, permission_id, permission_type, permission_key, grant_type)
SELECT
    rp.role_id,
    NULL,
    'REPORT',
    'reports.' || rp.permission_key AS new_key,
    rp.grant_type
FROM role_permissions rp
WHERE rp.permission_id IS NULL
  AND rp.permission_type = 'REPORT'
  AND rp.permission_key IN ('HR_REPORTS', 'FINANCE_REPORTS', 'SALES_REPORTS', 'ANALYTICS_REPORTS')
  -- Exclude rows already prefixed (defensive)
  AND rp.permission_key NOT LIKE 'reports.%'
ON CONFLICT (role_id, permission_type, permission_key) WHERE permission_id IS NULL DO NOTHING;

-- Step 2. Schedule OpenFGA tuple sync for roles whose users need report_group
-- tuples (re-write from legacy report:* to report_group:* via TupleSyncService
-- key-aware mapping in PR-B-2 Java code).
INSERT INTO tuple_sync_outbox (role_id, status, created_at)
SELECT DISTINCT rp.role_id, 'PENDING', NOW()
FROM role_permissions rp
WHERE rp.permission_id IS NULL
  AND rp.permission_type = 'REPORT'
  AND (
      rp.permission_key IN ('HR_REPORTS', 'FINANCE_REPORTS', 'SALES_REPORTS', 'ANALYTICS_REPORTS')
      OR rp.permission_key LIKE 'reports.%'
  );

-- Step 3. Bump authz_sync_version so /authz/me cache refresh tetiklenir.
-- Existing version varsa increment; yoksa initial value 1.
UPDATE authz_sync_version SET version = version + 1, updated_at = NOW();

-- Note: roles.permission_model marker güncellemesi gerek YOK; PR-B-2 ile
-- eklenen prefixed granule'lar V18'in raw expansion path'iyle aynı semantic.
-- PermissionDataInitializer next-boot dedupe raw vs prefixed'i logical aynı
-- group olarak görmez (raw key set'lerinden farklı), bu yüzden iki granule
-- coexist eder. AuthorizationControllerV1 reports map deny-wins merge ile
-- normalize edip FE'ye deterministic ALLOW/DENY döndürür (PR-B-2 Java code).
