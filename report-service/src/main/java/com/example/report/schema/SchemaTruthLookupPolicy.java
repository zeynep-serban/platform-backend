package com.example.report.schema;

/**
 * Phase 2 Program 8 — Schema Truth Integration lookup policy.
 *
 * <p>Aynı {@link SchemaTruthService} çağrısı policy enum'a göre farklı
 * tier davranışı üretir → Phase 2 Program 1 build-time deterministic +
 * Program 2 runtime strict + degraded type fast path orthogonal yapılır.
 *
 * <p>Spec: {@code docs/plans/2026-05-reporting-phase-2-program-8-schema-truth-integration-spec.md}
 * §2.1.1 (Codex iter-1 §1 absorb).
 */
public enum SchemaTruthLookupPolicy {

    /**
     * Build-time deterministic mode (CI {@code mvn test}).
     *
     * <p>Tier order: 2 (committed snapshot) → 3 (registry types).
     * Tier 1 (schema-service) <strong>DISABLED</strong> — network-independent CI.
     *
     * <p>Used by: Phase 2 Program 1 {@code ContractValidator} (PR #91).
     */
    BUILD_DETERMINISTIC,

    /**
     * Runtime strict existence check (production).
     *
     * <p>Tier order: 1 (schema-service Caffeine) <strong>ONLY</strong>.
     * Tier 1 miss / unreachable → 503 {@code schema_resolver_miss} (NO fallback).
     *
     * <p>Used by: Phase 2 Program 2 {@code TenantBoundaryGuard.exists(schema)} (PR #92).
     */
    RUNTIME_STRICT_EXISTENCE,

    /**
     * Runtime degraded type lookup (production fast path).
     *
     * <p>Tier order: 1 (Caffeine) → 2 (committed snapshot) → 3 (registry types).
     * All 3 tiers allowed; Tier 3 = WARN + {@code X-Schema-Truth-Tier} header.
     *
     * <p>Used by: {@code FilterTranslator}, {@code SqlBuilder}
     * (PR-0.4 pivot value discovery + weighted AVG), and frontend
     * {@code useReportSchemaContext}.
     */
    RUNTIME_DEGRADED_TYPE
}
