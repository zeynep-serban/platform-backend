package com.example.report.schema;

import com.example.report.schema.observability.SchemaTruthLogContext;
import com.example.report.schema.observability.SchemaTruthMetrics;
import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.example.report.schema.tier.RegistryTypeFallback;
import com.example.report.schema.tier.SchemaServiceClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 8 — Schema Truth Service facade.
 *
 * <p>Policy-driven 3-tier fallback orchestrator. Phase-2-Program-8b'de
 * Tier 2 ({@link CommittedSnapshotLoader}) + Tier 3
 * ({@link RegistryTypeFallback}) eklendi.
 *
 * <p>Spec: §2.1 (facade), §2.3 (policy → tier behavior matrix),
 * §2.1.2 (capability matrix).
 *
 * <p><strong>Policy → tier matrix</strong>:
 * <ul>
 *   <li>{@link SchemaTruthLookupPolicy#BUILD_DETERMINISTIC}: Tier 2 PRIMARY
 *       (committed snapshot); Tier 1 DISABLED (CI deterministic);
 *       Tier 3 fallback report-scoped lookups için</li>
 *   <li>{@link SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE}: Tier 1
 *       ONLY; miss/unreachable → exception (caller fail-closed 503)</li>
 *   <li>{@link SchemaTruthLookupPolicy#RUNTIME_DEGRADED_TYPE}: Tier 1 → Tier 2
 *       → Tier 3 fallback chain; her tier transitionunda WARN/metric</li>
 * </ul>
 *
 * @see SchemaTruthLookupPolicy
 * @see SchemaTruthLookupContext
 */
@Service
public class SchemaTruthService {

    private static final Logger log = LoggerFactory.getLogger(SchemaTruthService.class);

    private final SchemaServiceClient schemaServiceClient;
    private final CommittedSnapshotLoader committedSnapshotLoader;
    // RegistryTypeFallback tier 3 lookup'ı 8c consumer interface'lerinde
    // wire'lı olur (column type registry-scoped); facade fetchSnapshot
    // semantik'i schema-level olduğu için Tier 3 burada uygulanmaz.
    private final RegistryTypeFallback registryTypeFallback;
    // 8d: Metrics + MDC observability (ObjectProvider null-safe — test/dev
    // context'inde MeterRegistry yokken no-op).
    private final ObjectProvider<SchemaTruthMetrics> metricsProvider;

    public SchemaTruthService(SchemaServiceClient schemaServiceClient,
                               CommittedSnapshotLoader committedSnapshotLoader,
                               RegistryTypeFallback registryTypeFallback,
                               ObjectProvider<SchemaTruthMetrics> metricsProvider) {
        this.schemaServiceClient = schemaServiceClient;
        this.committedSnapshotLoader = committedSnapshotLoader;
        this.registryTypeFallback = registryTypeFallback;
        this.metricsProvider = metricsProvider;
    }

    /**
     * Schema snapshot fetch — policy-driven tier orchestration.
     *
     * <p>Tier transitions:
     * <ul>
     *   <li>BUILD_DETERMINISTIC: Tier 2 → Tier 3 (Tier 1 disabled)</li>
     *   <li>RUNTIME_STRICT_EXISTENCE: Tier 1 only; fail-soft → exception
     *       propagates (caller 503)</li>
     *   <li>RUNTIME_DEGRADED_TYPE: Tier 1 → Tier 2 (caller fail-soft);
     *       Tier 3 schema-level değil — column-level lookup'ta kullanılır
     *       (8c ColumnTypeRegistry consumer)</li>
     * </ul>
     *
     * @param ctx          lookup context (policy + reportKey + schemaMode + consumer)
     * @param schemaName   workcube schema adı
     * @return {@link Optional#empty()} if 404 (column gerçekten yok) veya tüm tier'lar
     *         empty döndü; {@link Optional#of} on success
     * @throws RuntimeException Tier 1 fail-soft sonrası policy
     *         {@link SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE} ise propagate
     */
    public Optional<SchemaSnapshot> fetchSnapshot(SchemaTruthLookupContext ctx, String schemaName) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }

        SchemaTruthMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics != null) {
            metrics.recordLookup(ctx);
        }

        switch (ctx.policy()) {
            case BUILD_DETERMINISTIC:
                // Tier 2 primary; CI deterministic, no network dependency.
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_2")) {
                    log.debug("schema-truth BUILD_DETERMINISTIC Tier 2 lookup: schema={} consumer={}",
                            schemaName, ctx.consumer());
                    return committedSnapshotLoader.lookup(ctx, schemaName);
                }

            case RUNTIME_STRICT_EXISTENCE:
                // Tier 1 only; fail-soft → exception propagates (caller 503).
                // Codex iter-1 §1 absorb: cache_hit_total Tier 1 success'tan
                // değil Caffeine native stats'tan ölçülür (SchemaTruthMetrics).
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_1")) {
                    log.debug("schema-truth RUNTIME_STRICT_EXISTENCE Tier 1 lookup: schema={} consumer={}",
                            schemaName, ctx.consumer());
                    return schemaServiceClient.fetchSnapshot(ctx, schemaName);
                }

            case RUNTIME_DEGRADED_TYPE:
                // Tier 1 → Tier 2 fallback. Tier 3 sadece column-level lookup'ta
                // (8c ColumnTypeRegistry consumer; schema-level değil).
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_1")) {
                    log.debug("schema-truth RUNTIME_DEGRADED_TYPE Tier 1 lookup: schema={} consumer={}",
                            schemaName, ctx.consumer());
                    try {
                        Optional<SchemaSnapshot> tier1 = schemaServiceClient.fetchSnapshot(ctx, schemaName);
                        if (tier1.isPresent()) {
                            return tier1;
                        }
                    } catch (RuntimeException e) {
                        log.warn("Tier 1 fail-soft, falling to Tier 2: schema={} error={}",
                                schemaName, e.getMessage());
                    }
                }
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_2")) {
                    Optional<SchemaSnapshot> tier2 = committedSnapshotLoader.lookup(ctx, schemaName);
                    if (tier2.isPresent()) {
                        log.info("Tier 2 committed snapshot served: schema={} consumer={}",
                                schemaName, ctx.consumer());
                        if (metrics != null) {
                            metrics.recordFallback(ctx, "committed_snapshot");
                        }
                    }
                    return tier2;
                }

            default:
                throw new IllegalStateException("Unknown policy: " + ctx.policy());
        }
    }

    /**
     * Tier 3 column-level lookup — registry types fallback.
     *
     * <p>Capability matrix §2.1.2 absorb: Tier 3 sadece report-scoped column
     * type fallback verir (DB-level değil). Caller {@link SchemaTruthLookupContext#reportKey()}
     * dolu olmalı.
     *
     * <p>Tier 1 + Tier 2 fail-soft sonrası last-resort; her başvuruda WARN +
     * metric (Codex iter-1 §3 absorb usage-time).
     *
     * @param ctx          lookup context (reportKey + policy gerekli)
     * @param fieldName    column field name
     * @return Tier 3 column type if found
     */
    public Optional<String> lookupColumnTypeTier3(SchemaTruthLookupContext ctx, String fieldName) {
        if (ctx == null) {
            return Optional.empty();
        }
        try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_3")) {
            Optional<String> result = registryTypeFallback.lookupColumnType(ctx, fieldName);
            if (result.isPresent()) {
                SchemaTruthMetrics metrics = metricsProvider.getIfAvailable();
                if (metrics != null) {
                    metrics.recordFallback(ctx, "registry_type");
                }
            }
            return result;
        }
    }

    /**
     * Phase 2 Program 8e — schema fetch with tier provenance for endpoint
     * X-Schema-Truth-Tier header (Codex iter-1 §3 absorb — facade owned tier
     * orchestration; controller bypass YOK; 8d metrics/MDC korunur).
     *
     * @param ctx          lookup context (policy + reportKey + schemaMode + consumer)
     * @param schemaName   workcube schema adı
     * @return {@link SchemaTruthResult} snapshot Optional + tier string
     */
    public SchemaTruthResult fetchSnapshotWithTier(SchemaTruthLookupContext ctx, String schemaName) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        SchemaTruthMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics != null) {
            metrics.recordLookup(ctx);
        }

        switch (ctx.policy()) {
            case BUILD_DETERMINISTIC:
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_2")) {
                    Optional<SchemaSnapshot> snap = committedSnapshotLoader.lookup(ctx, schemaName);
                    return snap.isPresent()
                            ? new SchemaTruthResult(snap, SchemaTruthResult.TIER_COMMITTED_SNAPSHOT)
                            : new SchemaTruthResult(Optional.empty(), SchemaTruthResult.TIER_MISS);
                }

            case RUNTIME_STRICT_EXISTENCE:
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_1")) {
                    Optional<SchemaSnapshot> snap = schemaServiceClient.fetchSnapshot(ctx, schemaName);
                    return snap.isPresent()
                            ? new SchemaTruthResult(snap, SchemaTruthResult.TIER_SCHEMA_SERVICE)
                            : new SchemaTruthResult(Optional.empty(), SchemaTruthResult.TIER_MISS);
                }

            case RUNTIME_DEGRADED_TYPE:
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_1")) {
                    try {
                        Optional<SchemaSnapshot> tier1 = schemaServiceClient.fetchSnapshot(ctx, schemaName);
                        if (tier1.isPresent()) {
                            return new SchemaTruthResult(tier1, SchemaTruthResult.TIER_SCHEMA_SERVICE);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Tier 1 fail-soft, falling to Tier 2: schema={} error={}",
                                schemaName, e.getMessage());
                    }
                }
                try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_2")) {
                    Optional<SchemaSnapshot> tier2 = committedSnapshotLoader.lookup(ctx, schemaName);
                    if (tier2.isPresent()) {
                        if (metrics != null) {
                            metrics.recordFallback(ctx, "committed_snapshot");
                        }
                        return new SchemaTruthResult(tier2, SchemaTruthResult.TIER_COMMITTED_SNAPSHOT);
                    }
                }
                if (metrics != null) {
                    metrics.recordFallback(ctx, "registry_type");
                }
                return new SchemaTruthResult(Optional.empty(), SchemaTruthResult.TIER_REGISTRY_TYPE);

            default:
                throw new IllegalStateException("Unknown policy: " + ctx.policy());
        }
    }
}
