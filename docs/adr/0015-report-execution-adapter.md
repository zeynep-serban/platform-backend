# ADR-0015 — Report Execution Adapter (remote-http executor pattern)

**Status**: Accepted
**Date**: 2026-06-01
**Cross-AI consensus**: Codex thread `019e8306` AGREE D verdict

## Context

D-chain karma migration (PR-D2b hr-demografik + hr-compensation) sonrası 5 pure-grid modül (users-overview / audit-report / monthly-login / access-report / weekly-audit-digest) hâlâ static frontend code'lu. Backend-metadata-driven dynamic report sözleşmesinin (PR-A/B/C contract chain + PR-D1a/D1b factory) 5 modüle de uygulanması istendi.

Pure-grid modüllerin kaynakları farklı servislerde:
- `users-overview` → `user-service` `/api/v1/users` (JSON kontrat user-service'in)
- `audit-report` → `notification-orchestrator` audit endpoint
- `monthly-login` → audit/notification kaynaklı materialized view veya report-service
- `access-report` → `permission-service` `/api/v1/roles`
- `weekly-audit-digest` → scheduled audit aggregation (report-service mart layer)

Şu anki backend yapısı:
- `report-service` Spring Boot + JPA + MSSQL (Workcube source); `ReportDefinition` JSON resources/reports/*.json okur; `QueryEngine` primary MSSQL `NamedParameterJdbcTemplate` üzerinden execute eder.
- Pure-grid modüllerin kaynak servisleri report-service değil; her birinin kendi RestController + DTO + repo'su var.

## Decision

**D: backend-metadata-driven, source-owned execution adapter**.

Yani:

- **Metadata/canonical report contract**: `report-service` (ReportDefinition JSON, filterDefinitions, access, sharedReportId, routeSegment)
- **Operasyonel data ownership**: orijinal servisler (user-service, permission-service, audit/notification hattı) — bounded-context değişmez
- **Execution**: `report-service` içinde allowlist'li `remote-http` (veya aggregation mart) executor — gelen `ReportDefinition.execution.kind` field'ına göre dispatch
- **Frontend**: mevcut dynamic factory yeterli; virtual adapter YOK

### ReportDefinition schema extension

```json
{
  "key": "users-overview",
  "routeSegment": "users",
  "sharedReportId": "users-overview",
  "execution": {
    "kind": "remote-http",
    "service": "user-service",
    "path": "/api/v1/users",
    "responseShape": "paged-items-total"
  },
  "columns": [...],
  "filterDefinitions": [...]
}
```

`execution` alanı:
- `kind`: `remote-http` | `sql` (default — legacy MSSQL reports)
- `service`: allowlist'li servis adı (`user-service`, `permission-service`, `notification-orchestrator`, ...)
- `path`: allowlist'li servis-internal path (`/api/v1/users`, `/api/v1/roles`, ...)
- `responseShape`: response normalizer kontrat (`paged-items-total`, `items-array`, ...)

Legacy reports (`hr-demografik-yapi`, `hr-compensation-detay`, vb.) `execution` alanı belirtmezler; default `sql` kind ile mevcut `QueryEngine` MSSQL path'i çalışır.

### RemoteReportExecutor sözleşmesi

- **Allowlist enforcement**: arbitrary URL execution YASAK. Sadece config'de tanımlı `(service, path)` çiftleri kabul edilir.
- **Auth propagation**: incoming user JWT veya auth context downstream servise iletilir (`Authorization: Bearer <token>` header).
- **Tenant context**: `X-Company-Id` header standart geçiş.
- **Timeout**: bounded (örn. 30s).
- **Error mapping**: structured (401 → `RemoteAuthException`, 403 → `RemoteAuthzException`, 5xx → `RemoteExecutionException`).
- **Param mapping**: `responseShape` ile birlikte tanımlı transform (örn. `paged-items-total` → backend response `{items, total}` → frontend grid data + total).
- **Filter projection**: `filterDefinitions[].key` frontend filter state key'leri; backend allowlist'li query param'lara map'lenir (örn. `search`, `status`, `page`, `pageSize`, `sort`).

### Frontend impact

- Mevcut dynamic factory `createDynamicReportModule(report)` zaten `routeSegment`/`sharedReportId`/`filterDefinitions` kontratını tüketiyor.
- 5 pure-grid modül için ek bir abstraction (virtual adapter) **eklenmez** — dynamic catalog'da `users-overview` route'u görüldüğünde static `users-report` module REPLACE edilir (PR-D1b.A useCatalog dedupe pattern).
- `gridIdOverride`/`moduleId` carry edilmesi gerekebilir (örneğin `reports.users` legacy grid variant state'i için, dynamic key `reports.dynamic.users-overview`'a otomatik geçmemeli). PR-D2.1d slice'ında ele alınır.

## Alternatives rejected

| Opsiyon | Niye reddedildi |
|---|---|
| **A (frontend virtual adapter)** | Backend-metadata-driven hedefe ters; her yeni report frontend code requires; long-term "metadata-driven" zincire ters |
| **B (report-service SQL-view per modül)** | Data ownership / bounded-context ihlali. Pure-grid kaynaklar farklı servislerin tabloları; replicated view veya cross-service join report-service'i transactional veri sahibi yapar |
| **C (case-by-case)** | Tutarsız; modüller arasında pattern drift'i; PR-E (dynamic-by-default gate) ratchet için clear contract gerek |

## Consequences

### Positive

- Backend ReportDefinition tek **canonical metadata kaynağı** — frontend kod yazımı sıfır per new module.
- Source services bounded context'i korunur; veri ownership değişmez.
- Future audit-mart, weekly-aggregation, schedule-driven reports için aynı executor pattern (kind genişletmeleri: `aggregation-mart`, `materialized-view`, vb.).
- Cross-AI consensus tek pattern üzerinde (Codex 019e8306 D AGREE iter-1).

### Negative / risks

- **Yeni executor surface**: allowlist + auth propagation + error mapping + timeout = ekstra code path. Test coverage gerek (unit + WireMock integration).
- **Latency**: remote-http hop ekler (report-service → downstream service). Local Spring services arasında <50ms beklenir; cross-cluster prod scenario değil (testai k3d-test'te aynı node).
- **Auth context propagation karmaşıklığı**: incoming JWT'nin downstream'e iletilmesi standart Spring Cloud Gateway pattern değil — manuel header geçişi (özel `WebClient` filter).

## Implementation plan (PR-D2.1 chain — Codex 019e8306 1-haftalık slice)

| PR | Scope | Çıktı |
|---|---|---|
| **PR-D2.1a** | **THIS ADR (docs only)** | ADR-0015 mühürlü |
| **PR-D2.1b** | `ReportDefinition.execution` + `ExecutionConfig` record + JSON schema update | Schema ready (NO migration, NO new report) |
| **PR-D2.1c** | `RemoteReportExecutor` impl + WireMock integration test + dispatcher in `QueryEngine`/`ReportController` | Executor source-ready |
| **PR-D2.1d** | `users-overview.json` ekle + frontend smoke test (catalog dedupe + grid state) | First pure-grid module LIVE |

Sprint estimate (Codex): **3 sprint + 1 buffer** for 5 modules (users → access → audit → monthly-login → weekly-audit-digest).

PR-E (dynamic-by-default gate) tetikleyici noktası: 5 modülün hepsi dynamic path'te live smoke gördükten sonra.

## PR-E Closure — Dynamic-by-default ratchet (2026-06-01)

D-chain 5/5 LIVE achieved (users-overview / access-report / audit-report / monthly-login / weekly-audit-digest). PR-E gate closed as a **test-only ratchet** rather than a runtime constant — governance intent, not behavior coupling.

### Ratchet location

- Test: `report-service/src/test/java/com/example/report/contract/ReportDefinitionContractTest.java` — `dynamicByDefault_migratedPureGridReportsStayRemoteHttp()`
- Lock: test-local `DYNAMIC_BY_DEFAULT_REPORTS` map (key → `(service, path, responseShape)` tuple)
- Codex consensus thread: `019e8543` (AGREE WITH AMENDMENTS — test-only over production-constant, tuple-locked over kind-only)

### What it enforces

Each of the 5 migrated pure-grid modules MUST keep:
- `execution.kind == REMOTE_HTTP`
- Exact `(service, path, responseShape)` tuple

Reverting any to SQL path, swapping downstream endpoint, or changing response shape → test fails. Explicit governance decision required to relax (update test map + this ADR section in tandem).

### What it does NOT enforce

- Frontend `useCatalog.ts` stays warn-only (static fallback remains for runtime resilience). The right guard for cluster drift is live smoke / e2e evidence (HARD RULE — Tarayıcıdan Sonuç Doğrulanmadan), not frontend fail-closed.
- Allowlist drift (`check_reporting_allowlist_drift.py`) remains orthogonal — those are table-level RLS contracts; PR-E ratchet is report-level definition contract.

### Why test-only (not production constant)

A production `DynamicByDefaultRegistry.MIGRATED_KEYS` constant would suggest live authority (filtering at runtime), but the actual runtime behavior remains `RemoteAllowlist` (service+path) and `report.remote-executor.enabled` flag. Test-only avoids false coupling; the set's purpose is governance regression protection, not runtime behavior.

### Future migrations (PR-D2.6+)

When a 6th key migrates, update `DYNAMIC_BY_DEFAULT_REPORTS` map + this ADR section together. Auto-discovery is deliberately avoided — it would silently accept any new remote-http report as "expected", defeating the ratchet purpose.

## References

- Codex thread `019e8306-c0f3-7012-9812-07b0ef99fa6f` (D verdict; pure-grid modül tablosu)
- Codex thread `019e8543-aaa7-79f2-ad19-245dfbc0570f` (PR-E plan + merge gate consensus AGREE WITH AMENDMENTS)
- ADR-0006 (report-contract-gate)
- PR-D1a (Codex 019e800b) backend schema extension (routeSegment + sharedReportId + filterDefinitions)
- PR-D1b.A/.B (Codex 019e8245) frontend transport DTO + factory + filter execution path
- PR-D2b (Codex 019e8269) hybrid wrapper karma route preservation
