package com.example.report.schema;

/**
 * Phase 2 Program 8 — Schema Truth lookup request context.
 *
 * <p>Consumer API'larına context parameter olarak inject edilir → metric
 * label'ları ({@code schema_truth_fallback_total{tier,schema_mode}},
 * {@code schema_truth_lookup_total{schema_mode}}) doldurulabilir.
 * MDC enrichment için de aynı context kullanılır
 * ({@code SchemaTruthLogContext}).
 *
 * <p>Spec: §2.1 (Codex iter-1 §3 absorb).
 *
 * @param reportKey   Report registry key (e.g. {@code "fin-muhasebe-detay"})
 * @param schemaMode  {@code yearly | current | canonical | static}
 *                    (Phase 2 Program 1 RC-000 enum değeri)
 * @param policy      Tier davranışını belirleyen policy
 *                    ({@link SchemaTruthLookupPolicy})
 * @param consumer    Consumer identifier — log/MDC için (örn.
 *                    {@code "contract_validator"}, {@code "tenant_boundary_guard"},
 *                    {@code "sql_builder_discovery"}, {@code "sql_builder_weighted_avg"},
 *                    {@code "filter_translator"}). Metric label DEĞİL —
 *                    cardinality patlamasını önle (Codex iter-1 §3 absorb).
 */
public record SchemaTruthLookupContext(
        String reportKey,
        String schemaMode,
        SchemaTruthLookupPolicy policy,
        String consumer
) {

    public SchemaTruthLookupContext {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("consumer must not be blank");
        }
        // schemaMode + reportKey null/blank olabilir (build-time test fixture'larda eksik olabilir);
        // metric label tarafında "unknown" olarak basılır.
    }
}
