package com.example.report.schema;

import java.util.Optional;

/**
 * Phase 2 Program 8e — Schema Truth fetch result with tier provenance.
 *
 * <p>Spec §2.5: response {@code X-Schema-Truth-Tier} header canonical
 * decision surface. Facade'ın hangi tier'dan döndüğünü dış API'ya
 * expose eder.
 *
 * @param snapshot result snapshot (or empty if all tiers missed)
 * @param tier     "schema_service" | "committed_snapshot" | "registry_type" | "miss"
 */
public record SchemaTruthResult(
        Optional<SchemaSnapshot> snapshot,
        String tier
) {

    public static final String TIER_SCHEMA_SERVICE = "schema_service";
    public static final String TIER_COMMITTED_SNAPSHOT = "committed_snapshot";
    public static final String TIER_REGISTRY_TYPE = "registry_type";
    public static final String TIER_MISS = "miss";

    public SchemaTruthResult {
        if (snapshot == null) {
            snapshot = Optional.empty();
        }
        if (tier == null || tier.isBlank()) {
            tier = TIER_MISS;
        }
    }
}
