package com.example.report.schema.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

/**
 * Phase 2 Program 8d — SchemaTruthMetrics Caffeine native stats delta path test
 * (Codex iter-2 §2 absorb).
 *
 * <p>Real Caffeine cache lifecycle (recordStats() açık) + delta hesabı +
 * schema_truth_cache_hit_total native re-export contract.
 */
class SchemaTruthMetricsCacheStatsTest {

    private SchemaTruthMetrics metrics;
    private SimpleMeterRegistry registry;
    private CaffeineCache caffeineCache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider =
                (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);

        caffeineCache = new CaffeineCache(SchemaTruthMetrics.SCHEMA_TRUTH_CACHE_NAME,
                Caffeine.newBuilder()
                        .recordStats()
                        .maximumSize(100)
                        .build());
        SimpleCacheManager mgr = new SimpleCacheManager();
        mgr.setCaches(List.of(caffeineCache));
        mgr.afterPropertiesSet();

        ObjectProvider<CacheManager> cacheManagerProvider =
                (ObjectProvider<CacheManager>) mock(ObjectProvider.class);
        when(cacheManagerProvider.getIfAvailable()).thenReturn(mgr);

        metrics = new SchemaTruthMetrics(registryProvider,
                mock(CommittedSnapshotLoader.class),
                cacheManagerProvider,
                Clock.systemUTC());
        metrics.registerGauges();
    }

    @Test
    void cacheHitTotal_reExportsFromNativeDelta() {
        // Generate cache hits via real Caffeine lifecycle: put + get
        caffeineCache.put("schema-1", "value-1");
        for (int i = 0; i < 5; i++) {
            caffeineCache.get("schema-1"); // 5 hits
        }
        for (int i = 0; i < 3; i++) {
            caffeineCache.get("schema-missing"); // 3 misses
        }

        metrics.refreshCacheMissBurstGauge();

        Counter hitCounter = registry.find(SchemaTruthMetrics.CACHE_HIT_TOTAL).counter();
        assertThat(hitCounter).isNotNull();
        assertThat(hitCounter.count()).isEqualTo(5.0);
    }

    @Test
    void cacheMissBurstGauge_oneWhenMissRateAboveFifty() {
        // 3 hits + 7 misses = 30% hit rate = 70% miss rate (>50% threshold) + 10 samples
        caffeineCache.put("schema-1", "value-1");
        for (int i = 0; i < 3; i++) {
            caffeineCache.get("schema-1"); // 3 hits
        }
        for (int i = 0; i < 7; i++) {
            caffeineCache.get("schema-missing-" + i); // 7 misses (different keys)
        }

        metrics.refreshCacheMissBurstGauge();

        assertThat(metrics.getCacheMissBurstGauge()).isEqualTo(1L);
    }

    @Test
    void cacheMissBurstGauge_zeroWhenInsufficientSamples() {
        // Only 5 total requests (<10 sample threshold)
        caffeineCache.put("schema-1", "value-1");
        for (int i = 0; i < 5; i++) {
            caffeineCache.get("schema-1");
        }

        metrics.refreshCacheMissBurstGauge();

        assertThat(metrics.getCacheMissBurstGauge()).isEqualTo(0L);
    }
}
