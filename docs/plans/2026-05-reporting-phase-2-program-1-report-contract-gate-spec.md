# Phase 2 — Program 1: Report Contract Gate (build-time validator)

> **Status**: Spec draft — owner UX feedback bekliyor. Plan v2.1
> §3.1 detayını implementation-ready hale taşır.
>
> **Authors**: Claude (autonomous mode, 2026-05-07).
>
> **Cross-AI peer review**: Codex thread `019e0119-7c9d-7541-8059-
> f9553c3303ce` iter-X (post-PR-0.4-spec consensus extension).
>
> **Related PRs**:
> - Plan v2.1 PR #75 (`docs/plans/2026-05-reporting-platform-hardening.md`) §3.1
> - PR-0.4 spec PR #90 (pattern reference — 9 iter Codex AGREE)

---

## 0. TL;DR — Karar Götürülecek 6 Soru

| # | Soru | Default önerisi | Alternatif |
|---|---|---|---|
| 1 | Validator nerede çalışacak (build-time only mu, runtime de mi)? | Build-time only (`mvn -pl report-service test`'in parçası); runtime'da loaded definitions zaten geçti varsayımı | Hem build-time hem runtime startup gate (zorlu, double safety) |
| 2 | `RC-003` "hardcoded `workcube_mikrolink_YYYY_ID`" Tier 0 (current period) için FAIL mı, yoksa hep WARN'a çekip migration grace period mı? | Tier 0 FAIL + Tier 1+ WARN (Plan v2.1 §3.1 default) | Hep WARN (legacy migration süreci uzun) |
| 3 | `exceptions.json` schema: ne kadar liberal? | `id`, `ruleIds`, `reason`, `owner`, `expiresAt` — `expiresAt` zorunlu (auto-expire 90 gün max) | `expiresAt` opsiyonel (kalıcı exception YASAK ama bypass için elastic) |
| 4 | Schema-service unreachable durumunda RC-004 (column existence check)? | **Committed snapshot primary** (`docs/migration/workcube-schema.json`); schema-service optional fresh refresh (CI step). Snapshot age >30 gün: validator WARN + summary counter. Schema-service unreachable: silent — committed snapshot zaten primary kaynak (Codex iter-1 §5 absorb). | FAIL (CI block — strict; build-time validator deterministic snapshot prensibi bozulur) |
| 5 | TypeScript `RC-011` (`action.handler` async check) hangi katmanda enforce? | `mfe-reporting/.../EntityGridTemplate.tsx` types + ESLint plugin custom rule | sadece runtime check (TS type yetersiz olabilir) |
| 6 | Acceptance: validator suite test çıktısı PR'a nasıl rapor edilir? | `target/report-contract-summary.md` PR sticky comment olarak (CI step) | sadece CI log + Surefire XML (manual check) |

---

## 1. Bağlam

### 1.1 Plan v2.1 referansı

- Plan §3.1 `Program 1 — Report Contract Gate` (build-time)
- Plan §3.8 `Program 8 — Schema Truth Integration` (cross-cutting; bu Program 1'in `schema-service` çağrısı altyapısını sağlar)
- Plan §3.6 `Program 6 — Filter/Sort/Grouping Backend Correctness` 6b PR-0 zincirine taşındı (PR #79/#81/#82/#86/#88) — Program 1 onları validator gate'inden geçirir

### 1.2 Halihazırda yerleşik altyapı

- `report-service/src/main/resources/reports/<key>.json` — registry (8+ rapor şu an: fin-muhasebe-detay vb.)
- `docs/migration/workcube-schema.json` — schema snapshot (3.4 MB, 1509 tablo, 26240 kolon, 1774 FK)
- `schema-service` `/api/v1/schema/snapshot` — runtime schema source
- ADR-0011 §2.3 boundary declaration governance pattern (PR-time validator pattern referans noktası)

### 1.3 Bilinen eksiklikler (sorunun gerçeklik kanıtı)

- 2026-05'ten önce `OUR_COMPANY_ID` rowFilter bug'ı (PR #74'te düzeltildi) — kolon mevcut olmayan tabloya tenant filter referansı; build-time validator olsaydı yakalardı
- `static` schemaMode hâlâ legacy raporlarda hardcoded `workcube_mikrolink_2026_35` görür — RC-003 production-grade FAIL gerek

---

## 2. Validator Architecture

### 2.1 Java package layout (Codex iter-1 §1 absorb — build-time vs runtime ayrımı net)

Build-time only kararı (Q1 default) ile package layout test ve main source ayrımı:

**Test source** (`src/test/java`) — sadece `mvn test` lifecycle'ında çalışır:

```
report-service/src/test/java/com/example/report/contract/
  └── ReportDefinitionContractTest.java     // JUnit @ParameterizedTest, classpath rapor sweep
```

**Main source** (`src/main/java`) — runtime classpath'te yer alır ama **runtime component scan / startup gate YOK**; sadece test classpath'in erişebildiği POJO/parser/registry yardımcıları:

```
report-service/src/main/java/com/example/report/contract/
  ├── ContractValidator.java                  // Orchestrator (no @Component / no @SpringBootApplication scan)
  ├── rules/
  │   ├── RC001YearlyRequiresYearColumn.java
  │   ├── RC002YearlySourceQueryRequiresPlaceholder.java
  │   ├── RC003HardcodedSchemaForbidden.java
  │   ├── RC004RowFilterColumnAllowlisted.java
  │   ├── RC005SchemaModePlusRowFilterForbidden.java
  │   ├── RC006NoneModeForbidsTenantFactTables.java
  │   ├── RC007ColumnFieldExistsInSourceQuery.java
  │   ├── RC008SchemaResolverRegistered.java
  │   ├── RC009ActionScopeValid.java
  │   ├── RC010DestructiveActionRequiresPermissionAndConfirm.java
  │   └── ContractRule.java                  // Interface
  ├── schema/
  │   ├── SchemaSnapshotLoader.java           // workcube-schema.json (committed primary)
  │   ├── ColumnTypeRegistry.java             // Schema-service-fed runtime side (Program 8 cross-cutting)
  │   └── TenantColumnAllowlist.java          // tenant-column-allowlist.json
  ├── exceptions/
  │   └── ExceptionsRegistry.java             // exceptions.json — id+ruleIds+reason+owner+expiresAt
  └── report/
      ├── ContractViolation.java              // record(ruleId, severity, reportKey, field, message)
      ├── ContractReport.java                 // summary + violations grouped by severity
      └── SummaryGenerator.java               // CLI tool — `target/report-contract-summary.md` artifact
```

> **Runtime gate sınırlaması**: `ContractValidator` ne `@Component` ne de `@Configuration` annotation'ı taşımaz; Spring Boot startup component scan'inde aktive edilmez. Production runtime'da loaded report definitions zaten build-time gate'inden geçtikleri varsayımıyla hareket eder. CI'da `mvn -pl report-service test` çalıştığında `ReportDefinitionContractTest` `@ParameterizedTest` registry sweep'ini yapar; production JVM'inde bu kod path inactive.
>
> **`SummaryGenerator` execution scope**: Marocchino sticky comment için CI step `./mvnw -pl report-service exec:java -Dexec.mainClass=com.example.report.contract.report.SummaryGenerator -Dexec.classpathScope=test` çağırır (`classpathScope=test` artifact production runtime'a sızmamasını sağlar). Alternatif olarak Surefire'ın kendi XML output'undan bir post-process step üretilebilir; spec implementation tarafına bırakır.

### 2.2 Schema additions (every report JSON)

```json
{
  "key": "fin-muhasebe-detay",
  "contractVersion": 1,
  "schemaMode": "yearly",
  "tenantBoundary": {
    "mode": "schema",
    "scopeType": "COMPANY",
    "schemaResolver": "workcube-year-company",
    "schemaPattern": "workcube_mikrolink_{year}_{companyId}",
    "reason": "fin-muhasebe-detay scope=COMPANY (per-tenant accounting fact table)"
  }
}
```

`schemaMode` enum (Codex iter-1 §2 absorb):

| Value | Pattern | Use |
|---|---|---|
| `yearly` | `workcube_mikrolink_{year}_{companyId}` | Yearly fact tables (ACCOUNT_CARD_ROWS) — **mevcut 19 rapor** |
| `current` | `workcube_mikrolink_{companyId}` | Per-tenant current state — **NEW** |
| `canonical` | `workcube_mikrolink` | Master/lookup tables (PROJECTS, COMPANIES) |
| `static` | exception only | Legacy hardcoded — RC-003 Tier 0 FAIL |

> **Migration: mevcut `schemaMode=standard` raporlar** (Codex iter-1 §2 absorb): Registry sweep'inde 1 rapor `schemaMode=standard` kullanıyor (`fin-butce-gerceklesen.json:9`). Plan v2.1 §3.1 enum'u `standard` içermiyor → **Phase-2-Program-1c sub-PR'ında migration**: bu rapor için doğru semantic değer atanır (data-shape'ine göre `canonical` master/lookup veya `current` per-tenant). Validator dry-run sırasında `schemaMode=standard` görürse `RC-000 ENUM_VIOLATION` üretir → exceptions.json'a geçici exception eklenmez (migration zorunlu); Phase-2-Program-1c PR'ında `standard` → uygun değer rename'i tek commit'te yapılır.

`tenantBoundary.mode`:
| Value | Semantics | RC ihlali |
|---|---|---|
| `schema` | Schema-level isolation (multi-tenant, year-resolver fed) | RC-005 reddi |
| `row` | Row-level isolation (`rowFilter.scopeType=COMPANY`) | RC-004 zorunlu |
| `none` | No boundary (master/lookup) | RC-006 reddi |

### 2.3 11 Validator Rules (RC-001..011)

Plan v2.1 §3.1'in matrix'ini implementation-detail seviyesinde detaylandırır.

| ID | Rule | Severity | Implementation |
|---|---|---|---|
| **RC-000** | `schemaMode` enum validity (yearly/current/canonical/static — registry'deki `standard` value FAIL) | FAIL | `if (!ENUM_SET.contains(def.schemaMode))` → `ENUM_VIOLATION` (Codex iter-2 §1 absorb: ayrı rule, RC-001 ile çakışma yok) |
| **RC-001** | `schemaMode=yearly` requires non-empty `yearColumn` | FAIL | `if (def.schemaMode == "yearly" && isBlank(def.yearColumn))` |
| **RC-002** | `schemaMode=yearly` + custom `sourceQuery` requires `[{schema}]` placeholder | FAIL | `if (yearly && hasSourceQuery && !sourceQuery.contains("{schema}"))` |
| **RC-003** | Hardcoded `workcube_mikrolink_YYYY_ID` + `static` schemaMode | FAIL Tier 0 (current year) / WARN Tier 1+ | Regex `workcube_mikrolink_\d{4}_\d+` scan in sourceQuery + sourceSchema field |
| **RC-004** | `rowFilter.scopeType=COMPANY` column must be in `tenant-column-allowlist.json` per-table; column existence verified via schema-service snapshot | FAIL | TenantColumnAllowlist lookup + ColumnTypeRegistry existence check |
| **RC-005** | `tenantBoundary.mode=schema` + non-empty `rowFilter` is forbidden (boundary clarity — schema-level isolation already covers tenant scope) | FAIL | `if (mode == "schema" && rowFilter != null)` |
| **RC-006** | `tenantBoundary.mode=none` reports cannot reference tenant fact tables | FAIL | sourceQuery scan: tenant fact table'ları (`ACCOUNT_CARD_ROWS`, `INVOICES`, `PURCHASE_ORDERS`, ...) içeriyorsa fail |
| **RC-007** | `columns[].field` projections must exist in `sourceQuery` SELECT (heuristic, best-effort) | WARN | Regex parse — bounded heuristic (Codex iter-1 §4 absorb): `AS [alias]` + `AS alias` + raw column token (`SELECT [TBL].[COL]`, `SELECT col`); `SELECT *` veya CTE/nested subquery parse edilemiyorsa `WARN(rc007_unparsed_query)` üretir CI fail etmez. False positive sources documented: alias expressions, generated pivot fields, dynamic SQL. **Severity hep WARN** — FAIL'e yükseltme YASAK. |
| **RC-008** | `tenantBoundary.schemaResolver` value must be in registered list (`workcube-year-company`, `workcube-current-company`, `none`) | FAIL | enum check |
| **RC-009** | `actions[].scope` must be `grid \| row \| selection` | FAIL | enum check |
| **RC-010** | Destructive actions (`destructive: true`) require non-null `permission` AND non-null `confirmation` | FAIL | `if (a.destructive && (isBlank(a.permission) \|\| isBlank(a.confirmation)))` |
| **RC-011** | `actions[].handler` must be async (TypeScript level — `mfe-reporting`) | FAIL (frontend ESLint) | TS type `(...args) => Promise<R>` enforcement; ESLint custom rule |

### 2.4 `tenant-column-allowlist.json` örneği

```json
{
  "ACCOUNT_CARD_ROWS": ["ACC_COMPANY_ID"],
  "INVOICES": ["COMPANY_ID", "BILLING_COMPANY_ID"],
  "PURCHASE_ORDERS": ["BUYER_COMPANY_ID"],
  "CONTRACTS": ["PARTY_COMPANY_ID"]
}
```

Uniform interface: `{tableName: string -> tenantColumnIds: string[]}`. RC-004 bu listeyi referans alır.

### 2.5 `exceptions.json` örneği

```json
[
  {
    "id": "EXCEPTION-001",
    "ruleIds": ["RC-003"],
    "reportKey": "legacy-stok-rapor",
    "reason": "Legacy hardcoded `workcube_mikrolink_2026_35` — Faz 19.MSSQL.B sırasında migrate edilecek",
    "owner": "halilkocoglu@example.com",
    "expiresAt": "2026-08-01T00:00:00Z"
  }
]
```

`ExceptionsRegistry` 3 enforcement rule (Codex iter-1 §3 absorb — 90-gün max bypass YASAK):

| Rule | Trigger | Action |
|---|---|---|
| `expiresAt` zorunlu | field absent | Validator FAIL: `EXCEPTION_MISSING_EXPIRY` |
| `expiresAt <= now` (past) | exception expired | Validator IGNORES exception → underlying violation surfaces (existing test: `ExceptionsRegistry_rejectsExpired`) |
| **`expiresAt > now + 90d`** | **`Clock` injectable, max 90-gün horizon** | **Validator FAIL: `EXCEPTION_BEYOND_90D_HORIZON`** (yeni — Codex §3 absorb) |

`Clock` injection PR-time test'lerin deterministic kalmasını sağlar (yeni: `ExceptionsRegistry_rejectsExpiresAtBeyond90Days`).

---

## 3. CI Integration

### 3.1 Maven Surefire integration

`ReportDefinitionContractTest`:

```java
@ParameterizedTest
@MethodSource("loadAllRegistryDefinitions")
void contractValidates(Path reportPath) {
    ReportDefinition def = parseRegistry(reportPath);
    List<ContractViolation> violations = ContractValidator.validate(def);
    List<ContractViolation> failOnly = violations.stream()
        .filter(v -> v.severity() == FAIL)
        .filter(v -> !ExceptionsRegistry.isExempted(def.key(), v.ruleId()))
        .toList();
    assertThat(failOnly)
        .as("Report %s contract violations: %s", def.key(), failOnly)
        .isEmpty();
}

static Stream<Path> loadAllRegistryDefinitions() {
    return Files.walk(Path.of("src/main/resources/reports"))
        .filter(p -> p.toString().endsWith(".json"));
}
```

### 3.2 PR sticky comment summary

CI step (post-test):

```yaml
- name: Generate report-contract-summary.md
  if: always()
  run: ./mvnw -pl report-service exec:java -Dexec.mainClass=com.example.report.contract.report.SummaryGenerator -Dexec.classpathScope=test
- name: Sticky PR comment (Marocchino)
  uses: marocchino/sticky-pull-request-comment@v2
  with:
    path: report-service/target/report-contract-summary.md
    header: report-contract-gate
```

`target/report-contract-summary.md` örneği:

```markdown
## Report Contract Gate — PR #N
| Report | Status | Violations | Exceptions |
|---|---|---|---|
| fin-muhasebe-detay | ✅ PASS | 0 | 0 |
| stok-hareket-rapor | ⚠️ WARN | 1 (RC-007) | 0 |
| legacy-stok-rapor | ✅ PASS | 0 | 1 (EXCEPTION-001 RC-003 expires 2026-08-01) |

11 rules, 8 reports, 0 FAIL, 1 WARN, 1 active exception.
```

---

## 4. Failure semantics

| Status | Code | When |
|---|---|---|
| `400` (CI test fail) | `report_contract_violation` | RC-001..011 FAIL severity, no matching exception |
| `400` (CI warn) | `report_contract_warning` | RC-007 WARN, surfaced in summary but doesn't fail CI |
| `400` (exception expired) | `report_contract_exception_expired` | `exceptions.json` entry `expiresAt` < now |
| Schema snapshot age | `report_contract_snapshot_age_warn` summary counter | Snapshot file mtime > 30 days → WARN row in summary; CI doesn't fail (committed primary, refresh runbook) |

---

## 5. Test plan

### 5.1 Unit (ContractValidator + per-rule)

| Test | Senaryo |
|---|---|
| `RC001YearlyRequiresYearColumn_failsWhenBlank` | `schemaMode=yearly` + `yearColumn=null` → 1 FAIL |
| `RC002YearlySourceQueryRequiresPlaceholder_failsWhenMissing` | yearly + sourceQuery without `[{schema}]` → 1 FAIL |
| `RC003HardcodedSchemaForbidden_tier0Fails_tier1Warns` | sourceQuery `workcube_mikrolink_2026_35` + `static` mode → tier=0 FAIL, tier=1 WARN |
| `RC004RowFilterColumnAllowlisted_failsWhenColumnAbsent` | `rowFilter.scopeType=COMPANY` + column not in allowlist → FAIL |
| `RC005SchemaModePlusRowFilterForbidden_fails` | `mode=schema` + `rowFilter` non-null → FAIL |
| `RC006NoneModeForbidsTenantFactTables_failsForReferencedFact` | `mode=none` + sourceQuery contains `ACCOUNT_CARD_ROWS` → FAIL |
| `RC007ColumnFieldExistsInSourceQuery_warnsWhenAbsent` | `columns[].field=GHOST_COL` not in SELECT → WARN |
| `RC008SchemaResolverRegistered_failsForUnknown` | `schemaResolver=foobar` → FAIL |
| `RC009ActionScopeValid_failsForUnknown` | `actions[0].scope=invalid` → FAIL |
| `RC010DestructiveActionRequiresPermAndConfirm_fails` | `destructive=true` + `permission=null` → FAIL |
| `ExceptionsRegistry_filtersByReportKeyAndRuleIds` | EXCEPTION-001 covers (legacy-stok-rapor, RC-003) → suppressed |
| `ExceptionsRegistry_rejectsExpired` | `expiresAt=2024-01-01` → exception ignored, original FAIL surfaces |
| `ExceptionsRegistry_rejectsExpiresAtBeyond90Days` | `Clock` fixed to 2026-05-07 + `expiresAt=2028-01-01` → `EXCEPTION_BEYOND_90D_HORIZON` FAIL (Codex iter-1 §3 absorb) |
| `ExceptionsRegistry_rejectsMissingExpiresAt` | exception entry without `expiresAt` field → `EXCEPTION_MISSING_EXPIRY` FAIL |
| `RC000SchemaModeEnumValid_existingStandardSchemaMode_reportsEnumViolation` | `fin-butce-gerceklesen.json` mevcut `schemaMode=standard` → `RC-000 ENUM_VIOLATION` (mig path: Phase-2-Program-1c) |

### 5.2 Integration (existing report registry sweep)

| Test | Senaryo |
|---|---|
| `ReportDefinitionContractTest_allRegistryReports_pass` | `mvn -pl report-service test` → tüm 8+ rapor için 0 FAIL (mevcut state'e göre); 1+ WARN OK |

### 5.3 PR feedback test (sticky comment)

| Test | Senaryo |
|---|---|
| Manuel PR test: 1 FAIL üreten test rapor değişikliği | CI red + sticky comment'te FAIL satırı + Marocchino update mekanizması |

---

## 6. Out of scope (gelecek PR / Phase 2 başka programlar)

- **Runtime tenant guard** — Phase 2 Program 2 (ayrı spec)
- **Action menu standard** — Phase 2 Program 3 (ayrı spec)
- **Schema-service fresh snapshot pull at CI time** — Plan §3.8'de "optional CI step" olarak tanımlı; ilk implementation'da committed snapshot yeterli
- **TypeScript `RC-011` async handler ESLint rule** — frontend repo'da (mfe-reporting) ayrı PR
- **Multi-table composition governance** — Phase 2 Program 7

---

## 7. Rollback semantics

Bu validator merge edilirse + 1 mevcut rapor RC-001..011'den birinde FAIL üretirse:
1. `exceptions.json`'a o rapor için time-bounded exception eklenir (`expiresAt` 90 gün max)
2. Audit comment ekler: "EXCEPTION-X added; rapor migration takvimi: ..."
3. Plan güncellenir; rapor Tier 0 olduğu sürece exception aktif

Validator implementation'ının kendisi rollback edilirse: yeni rapor merge'leri eskisi gibi gate'siz olur (regression). Bu PR'ın merge'i bu yüzden tek başına yeterli **değil**; önce mevcut rapor inventory FAIL produce etmiyor olmalı.

---

## 8. Risk / Trade-off Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Mevcut 8+ rapor RC-001..011'de unexpected FAIL | M | CI red, dev productivity hit | Pre-merge dry-run (`mvn -pl report-service test -Dreport.contract.dryRun`) sonuçları PR description'a; tüm FAIL için ya fix ya `exceptions.json` ekle |
| Schema-service unreachable in CI | L | RC-004 false-pass minimal (committed snapshot primary) | Q4 absorbed: committed snapshot primary; service optional fresh refresh; unreachable silent (committed snapshot zaten kullanılır); snapshot mtime > 30 gün → `report_contract_snapshot_age_warn` summary counter, CI doesn't fail |
| Validator çok yavaş (8+ rapor × 11 rule + schema lookup) | L | CI lane >2 dakika | Caffeine cache `schemaSnapshot` + parallel `@ParameterizedTest` @Execution(CONCURRENT) |
| `exceptions.json` ÇOĞALIR (geçici exception kalıcılaşır) | M | Validator değer kaybeder | `expiresAt` zorunlu (Q3 default) + CI WARN at 30-day-pre-expire |

---

## 9. Definition of Done

- [ ] `report-definition.schema.json` (Draft 2020-12) yazılı + 19+ mevcut rapor schema'ya uygun (`fin-butce-gerceklesen` `standard` → uygun değer migration commit'i Phase-2-Program-1c'de)
- [ ] `tenant-column-allowlist.json` mevcut tenant fact tabloları için doldurulmuş
- [ ] `exceptions.json` (boş başlangıç, sadece EXCEPTION-001 örneği)
- [ ] `ContractValidator` + 11 backend RC rule (RC-000..RC-010) (RC-011 frontend separate)
- [ ] **Build-time vs runtime gate ayrımı net**: `ContractValidator` no `@Component`/`@Configuration` (Codex iter-1 §1 absorb); test'te `@ParameterizedTest` registry sweep
- [ ] `SchemaSnapshotLoader` — committed snapshot primary; schema-service optional fresh refresh CI step; snapshot age >30 gün WARN summary counter (Codex iter-1 §5 absorb)
- [ ] `ExceptionsRegistry` 3 enforcement rule: `expiresAt` zorunlu + past → ignored + **>90-gün horizon FAIL** (Codex iter-1 §3 absorb, injectable `Clock`)
- [ ] `ReportDefinitionContractTest` `@ParameterizedTest` over registry sweep
- [ ] `target/report-contract-summary.md` artifact + Marocchino sticky comment CI step (`exec.classpathScope=test`)
- [ ] ADR `docs/adr/0006-report-definition-contract.md` (Plan v2.1 §3.1 mandate)
- [ ] **15 unit test PASS** (12 + Codex iter-1 §3 + §2 yeni tests) + 1 IT (registry sweep) PASS
- [ ] Mevcut 19+ rapor sweep'te 0 FAIL (gerekirse exceptions.json mounting; `fin-butce-gerceklesen` migration Phase-2-Program-1c'de)
- [ ] Codex post-impl peer review AGREE (HARD RULE Cross-AI)
- [ ] CI 9/9 green + admin merge YASAK

---

## 10. Owner Karar Soruları (özet)

Bu spec'i okumak için 5 dakika ayırıp şu 6 soruya cevap verirseniz implementation başlayabilir:

1. **Validator scope**: build-time only mu, runtime de mi?
2. **RC-003 Tier 0 davranış**: FAIL (önerilen) mı, WARN mı?
3. **`exceptions.json` `expiresAt` zorunluluğu**: zorunlu (önerilen) mu, opsiyonel mi?
4. **Schema-service unreachable**: Committed snapshot primary; schema-service optional refresh; unreachable silent; snapshot age >30d WARN summary counter (önerilen) mi, strict FAIL mi?
5. **`RC-011` async handler enforcement**: TS types + ESLint (önerilen) mi, sadece runtime mi?
6. **PR feedback artifact**: `report-contract-summary.md` sticky comment (önerilen) mi, sadece CI log mu?

Default önerileri seçerseniz "AGREE → impl başla" cevabı yeterlidir.

---

## 11. Sub-PR breakdown

1. **Phase-2-Program-1a**: ContractValidator + 11 backend RC rule (RC-000..RC-010) + ContractRule interface + ContractViolation/ContractReport records
2. **Phase-2-Program-1b**: `tenant-column-allowlist.json` + `exceptions.json` + `ExceptionsRegistry` + auto-expire
3. **Phase-2-Program-1c**: `report-definition.schema.json` (Draft 2020-12) + 19+ mevcut rapor `contractVersion=1` + `tenantBoundary` field + `schemaMode=standard` → `canonical`/`current` migration commit
4. **Phase-2-Program-1d**: `SchemaSnapshotLoader` (committed primary, optional schema-service fresh refresh CI step, snapshot age WARN) + `ColumnTypeRegistry`
5. **Phase-2-Program-1e**: `ReportDefinitionContractTest` + Marocchino sticky comment CI integration + ADR-0006

5 sub-PR, her biri bağımsız Codex iter cycle, normal merge (admin merge YASAK), sırasıyla.

---

## 12. Plan v2.1 referans çapraz-link

- §3.1 `Program 1 — Report Contract Gate (build-time)` — bu spec
- §3.6 `Program 6 6b PR-0` — PR-0.4 spec (PR #90) ve sub-PR'ları (PR-0.4a/b/c/d/e) bu validator gate'inden geçer
- §3.8 `Program 8 — Schema Truth Integration` — Plan §3.8 fallback chain Program 1 + Program 2 + runtime FilterTranslator için ortak

---

## 13. Next Step

1. Owner UX feedback (Q1-Q6).
2. Codex iter-N cross-AI peer review (spec sonrası, impl-time karar açılışı).
3. Sub-PR breakdown'a göre Phase-2-Program-1a impl başlar.
4. Per-PR Codex AGREE → CI green → normal merge.
