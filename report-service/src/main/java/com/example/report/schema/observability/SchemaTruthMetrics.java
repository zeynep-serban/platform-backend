package com.example.report.schema.observability;

import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8d — Schema Truth observability metrics.
 *
 * <p>Spec §2.4 metric set + §9 DoD 6 metrics:
 * <ul>
 *   <li>{@code schema_truth_lookup_total{schema_mode}} — total lookup count</li>
 *   <li>{@code schema_truth_fallback_total{tier, schema_mode}} — Tier transition count
 *       (incremented when a tier was used as fallback, not the primary)</li>
 *   <li>{@code schema_truth_cache_hit_total} — Caffeine cache hit count (Tier 1)</li>
 *   <li>{@code schema_truth_snapshot_age_days} — Tier 2 committed snapshot age (gauge)</li>
 *   <li>{@code schema_truth_snapshot_age_warn} — 0/1 binary flag (>30d threshold, Q4 default)</li>
 *   <li>{@code schema_truth_cache_miss_burst} — 0/1 binary flag (5-min cache miss rate > 50%)</li>
 * </ul>
 *
 * <p>Tag cardinality (Codex iter-1 §3 absorb): {tier, schema_mode} only;
 * 3 tier × 4 schemaMode + 1 unknown = ~15 series ceiling. Report_key /
 * tenant_id metric label DEĞİL — log/MDC tarafında ({@link SchemaTruthLogContext}).
 *
 * <p>{@link MeterRegistry} {@link ObjectProvider} ile null-safe inject;
 * test context'inde actuator yokken null kalır, metric increments no-op.
 */
@Component
public class SchemaTruthMetrics {

    private static final Logger log = LoggerFactory.getLogger(SchemaTruthMetrics.class);

    public static final String LOOKUP_TOTAL = "schema_truth_lookup_total";
    public static final String FALLBACK_TOTAL = "schema_truth_fallback_total";
    public static final String CACHE_HIT_TOTAL = "schema_truth_cache_hit_total";
    public static final String SNAPSHOT_AGE_DAYS = "schema_truth_snapshot_age_days";
    public static final String SNAPSHOT_AGE_WARN = "schema_truth_snapshot_age_warn";
    public static final String CACHE_MISS_BURST = "schema_truth_cache_miss_burst";
    public static final String SCHEMA_TRUTH_CACHE_NAME = "schemaTruthSnapshot";

    private static final long SNAPSHOT_AGE_THRESHOLD_DAYS = 30L; // Q4 default

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final CommittedSnapshotLoader committedSnapshotLoader;
    private final ObjectProvider<CacheManager> cacheManagerProvider;
    private final Clock clock;

    // Caffeine native stats deltas — Codex iter-1 §1 absorb (cache_hit_total
    // ve cache_miss_burst gerçek Caffeine stats'tan beslenir, facade'da
    // recordCacheHit YOK — Tier 1 success ≠ Caffeine hit ayrımı).
    private final AtomicLong lastSnapshotHitCount = new AtomicLong(0L);
    private final AtomicLong lastSnapshotRequestCount = new AtomicLong(0L);

    // Per-tag-tuple Counter cache to avoid registry lookup on hot path.
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    // Gauges — written to via AtomicLong.
    private final AtomicLong snapshotAgeDaysGauge = new AtomicLong(-1L);
    private final AtomicLong snapshotAgeWarnGauge = new AtomicLong(0L);
    private final AtomicLong cacheMissBurstGauge = new AtomicLong(0L);

    public SchemaTruthMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                CommittedSnapshotLoader committedSnapshotLoader,
                                ObjectProvider<CacheManager> cacheManagerProvider,
                                Clock clock) {
        this.meterRegistryProvider = meterRegistryProvider;
        this.committedSnapshotLoader = committedSnapshotLoader;
        this.cacheManagerProvider = cacheManagerProvider;
        this.clock = clock;
    }

    @PostConstruct
    public void registerGauges() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.debug("MeterRegistry absent; SchemaTruthMetrics gauges not registered");
            return;
        }
        registry.gauge(SNAPSHOT_AGE_DAYS, snapshotAgeDaysGauge);
        registry.gauge(SNAPSHOT_AGE_WARN, snapshotAgeWarnGauge);
        registry.gauge(CACHE_MISS_BURST, cacheMissBurstGauge);
        // Initialize snapshot age gauge from current loader state.
        refreshSnapshotAgeGauge();
    }

    /**
     * Record a {@code schema_truth_lookup_total{schema_mode}} increment.
     */
    public void recordLookup(SchemaTruthLookupContext ctx) {
        incrementCounter(LOOKUP_TOTAL,
                Tags.of("schema_mode", schemaModeTag(ctx)));
    }

    /**
     * Record a {@code schema_truth_fallback_total{tier, schema_mode}} increment
     * — Tier was used as fallback (Tier 1 fail-soft + Tier 2 served, OR Tier 1+2
     * fail-soft + Tier 3 served).
     */
    public void recordFallback(SchemaTruthLookupContext ctx, String tier) {
        incrementCounter(FALLBACK_TOTAL,
                Tags.of("tier", tier, "schema_mode", schemaModeTag(ctx)));
    }

    /**
     * Refresh snapshot age gauge from {@link CommittedSnapshotLoader}.
     * Called periodically + on startup.
     */
    @Scheduled(fixedDelay = 60000L) // 1-minute refresh
    public void refreshSnapshotAgeGauge() {
        Optional<Long> ageOpt = committedSnapshotLoader.snapshotAgeDays(clock);
        if (ageOpt.isEmpty()) {
            snapshotAgeDaysGauge.set(-1L);
            snapshotAgeWarnGauge.set(0L);
            return;
        }
        long age = ageOpt.get();
        snapshotAgeDaysGauge.set(age);
        snapshotAgeWarnGauge.set(age > SNAPSHOT_AGE_THRESHOLD_DAYS ? 1L : 0L);
    }

    /**
     * Refresh cache-miss-burst gauge — 1 if 5-min cache miss rate > 50%.
     * Codex iter-1 §1 absorb: gerçek Caffeine native stats delta'sından
     * beslenir; facade'daki Tier 1 success ≠ Caffeine hit ayrımı korunur.
     */
    @Scheduled(fixedDelay = 300000L) // 5-minute window
    public void refreshCacheMissBurstGauge() {
        Optional<CacheStats> statsOpt = readNativeCaffeineStats();
        if (statsOpt.isEmpty()) {
            cacheMissBurstGauge.set(0L);
            return;
        }
        CacheStats current = statsOpt.get();
        long previousHits = lastSnapshotHitCount.getAndSet(current.hitCount());
        long previousReqs = lastSnapshotRequestCount.getAndSet(current.requestCount());
        long deltaHits = current.hitCount() - previousHits;
        long deltaReqs = current.requestCount() - previousReqs;

        // Codex iter-2 §1 absorb: native stats delta'dan schema_truth_cache_hit_total
        // counter'ını re-export et (custom namespace contract korunur).
        if (deltaHits > 0) {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                String key = CACHE_HIT_TOTAL + ":" + Tags.empty();
                Counter counter = counterCache.computeIfAbsent(key, k ->
                        Counter.builder(CACHE_HIT_TOTAL).register(registry));
                counter.increment(deltaHits);
            }
        }

        if (deltaReqs < 10L) {
            // Insufficient samples in 5-min window; flapping önle.
            cacheMissBurstGauge.set(0L);
            return;
        }
        double missRate = 1.0 - ((double) deltaHits / deltaReqs);
        cacheMissBurstGauge.set(missRate > 0.5 ? 1L : 0L);
    }

    /**
     * Read native Caffeine stats from the {@code schemaTruthSnapshot} cache.
     *
     * <p>{@link CacheConfig} {@code recordStats()} açık;
     * {@link Cache#stats()} O(1) snapshot döner.
     */
    private Optional<CacheStats> readNativeCaffeineStats() {
        CacheManager mgr = cacheManagerProvider.getIfAvailable();
        if (mgr == null) {
            return Optional.empty();
        }
        org.springframework.cache.Cache springCache = mgr.getCache(SCHEMA_TRUTH_CACHE_NAME);
        if (!(springCache instanceof CaffeineCache caffeineCache)) {
            return Optional.empty();
        }
        Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
        return Optional.of(nativeCache.stats());
    }

    /**
     * Direct gauge read for testing.
     */
    public long getSnapshotAgeDaysGauge() {
        return snapshotAgeDaysGauge.get();
    }

    public long getSnapshotAgeWarnGauge() {
        return snapshotAgeWarnGauge.get();
    }

    public long getCacheMissBurstGauge() {
        return cacheMissBurstGauge.get();
    }

    /**
     * Clock for test deterministic age calculations (defaults to system Clock
     * via Spring config; injectable in tests).
     */
    public Duration getSnapshotAgeThreshold() {
        return Duration.ofDays(SNAPSHOT_AGE_THRESHOLD_DAYS);
    }

    private String schemaModeTag(SchemaTruthLookupContext ctx) {
        if (ctx == null || ctx.schemaMode() == null || ctx.schemaMode().isBlank()) {
            return "unknown";
        }
        return ctx.schemaMode();
    }

    private void incrementCounter(String name, Tags tags) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        String key = name + ":" + tags;
        Counter counter = counterCache.computeIfAbsent(key, k ->
                Counter.builder(name).tags(tags).register(registry));
        counter.increment();
    }
}
