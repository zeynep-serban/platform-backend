package com.example.report.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache("reportDefinitions", Duration.ofHours(1), 100),
                buildCache("reportCounts", Duration.ofSeconds(60), 1000),
                buildCache("reportCategories", Duration.ofMinutes(5), 10),
                buildCache("authzMe", Duration.ofMinutes(5), 500),
                buildCache("dashboardKpis", Duration.ofMinutes(5), 200),
                buildCache("dashboardCharts", Duration.ofMinutes(5), 500),
                buildCache("yearlySchemas", Duration.ofMinutes(30), 10),
                buildCache("contextHealthFiles", Duration.ofSeconds(30), 20),
                buildCache("contextHealthKpis", Duration.ofSeconds(30), 1),
                buildCache("contextHealthCharts", Duration.ofSeconds(30), 1),
                buildCache("contextHealthGrids", Duration.ofSeconds(30), 10),
                // Workcube CompanyPicker dropdown — global per-cluster catalog,
                // 5dk TTL (Codex 019dfb15 iter-2 absorb #1).
                buildCache("companyOptions", Duration.ofMinutes(5), 1),
                // Phase 2 Program 8a: SchemaTruthService Tier 1 cache (Plan v2.1 §3.8 default).
                // Keyed by schemaName; 5-min TTL matches Plan §3.8 prescription.
                buildCache("schemaTruthSnapshot", Duration.ofMinutes(5), 100)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, Duration ttl, int maxSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl)
                        .maximumSize(maxSize)
                        .recordStats()
                        .build());
    }
}
