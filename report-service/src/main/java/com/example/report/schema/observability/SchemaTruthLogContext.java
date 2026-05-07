package com.example.report.schema.observability;

import com.example.report.schema.SchemaTruthLookupContext;
import org.slf4j.MDC;

/**
 * Phase 2 Program 8d — Schema Truth MDC enrichment.
 *
 * <p>Spec §2.1: log/MDC tarafında {tier, schema_mode, report_key, age_days, consumer}.
 * Metric tag cardinality kontrol için report_key/tenant_id metric label OLAMAZ;
 * log MDC bunları her satırda surface eder (granular debug).
 *
 * <p>Try-with-resources pattern:
 * <pre>{@code
 * try (var ctx = SchemaTruthLogContext.enter(lookupCtx, "tier_1_cache_hit")) {
 *     // operation; logs include MDC tags
 * }
 * }</pre>
 */
public final class SchemaTruthLogContext implements AutoCloseable {

    private static final String KEY_TIER = "schema_truth.tier";
    private static final String KEY_SCHEMA_MODE = "schema_truth.schema_mode";
    private static final String KEY_REPORT_KEY = "schema_truth.report_key";
    private static final String KEY_CONSUMER = "schema_truth.consumer";

    private SchemaTruthLogContext() {
        // factory only
    }

    public static SchemaTruthLogContext enter(SchemaTruthLookupContext ctx, String tier) {
        if (ctx != null) {
            putIfPresent(KEY_SCHEMA_MODE, ctx.schemaMode());
            putIfPresent(KEY_REPORT_KEY, ctx.reportKey());
            putIfPresent(KEY_CONSUMER, ctx.consumer());
        }
        if (tier != null && !tier.isBlank()) {
            MDC.put(KEY_TIER, tier);
        }
        return new SchemaTruthLogContext();
    }

    @Override
    public void close() {
        MDC.remove(KEY_TIER);
        MDC.remove(KEY_SCHEMA_MODE);
        MDC.remove(KEY_REPORT_KEY);
        MDC.remove(KEY_CONSUMER);
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}
