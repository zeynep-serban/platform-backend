# ADR-0008 — Schema Truth Integration (Phase 2 Program 8)

> **Status**: Accepted (2026-05-07)
> **Plan**: v2.1 §3.8
> **Implementation**: PR #95 (8a) + #96 (8b) + #98 (8c) + #100 (8d) + this PR (8e)
> **Cross-AI peer review**: Codex thread `019e0119-7c9d-7541-8059-f9553c3303ce`

## Context

Reporting platform consumer'ları (FilterTranslator, SqlBuilder grouping/
weighted-AVG, Phase 2 Program 1 build-time validator, Phase 2 Program 2
runtime tenant guard, frontend `useReportSchemaContext`) tüm schema/column
metadata'yı tek authoritative kaynaktan tüketmeli. Önceki state: her
consumer kendi snapshot lookup yolunu yazıyordu (workcube-schema.json
manuel parse, schema-service ad-hoc çağrıları, registry types fallback'i
inconsistent).

## Decision

**`SchemaTruthService` facade** + **3-tier policy-driven fallback chain**:

| Tier | Source | Use case |
|---|---|---|
| 1 | schema-service `/api/v1/schema/snapshot` (5-min Caffeine) | Runtime authoritative |
| 2 | Committed snapshot (`docs/migration/workcube-schema.json`) | Build-time deterministic + runtime fallback |
| 3 | Report registry `<key>.json columns[].type` | Last-resort report-scoped fallback |

**Policy enum** (`SchemaTruthLookupPolicy`):
- `BUILD_DETERMINISTIC`: Tier 2 PRIMARY (CI deterministic, no network)
- `RUNTIME_STRICT_EXISTENCE`: Tier 1 ONLY (Phase 2 Program 2 fail-closed 503)
- `RUNTIME_DEGRADED_TYPE`: Tier 1 → 2 → 3 fallback chain (FilterTranslator + SqlBuilder + frontend hook)

**Capability matrix** (§2.1.2):
| API | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|
| `lookupColumnType(reportKey, field)` (report-scoped) | ✓ | ✓ | ✓ |
| `lookupColumnType(schema, table, column)` (DB-level) | ✓ | ✓ | ✓ (last-resort) |
| `exists(schemaName)` | ✓ | ✗ | ✗ |
| `listColumns(schema, table)` | ✓ | ✓ | partial (report-scoped subset + WARN) |

## Consequences

### Positive

- **Single source of truth**: 4 consumer'lar (Programs 1, 2, 8, PR-0.4) tek facade'a bağlı; schema/column metadata drift'i önlenir
- **Runtime resilience**: Tier 1 down olduğunda Tier 2 committed snapshot ile FilterTranslator/SqlBuilder degraded path'te çalışmaya devam eder; tenant guard fail-closed 503 strict semantics korunur
- **Build-time deterministic**: CI'da network bağımsız (Tier 2 primary) — `mvn test` reproducible
- **Observability**: 6 metric (lookup_total, fallback_total, cache_hit_total, snapshot_age_days, snapshot_age_warn, cache_miss_burst) + MDC enrichment
- **Frontend transparency**: `X-Schema-Truth-Tier` response header + `useReportSchemaContext` hook tier signal → degraded mode'da console.warn (Tier 3)

### Negative / Trade-offs

- **Cross-service auth**: schema-service `/snapshot` endpoint'ine internal API key + JWT dual auth eklendi; production ESO/Vault key wiring gerek
- **Snapshot freshness**: committed snapshot mtime > 30 gün → WARN; no hot-reload
- **Tier 3 partial semantics**: `listColumns` Tier 3 fallback'inde sadece raporun expose ettiği kolonlar; caller bunu transparent degraded sinyal olarak okur

## Implementation breakdown

| Sub-PR | Scope | Spec section |
|---|---|---|
| 8a | Policy enum + context record + Tier 1 client + 5-min Caffeine | §2.1.1, §2.2 Tier 1 |
| 8b | Tier 2 + Tier 3 + facade orchestration | §2.2 Tier 2/3, §2.3 |
| 8c | Consumer interfaces + @RequestScope cache | §2.1, §2.1.2 capability |
| 8d | 6 metrics + MDC + facade instrumentation | §2.4 + §9 DoD |
| 8e | `GET /schema-context` endpoint + `X-Schema-Truth-Tier` header + this ADR | §2.5 |

## Cross-AI peer review

Spec PR #93 (Plan v2.1 §3.8 detail) Codex iter-4 AGREE. Implementation
sub-PR'larında her biri ayrı Codex iter cycle:
- 8a: iter-3 AGREE (3 BLOCKING absorbed)
- 8b: iter-1 direct AGREE
- 8c: iter-2 AGREE (4 finding absorbed)
- 8d: iter-3 AGREE (1 BLOCKING + 1 REVISE absorbed)
- 8e: this PR — pending review

## Frontend hook (separate, platform-web)

`useReportSchemaContext(reportKey)` — implementation in platform-web repo
(separate PR; out of scope for this backend ADR).

```ts
const { columnTypes, tier } = useReportSchemaContext("fin-muhasebe-detay");
if (tier === "registry_type") {
    console.warn(`[schema-truth] degraded fallback for ${reportKey}`);
}
```

## Snapshot file refresh runbook

```bash
# 1. Pull fresh schema from production schema-service (in-cluster)
kubectl exec -n platform-prod deploy/schema-service -- \
    curl -H "X-Internal-Api-Key: $KEY" /api/v1/schema/snapshot \
    > docs/migration/workcube-schema.json

# 2. Validate snapshot (build-time validator, RC-001..010)
mvn -pl report-service test -Pcontract-validator

# 3. Commit + GitOps deploy via ConfigMap mount
git add docs/migration/workcube-schema.json
git commit -m "chore(snapshot): refresh workcube schema"
```
