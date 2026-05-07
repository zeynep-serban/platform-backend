# ADR-0006: Report Contract Gate (Phase 2 Program 1)

**Status**: Accepted (2026-05-07)
**Driver**: Halil Kocoğlu (gladyatore@hotmail.com)
**Reviewers**: Codex MCP thread `019e0119-7c9d-7541-8059-f9553c3303ce`
**PRs**: #102 (1a), #103 (1b), #105 (1c), #106 (1d), #108 (1e)

## Context

Workcube ERP raporları `report-service` kayıt defterine JSON dosyaları olarak (`reports/*.json`) commit edilir. Faz 18 öncesinde her rapor sadece runtime'da `ReportRegistry.loadDefinitions()` ile yüklenirdi; şema doğrulaması, runtime exception bypass, tenant-boundary tutarsızlığı veya kayıt defteri çapında governance kontrolleri yoktu. Faz 18'de **build-time** disiplini eklendi:

- Yıllık-bölünmüş schema'larda yanlış `yearColumn` kayması
- `rowFilter.scopeType=COMPANY` misclassified (DEPARTMENT_ID, BRANCH_ID, EMPLOYEE_ID gibi non-tenant kolonlar)
- `schemaMode=yearly` + `rowFilter` redundant boundary
- Şema-dışı (drift) JSON alanlarının runtime'da sessiz tüketimi
- Aynı `key` ile birden fazla rapor commit edilmesi
- `exceptions.json` kayıt defteri integrity ihlali (90d horizon, RC-XXX namespace)

## Decision

**Build-time Report Contract Gate** kuruldu. 11 RC kuralı (semantic) + sweep meta-rules (REPORT_*) + ExceptionsRegistry + tenant-column-allowlist + JSON Schema Draft 2020-12 doğrulayıcı bileşenlerinden oluşur.

### Mimari (5 sub-PR)

```
ReportContractGate (orchestrator)
├── RegistrySweep (raw JSON: REPORT_FILE_LOAD_ERROR / REPORT_SCHEMA_INVALID / REPORT_KEY_DUPLICATE)
│   └── ReportDefinitionSchemaValidator (JSON Schema Draft 2020-12, networknt)
├── ContractValidator (per-report semantic — 11 RC rules)
│   ├── RC-000 SchemaModeEnumValid
│   ├── RC-001 YearlyRequiresYearColumn
│   ├── RC-002 YearlySourceQueryRequiresPlaceholder
│   ├── RC-003 HardcodedSchemaForbidden
│   ├── RC-004 RowFilterColumnAllowlisted (uses TenantColumnAllowlist)
│   ├── RC-005 SchemaModePlusRowFilterForbidden
│   ├── RC-006 NoneModeForbidsTenantFactTables
│   ├── RC-007 ColumnFieldExistsInSourceQuery
│   ├── RC-008 SchemaResolverRegistered
│   ├── RC-009 ActionScopeValid
│   └── RC-010 DestructiveActionRequiresPermissionAndConfirm
├── ExceptionsRegistry (90d horizon, RC-XXX namespace, RULE_EXECUTION_ERROR non-suppressible)
└── BuildTimeSchemaTruthLookup (Phase 2 Program 8 Tier 2 adapter; existence check Phase 2 Program 2 follow-up)
```

### Kritik kurallar

1. **Build-time only** — Hiçbir gate bileşeni `@Component` / `@Configuration` ile annotated DEĞİL. Üretim runtime'ında tüm path inactive.
2. **Suppression namespace** — `ExceptionsRegistry.apply()` yalnızca `^RC-\d{3}$` matchli ruleId'leri suppress eder. Meta-violations (`REPORT_*`, `EXCEPTION_*`) ve `RULE_EXECUTION_ERROR` mesajlı RC-XXX ASLA suppress edilemez.
3. **90-day horizon** — Tüm exception entry'leri `expiresAt` ZORUNLU + `now + 90 days` üstü → `EXCEPTION_BEYOND_90D_HORIZON` FAIL meta. Geçmiş `expiresAt` → entry inert (underlying violation surfaces).
4. **Schema-validity ön-koşul** — `REPORT_SCHEMA_INVALID` raporlarında semantic RC kuralları **early-exit**.
5. **CI gate** — `ReportDefinitionContractTest` default Surefire test suite altında. PR/push tetikli `.github/workflows/contract-gate.yml` — Marocchino sticky PR comment + JSON/Markdown artifact upload + final exit-code propagation.

### Governance debt mekanizması

90 günlük expiry'li `exceptions.json` entries ile bilinen tech debt **görünür** kılınır. 1d'de 21 entry commit edildi (RC-001×2, RC-004×7, RC-005×12). Sticky PR comment her PR'da expiry countdown'u gösterir. Phase 2 Program 2'de bu debt'ler tek tek temizlenir veya semantic fix yapılır.

## Consequences

### Olumlu

- Yeni rapor PR'ları gate'den geçmek zorunda — sessiz drift YASAK
- Governance debt 90 gün içinde temizlenmeli (audit-trailed)
- JSON Schema 2020-12 ile vocabulary lock + Jackson ignore-unknown ile runtime backward-compat
- Phase 2 Program 8 schema-truth integration'a hazır altyapı (BuildTimeSchemaTruthLookup adapter)
- Cross-AI peer review (Codex ↔ Claude) ile her sub-PR'da çift onay disiplini

### Olumsuz

- Yeni rapor eklemek 31-key whitelist update gerektirir (`ReportDefinitionContractTest.knownReportKeys()`)
- 3.4MB canonical snapshot (`workcube-schema.json`) build-time governance artifact (kabul edildi: build-time only, runtime fallback fixture değişmedi)
- Ekspesyon entry'leri PR review yükü doğurur (90 günde temizleme baskısı)
- `classpath:` vs `classpath*:` test-classpath nüansı (RegistrySweep + ReportRegistry test path'leri açık documented)

### Riskler ve azaltımlar

| Risk | Azaltım |
|---|---|
| Snapshot drift (yearly partition) | Phase 2 Program 2 yearly snapshot crawler |
| Exception bypass abuse | RC-XXX namespace + `EXCEPTION_INVALID_RULE_ID` + sticky comment expiry countdown |
| Rule crash silently masked | RULE_EXECUTION_ERROR non-suppressible guard |
| New report missing from whitelist | knownReportKeys list + 31-count drift guard |

## Alternatives Considered

1. **Runtime gate** (rejected): production traffic'i etkiler; CI'da ölçülemez
2. **No exception mechanism** (rejected): legitimate transition debt için hard-fail unsustainable
3. **Spring Validator API** (rejected): JSON Schema 2020-12 vendor-neutral + linguistic precision daha iyi
4. **`pull_request_target` workflow** (rejected): write token risk surface büyütür

## References

- PR #102: `feat(report-service): Phase 2 Program 1a — ContractValidator + 11 RC rules`
- PR #103: `feat(report-service): Phase 2 Program 1b — ExceptionsRegistry + 90d horizon`
- PR #105: `feat(report-service): Phase 2 Program 1c — report-definition.schema.json + 31 report migration`
- PR #106: `feat(report-service): Phase 2 Program 1d — ReportContractGate + RC-004 allowlist + 90d governance debt`
- PR #108 (1e): `feat(report-service): Phase 2 Program 1e — Marocchino sticky comment + ADR-0006` (this ADR lands here)
- ADR-0008: Schema Truth Integration (Phase 2 Program 8)
- Codex thread: `019e0119-7c9d-7541-8059-f9553c3303ce` (plan + post-impl review for all 5 sub-PRs)
