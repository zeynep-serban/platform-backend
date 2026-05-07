package com.example.report.schema;

import com.example.report.schema.tier.SchemaServiceClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 8 — Schema Truth Service facade.
 *
 * <p>Policy-driven 3-tier fallback orchestrator. Bu sub-PR (8a) sadece
 * Tier 1 ({@link SchemaServiceClient}) entegrasyonunu sağlar; Tier 2
 * ({@code CommittedSnapshotLoader}) ve Tier 3 ({@code RegistryTypeFallback})
 * Phase-2-Program-8b'de eklenir.
 *
 * <p>Spec: §2.1 (facade), §2.3 (policy → tier behavior matrix).
 *
 * <p><strong>Şu anki kapsam (8a)</strong>:
 * <ul>
 *   <li>{@link SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE}: Tier 1 only;
 *       cache miss + service unreachable → exception propagates (caller fail-closed 503 üretir)</li>
 *   <li>{@link SchemaTruthLookupPolicy#RUNTIME_DEGRADED_TYPE}: Tier 1; Tier 2/3
 *       fallback HENÜZ EKLENMEDI → 8b'de aktive olur</li>
 *   <li>{@link SchemaTruthLookupPolicy#BUILD_DETERMINISTIC}: Tier 2 primary;
 *       8b'de wire'lı (şu an UnsupportedOperationException)</li>
 * </ul>
 *
 * @see SchemaTruthLookupPolicy
 * @see SchemaTruthLookupContext
 */
@Service
public class SchemaTruthService {

    private static final Logger log = LoggerFactory.getLogger(SchemaTruthService.class);

    private final SchemaServiceClient schemaServiceClient;

    public SchemaTruthService(SchemaServiceClient schemaServiceClient) {
        this.schemaServiceClient = schemaServiceClient;
    }

    /**
     * Schema snapshot fetch — policy-driven tier orchestration.
     *
     * @param ctx          lookup context (policy + reportKey + schemaMode + consumer)
     * @param schemaName   workcube schema adı
     * @return {@link Optional#empty()} if 404 (column gerçekten yok); {@link Optional#of}
     *         on success
     * @throws UnsupportedOperationException 8a scope dışı policy çağrısında
     *         ({@code BUILD_DETERMINISTIC} Tier 2 Phase-2-Program-8b'de eklenir)
     * @throws RuntimeException Tier 1 fail-soft (network/timeout/5xx) — caller
     *         {@link SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE} altında
     *         503 schema_resolver_miss üretir; {@code RUNTIME_DEGRADED_TYPE}
     *         altında 8b Tier 2 fallback'e düşer.
     */
    public Optional<SchemaSnapshot> fetchSnapshot(SchemaTruthLookupContext ctx, String schemaName) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }

        switch (ctx.policy()) {
            case BUILD_DETERMINISTIC:
                // 8b'de Tier 2 (CommittedSnapshotLoader) eklenir.
                throw new UnsupportedOperationException(
                        "BUILD_DETERMINISTIC policy Tier 2 fallback Phase-2-Program-8b'de aktive olur");
            case RUNTIME_STRICT_EXISTENCE:
            case RUNTIME_DEGRADED_TYPE:
                // 8a: Tier 1 only. RUNTIME_DEGRADED_TYPE Tier 2/3 fallback 8b'de eklenir.
                log.debug("schema-truth Tier 1 lookup: schema={} policy={} consumer={}",
                        schemaName, ctx.policy(), ctx.consumer());
                return schemaServiceClient.fetchSnapshot(ctx, schemaName);
            default:
                throw new IllegalStateException("Unknown policy: " + ctx.policy());
        }
    }
}
