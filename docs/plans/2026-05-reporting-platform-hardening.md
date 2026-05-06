# Reporting Platform Hardening — Project Plan

| Field | Value |
|---|---|
| **Status** | DRAFT — pending owner approval |
| **Owner** | Platform Reporting (Halil Koçoğlu) |
| **Plan-time consensus** | Codex iter 11–15 (cross-AI, HARD RULE 2026-05-05) |
| **Initiated** | 2026-05-06 |
| **Target completion** | 2026-05-20 (~2 weeks, 7 PRs) |
| **Tracking doc** | this file |
| **Related repos** | `Halildeu/platform-backend`, `Halildeu/platform-web` |

---

## 1. Vision

> Stable, healthy, flexible, performant, long-lived reporting infrastructure for Workcube ERP integration.
>
> - One report UI, many backend tables — flexible composition
> - Backend filter/sort/grouping correctness proven against real MSSQL
> - Grid visual identical across all reports; per-report action menu
> - Single filter entry, single action entry
> - Tenant isolation enforced at both build-time and runtime
> - Schema truth from a single authoritative source (`schema-service`)

## 2. Why this plan exists

The 4-iter loop on `fin-muhasebe-detay` (PRs #69, #70, #73, #74) exposed structural weaknesses:

- Report JSON contract has no semantic validator — wrong `rowFilter.column` (counterparty vs tenant id) shipped to production silently
- `schemaMode`, `sourceSchema`, `tenantBoundary` relationships are not CI-enforced
- `sourceQuery` and `columns[]` projections are not cross-checked
- Tenant boundary lives in tribal knowledge, not in code
- Errors surface only when users report them (no per-report metric)
- Server-side grouping is sent by frontend but ignored by backend (user-reported regression)

The plan addresses these gaps in 8 programs delivered as 7 sequential PRs.

## 3. Programs

### 3.1 Program 1 — Report Contract Gate (build-time)

**Outcome:** New or changed report definitions pass schema + semantic validation in CI before merge.

**Deliverables:**
- `report-service/src/main/resources/reports/report-definition.schema.json` (Draft 2020-12)
- `report-service/src/main/resources/reports/tenant-column-allowlist.json`
- `report-service/src/main/resources/reports/exceptions.json` (`id`, `ruleIds`, `reason`, `owner`, `expiresAt`)
- Java validator package: `com.example.report.contract.*`
- 11 validator rules (RC-001..011)
- `ReportDefinitionContractTest` integrated into `mvn -pl report-service test`
- `target/report-contract-summary.md` artifact (PR-readable)
- ADR: `docs/adr/0006-report-definition-contract.md`

**New schema fields on every report JSON:**
```json
"contractVersion": 1,
"schemaMode": "yearly | current | canonical | static",
"tenantBoundary": {
  "mode": "schema | row | none",
  "scopeType": "COMPANY",
  "schemaResolver": "workcube-year-company | workcube-current-company | none",
  "schemaPattern": "workcube_mikrolink_{year}_{companyId}",
  "reason": "string"
}
```

**`schemaMode` enum (4 values):**
- `yearly` → `workcube_mikrolink_{year}_{companyId}` (yearly fact tables)
- `current` → `workcube_mikrolink_{companyId}` (per-tenant current state) — NEW
- `canonical` → `workcube_mikrolink` (master/lookup)
- `static` → exception only (legacy hardcoded)

**Validator rules (RC-001..011):**

| ID | Rule | Severity |
|---|---|---|
| RC-001 | `schemaMode=yearly` requires `yearColumn` | FAIL |
| RC-002 | yearly + custom `sourceQuery` requires `[{schema}]` placeholder | FAIL |
| RC-003 | hardcoded `workcube_mikrolink_YYYY_ID` + `static` mode forbidden | FAIL Tier 0; WARN Tier 1+ |
| RC-004 | `rowFilter.scopeType=COMPANY` column must be in per-table allowlist; column existence verified via schema-service snapshot | FAIL |
| RC-005 | `tenantBoundary.mode=schema` + non-empty `rowFilter` is forbidden (boundary clarity) | FAIL |
| RC-006 | `tenantBoundary.mode=none` reports cannot reference tenant fact tables | FAIL |
| RC-007 | `columns[].field` projections must exist in `sourceQuery` SELECT (heuristic) | WARN |
| RC-008 | `schemaResolver` value must be in registered list | FAIL |
| RC-009 | `action.scope` must be `grid \| row \| selection` | FAIL |
| RC-010 | destructive actions require non-null `permission` and `confirmation` | FAIL |
| RC-011 | (TS-level) `action.handler` must be async — enforced at TypeScript level, not in JSON | FAIL |

**Acceptance:**
- All 5 Tier 0 reports carry explicit `tenantBoundary`
- All other reports run in warn-only mode initially
- Any new or modified report fails CI if it violates RC-001..010
- Markdown summary attached to every PR via Maven Step Summary

---

### 3.2 Program 2 — Runtime Tenant Guard

**Outcome:** Tenant boundary enforced inside the request path, not just at build-time.

**Deliverables:**
- `TenantBoundaryGuard` preflight component
- `CurrentTenantSchemaResolver` (NEW resolver for `workcube_mikrolink_{companyId}` pattern)
- Hardening of `YearlySchemaResolver`:
  - No `def.sourceSchema()` fallback for `tenantBoundary.mode=schema` reports
  - `extractCompanyFromSchema()` only as legacy exception
  - Super-admin without header on tenant-bound report → 400 (selection required)
- Schema existence check via `schema-service` (`/api/v1/schema/snapshot`)

**Failure semantics:**
- 400 — header missing on tenant-bound report (multi-company / super-admin); single-company users auto-selected
- 403 — selected company outside user scope (existing PR #70 behaviour)
- 503 — resolved schema does not exist + `report_resolved_schema_miss` metric increment
- Frontend MUST NOT silent-fallback; surface error state and toast

**Acceptance:**
- `RowFilterInjectorRlsTest` extended with super-admin + explicit scope assertions
- New `TenantBoundaryGuardTest` and `CurrentTenantSchemaResolverTest`
- Integration test (Testcontainers MSSQL) covers all four failure modes

---

### 3.3 Program 3 — Action Menu Standard

**Outcome:** Every grid offers actions in a consistent visual location; content varies per report.

**Deliverables:**
- `EntityGridTemplate` API extension: `actions?: ReportAction<TRow>[]`
- `ReportModule.actions?` field on the module type (mfe-reporting)
- Three render slots driven by `scope`:
  - `grid` → toolbar `[Eylemler ▾]`
  - `row` → AG Grid pinned-right action column with three-dot menu
  - `selection` → `[Seçili (N) ▾]` toolbar shown when rows selected
- A11y: `role="menu"`, `role="menubutton"`, keyboard navigation, `aria-label`
- Confirmation modal for destructive actions
- Permission semantics:
  - `permission: string | null` — `null` = inherit report-view permission
  - `permission: undefined` → config bug, fail in dev/test, hidden + warn in production
  - destructive actions cannot have `permission: null`
- `requiresSingleCompany` shows action as disabled with tooltip when not satisfied
- New metric: `report_action_invoked_total{report, action_id, status}`

**Discriminated union:**
```ts
type ReportAction<TRow> =
  | (BaseAction & { scope: 'grid'; handler: (ctx: GridActionContext) => Promise<void> })
  | (BaseAction & { scope: 'row'; handler: (ctx: RowActionContext<TRow>) => Promise<void> })
  | (BaseAction & { scope: 'selection'; handler: (ctx: SelectionActionContext<TRow>) => Promise<void> });
```

**Empty-set rule:** if a report defines no `actions`, the toolbar button, action column, and selection toolbar are all hidden — same suppression pattern used for `filterBuilderPrefix`.

**Acceptance:**
- Two example actions wired (one grid-scope, one row-scope) on a real report
- Storybook story for each scope
- A11y test (axe) covers all three slots

---

### 3.4 Program 4 — Operational Observability

**Outcome:** Production issues surface from telemetry before users report them.

**Metrics (cardinality-controlled):**
- `report_query_total{report, schemaMode, status, sqlState}`
- `report_query_duration_seconds{report, schemaMode}`
- `report_query_rows{report}` histogram
- `report_resolved_schema_miss{report}`
- `report_company_header_narrow_total{status}` (allowed | forbidden | missing)
- `report_action_invoked_total{report, action_id, status}`

`userId` is NOT used as a metric label. It appears in the structured query log instead.

**Structured query log:**
```json
{
  "report": "fin-muhasebe-detay",
  "userId": "...",
  "companyId": "5",
  "schemaMode": "yearly",
  "resolvedSchemas": ["workcube_mikrolink_2026_5"],
  "rowCount": 123,
  "durationMs": 180,
  "errorCode": null
}
```

**Initial alerts (3):**
- SQL state 207/208 spike (Invalid object/column name)
- Per-report error rate > threshold
- Resolved schema miss > threshold for tenant-bound reports

**Acceptance:**
- Grafana panel with the six metrics
- Alert routes to ops channel (deferred to ops follow-up — initial: log + metric)

---

### 3.5 Program 5 — Grid Governance

**Outcome:** No new direct `AgGridReact` usage in production grids; existing exceptions documented.

**Deliverables:**
- ADR: `EntityGridTemplate` is the canonical production grid
- ESLint `no-restricted-imports`: `apps/**` cannot import `ag-grid-react` except via allowlist
- Allowlist (with reason comments):
  - `mfe-audit/AuditEventFeed` — capability gap (infinite row + WebSocket feed)
  - `mfe-reporting/context-health` — being migrated
  - `mfe-reporting/hr-compensation/CompensationDashboard` — embedded chart, not a grid
  - design-system internals, tests, Storybook, design-lab showcase
- `context-health/GridTabPanel` migrated to `EntityGridTemplate`
- `AuditEventFeed` capability gap spike (NOT migration) — deliverable: ADR section listing required `EntityGridTemplate` extensions (`infinite` row model, `createInfiniteDatasource`, live refresh hook, deep-link highlight, async export job)
- `filterBuilderPrefix` Storybook story

**Explicitly NOT done in this program:**
- `createGridConfig()` helper — abstraction debt risk
- Locale dictionary merge `shared.grid.*` — i18n breaking risk

---

### 3.6 Program 6 — Filter / Sort / Grouping Backend Correctness

**Outcome:** AG Grid filter/sort/grouping requests produce semantically correct T-SQL on real MSSQL, with property-based protection against SQL injection.

#### 6a — Filter / Sort / Null / Injection Matrix

**Deliverables:**
- `FilterTranslator` becomes type-aware (column type from schema-service)
- Generated parameterized cases (~50–70):
  - Column types: `nvarchar | int | bigint | decimal | datetime | bit | char`
  - Operators: `equals | notEqual | contains | notContains | startsWith | endsWith | blank | notBlank | lessThan | greaterThan | inRange`
  - NULL semantics: blank vs notBlank, IS NULL, NULL in IN/NOT IN, NULL after LEFT JOIN
  - Locale: Turkish collation (`Turkish_CI_AS`) for ç/ğ/ı/i/İ
- Property-based tests (jqwik) for SQL injection: random filter values must always bind via named params, never reach the SQL string literally
- Sort: multi-column, NULL ordering (`NULLS FIRST/LAST`), date sort with timezone
- Multi-year UNION ALL test: filter range 2024–2025 produces queries against both `workcube_mikrolink_2024_<id>` and `workcube_mikrolink_2025_<id>`
- MSSQL Testcontainers integration with deterministic minimal seed:
  - primitive test table
  - NULL-heavy table
  - JOIN fixture
  - yearly schema fixture (`workcube_mikrolink_2024_1`, `_2025_1`)
  - dimension table

**Acceptance:**
- Tests pinned in CI as a separate `@Tag("integration")` Maven profile
- Slow query log threshold 2s warn / 10s error
- All advanced filter operators round-trip through MSSQL with documented expected SQL

#### 6b — Server-Side Grouping (proper implement)

**User-reported regression:** AG Grid sends `rowGroupCols`, `groupKeys`, `valueCols`; backend ignores them. The fix is end-to-end:

1. Frontend (`mfe-reporting/grid` types + `dynamic-report/api.ts`):
   - Extend `GridRequest` with `rowGroupCols`, `groupKeys`, `valueCols`, `pivotMode`, `pivotCols`
   - Forward through to backend as URL/query params
2. Backend `GridRequest` DTO + `ReportController.getData`:
   - Accept the new params
   - Pass through to `QueryEngine`
3. Backend `QueryEngine` + `SqlBuilder`:
   - Compute drill level (`groupKeys.length < rowGroupCols.length` → group level; equal → leaf)
   - SELECT: at group level → group columns + aggregated value columns; at leaf level → unchanged
   - GROUP BY: group columns up to the current depth
   - WHERE: parent group keys filter
   - Aggregations: `SUM`, `AVG`, `COUNT`, `MIN`, `MAX`
   - Row count at group level: `COUNT(*)` over groups

**Acceptance:**
- Multi-level drill (Şube > Departman > Çalışan) returns correct group count + aggregates
- NULL group key handled deterministically (e.g. "(Boş)" bucket)
- Lazy expand: drilling into a group fetches only that group's children
- Row count cap: 50k groups (server returns 503 if exceeded; frontend shows guidance)
- Column type comes from schema-service (no hardcoded type map)

---

### 3.7 Program 7 — Multi-table Composition Governance

**Outcome:** Reusable, vetted JOIN fragments + column metadata. NOT a dynamic SQL composer.

**Deliverables:**

`JoinFragmentRegistry`:
```json
{
  "id": "ourCompany",
  "tables": ["[workcube_mikrolink].[OUR_COMPANY]"],
  "alias": "OC",
  "joinTemplate": "LEFT JOIN [workcube_mikrolink].[OUR_COMPANY] OC ON OC.COMP_ID = ?",
  "outputs": ["OC.COMP_ID", "OC.NICK_NAME", "OC.COMPANY_NAME"],
  "owner": "reporting-platform"
}
```

Initial fragments (5):
- `ourCompany` (tenant master)
- `company` (counterparty / cari)
- `department`
- `project`
- `currencyRate` (`MONEY_HISTORY` EUR/USD/TCMB)

`ColumnTypeRegistry`:
- `Tutar` → `decimal(18,2)`, currency `TRY` default, format `tr-TR`
- `İşlem Tarihi` → `date`, format `dd/MM/yyyy`
- `Cari Adı` → `text`, width 230
- ... initial set documented in `docs/adr/0007-column-type-registry.md`

**Validator hooks (built on top of Program 1):**
- Cross-check `sourceQuery` JOINs against schema-service FK graph (`/api/v1/schema/impact/{table}`)
- Fragment alias collision detection
- Output projection match (column referenced in `columns[]` must come from a known fragment or an explicit projection)

**Explicitly out of scope:**
- Full dynamic `ReportComposer` that builds SQL at runtime — production plan stability would suffer
- Replacing every existing static `sourceQuery` — narrow adoption only

---

### 3.8 Program 8 — Schema Truth Integration (cross-cutting)

**Outcome:** Schema metadata has one authoritative source: `schema-service`.

**Build-time:**
- Validator loads canonical snapshot from `docs/migration/workcube-schema.json` (already committed)
- Optional CI step pulls a fresh snapshot from schema-service before validator runs

**Runtime backend:**
- `FilterTranslator` is column-type-aware, type fetched from schema-service
- `TenantBoundaryGuard` calls `schema-service` `schemaExists()` before executing tenant-bound queries
- `SqlBuilder` uses column type for grouping/aggregation choices

**Runtime frontend:**
- `useReportSchemaContext` hook (already exists) used for AG Grid `colDef` enrichment
- Filter type picked automatically from column data type (text → `agTextColumnFilter`, etc.)

**Acceptance:**
- No hardcoded column type maps in backend
- Stale report definition (column renamed in schema) caught by validator at build-time

## 4. Sprint Plan (7 PRs)

| PR | Title | Programs | Effort | Schema-service usage |
|---|---|---|---|---|
| **PR-1** | Tenant Contract Gate + Runtime Guard (combined) | 1 + 2 + 8 partial | 2.5 d | snapshot file + service runtime |
| **PR-2** | Action Menu Standard | 3 | 1.0 d | — |
| **PR-3** | Observability + Grid Governance MVP | 4 + 5 | 1.5 d | metric infrastructure |
| **PR-4a** | Filter/sort/null/injection matrix + column header type-awareness | 6 part 1 + 8 partial | 2.0 d | column type lookup |
| **PR-4b** | Server-side grouping (proper implement) | 6 part 2 | 2–3 d | column type for aggregates |
| **PR-5** | JoinFragmentRegistry + ColumnTypeRegistry + composition validator | 7 + 8 partial | 2–3 d | FK graph |
| **PR-6** | HR / satış / stok migration wave + exceptions burn-down | follow-up | 1.5 d | column existence verify |

**Total:** 12–14 working days end-to-end with Codex peer review at every step.

## 5. Migration Tier (existing 27 reports)

| Tier | Count | Pattern | Blocking when |
|---|---:|---|---|
| **0** Tenant fact, ready | 5 | yearly + custom sourceQuery + rowFilter cleared (PR #74) | PR-1 |
| **B** Tenant fact, working with substitution | 9 | yearly + `sourceSchema=workcube_mikrolink_1` + yearColumn | Tier 1 batch (PR-6) |
| **C** Tenant fact, hardcoded year+id | 4 | yearly + `sourceSchema=workcube_mikrolink_2026_1` + yearColumn | Tier 1 batch (PR-6) |
| **D** HR | 9 | `mode=null` + `sourceSchema=workcube_mikrolink` | **Owner clarification needed** |
| **E** satis-ozet + stok-durum | 2 | hardcoded tenant-1 schema + `mode=null` | **Owner clarification needed** |
| **F** Canonical / lookup | 1 | `fin-butce-gerceklesen` master schema | Reviewed in PR-1 |

Reports outside Tier 0 stay in **warn-only** mode in Program 1 until they pass through PR-6.

## 6. Open Decisions

| # | Decision | Default | Owner | Required by |
|---|---|---|---|---|
| **D-1** | PR-4b grouping: (a) proper implement, (b) UI guard, (c) whitelist | (a) per Codex iter-15 | Owner | PR-4b kickoff |
| **D-2** | HR reports (9): tenant-specific or master? | TBD | Owner | PR-6 kickoff |
| **D-3** | satis-ozet + stok-durum tenant-specific? | Likely yes (current `sourceSchema` is BUG) | Owner | PR-6 kickoff |
| **D-4** | Multi-customer SaaS model | Defer; document `CustomerContext` contract | Architect | post-PR-6 |
| **D-5** | Cross-tenant admin reports | Default off; opt-in via `tenantBoundary.mode="multiTenantAggregate"` | Architect | post-PR-6 |

PR-1, PR-2, PR-3, PR-4a, PR-5 do NOT depend on D-2 / D-3 — they can proceed in parallel with the open questions.

## 7. Risk Register

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-1 | Validator false-positives block legitimate reports | Med | Med | Tier 1+ warn-only initially; expand blocking gradually |
| R-2 | MSSQL Testcontainers slow CI | Med | Low | Separate `@Tag("integration")` profile; baseline run nightly + on-demand |
| R-3 | Server-side grouping schema-service dependency | Low | High | Type registry fallback to `columns[].type` if schema-service unavailable, with WARN log |
| R-4 | Action Menu permission default ambiguity | Low | High | Explicit `null` vs `undefined` semantics; destructive actions cannot have `null` |
| R-5 | AuditEventFeed migration regression | High | High | NO migration in this plan; spike-only deliverable |
| R-6 | YearlySchemaResolver `sourceSchema` fallback removal breaks legacy reports | Med | Med | Migration tier; only Tier 0 + new/changed enforced first |
| R-7 | i18n key drift if locale dictionary merged | High | Med | NOT done; leave app-specific keys |

## 8. Performance Guarantees (asserted in every PR)

- SQL query timeout: 30 s (Hikari)
- Slow query log threshold: 2 s warn / 10 s error
- Per-report cache (Caffeine) optional; companyOptions 5 min TTL is the reference pattern
- AG Grid SSR `cacheBlockSize=50`
- Frontend lazy chunks + Module Federation share for grid + ag-grid
- Production p95 alerting (deferred to ops integration)
- NO JMH micro-benchmark in V1 (real bottleneck is SQL plan, not Java translation)

## 9. Stability Guarantees (asserted in every PR)

- Cross-AI peer review (HARD RULE 2026-05-05): every PR reviewed by Codex
- Build-time validator + runtime guard (double gate)
- ADR + exception registry for audit trail
- Schema truth single source: `schema-service` + committed snapshot
- Per-report metric for early bug detection
- Migration tier: warn-only Tier 1+, fail-fast Tier 0 + new/changed
- MSSQL Testcontainers covers integration semantics

## 10. Don't-Do List (Codex consensus)

- ❌ `createGridConfig()` helper — abstraction debt
- ❌ Locale dictionary merge — i18n breaking risk
- ❌ AuditEventFeed migration without spike — capability gap
- ❌ H2 in-memory test DB — T-SQL dialect divergence
- ❌ Full dynamic `ReportComposer` — production plan instability
- ❌ Big-bang Tier 1 migration — CI lock risk
- ❌ Cross-tenant report by default — admin-only opt-in
- ❌ JMH micro-benchmarks in V1 — wrong layer
- ❌ 140 manual filter test cases — 50–70 generated cases sufficient
- ❌ Multi-customer schema namespace expansion — DB-per-customer preferred
- ❌ Both ESLint + runtime contract guard — ESLint sufficient

## 11. PR-1 Acceptance Checklist

- [ ] Branch `feat/report-contract-gate-and-tenant-guard` opened
- [ ] Codex plan-time consultation (new thread) AGREE
- [ ] `report-definition.schema.json` (Draft 2020-12) committed
- [ ] `tenant-column-allowlist.json` committed
- [ ] `exceptions.json` skeleton committed
- [ ] Java validator package: 11 RC rules implemented
- [ ] `CurrentTenantSchemaResolver` implemented
- [ ] `YearlySchemaResolver` fallback hardened (no `def.sourceSchema()` for tenant-bound)
- [ ] `TenantBoundaryGuard` implemented and wired into `QueryEngine`
- [ ] `ReportDefinitionContractTest` integrated into `mvn -pl report-service test`
- [ ] `target/report-contract-summary.md` produced
- [ ] 5 Tier 0 reports carry explicit `tenantBoundary`
- [ ] Tier 1+ reports infer `tenantBoundary` (warn-only)
- [ ] ADR `docs/adr/0006-report-definition-contract.md` written
- [ ] Schema-service `schemaExists()` integration smoke-tested
- [ ] All existing tests still pass (`mvn -pl report-service test`)
- [ ] Codex peer review — AGREE
- [ ] Cross-AI HARD RULE: implementer ≠ reviewer
- [ ] Cluster smoke: muavin still works for super-admin + scoped users
- [ ] No `Invalid column` errors in 24h after deploy
- [ ] PR description references this plan and lists which checklist items completed

## 12. Status Tracking

This section is updated at every PR open / merge / deploy.

| PR | Status | Branch | Codex iter | Merged at | Deploy digest | Notes |
|---|---|---|---|---|---|---|
| **PR-1** | NOT STARTED | — | — | — | — | Awaiting owner approval |
| PR-2 | NOT STARTED | — | — | — | — | Depends on PR-1 |
| PR-3 | NOT STARTED | — | — | — | — | Depends on PR-1 |
| PR-4a | NOT STARTED | — | — | — | — | Depends on PR-1 |
| PR-4b | NOT STARTED | — | — | — | — | Depends on PR-4a + D-1 |
| PR-5 | NOT STARTED | — | — | — | — | Depends on PR-4a |
| PR-6 | NOT STARTED | — | — | — | — | Depends on D-2 + D-3 |

## 13. Decision Log

| Date | Decision | Made by |
|---|---|---|
| 2026-05-06 | 8-program plan approved as the working baseline | DRAFT — pending owner approval |
| 2026-05-06 | PR-1 combines Programs 1 + 2 (no gap between build-time and runtime gates) | Codex iter-12 |
| 2026-05-06 | Action menu API: single `actions: ReportAction[]` with discriminated union | Codex iter-14 |
| 2026-05-06 | `permission: string \| null`, `undefined` is config error | Codex iter-14 |
| 2026-05-06 | Server-side grouping: proper implement preferred over UI guard | Codex iter-15 |
| 2026-05-06 | Filter test matrix ~50–70 generated cases + 10–15 edge cases (not 140 manual) | Codex iter-15 |
| 2026-05-06 | MSSQL Testcontainers required (no H2) | Codex iter-15 |
| 2026-05-06 | `JoinFragmentRegistry` is vetted-id based, NOT dynamic composer | Codex iter-15 |

## 14. References

- Cross-AI HARD RULE: `~/.claude/CLAUDE.md` (2026-05-05)
- Existing infrastructure docs: `~/.claude/projects/<slug>/memory/`
- Report registry: `report-service/src/main/resources/reports/*.json`
- Canonical schema snapshot: `docs/migration/workcube-schema.json`
- Schema service endpoints: `schema-service/src/main/java/com/example/schema/controller/`

## 15. Approval

This plan requires explicit owner approval before PR-1 starts.

- [ ] **Owner approval** — date / signature
- [ ] **Architect approval** (multi-customer SaaS direction) — deferred
- [ ] **D-1 (PR-4b grouping approach)** — selected option:
- [ ] **D-2 (HR reports)** — answer:
- [ ] **D-3 (satis-ozet + stok-durum)** — answer:

Once approved, change this document's `Status` field from `DRAFT` to `ACTIVE`, then proceed with PR-1.
