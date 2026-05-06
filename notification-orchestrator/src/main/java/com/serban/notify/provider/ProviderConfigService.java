package com.serban.notify.provider;

import com.serban.notify.domain.ProviderConfig;
import com.serban.notify.repository.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ProviderConfigService — runtime provider config lookup with cache + org-aware
 * failover (Faz 23.2 PR-A — Codex 019dfae5 Q4 REVISE absorb).
 *
 * <p>Codex Q4 absorb:
 * <ul>
 *   <li>Multi-tenant: org × channel × provider tuple — org-specific override veya
 *       default org_id='*' fallback</li>
 *   <li>Hot reload: 30-60s cache TTL ile config değişikliği yumuşak yansır</li>
 *   <li>Failover: priority ASC içinde aynı (org, channel, env) → en düşük
 *       priority active=TRUE seçilir; transient failure'da next-priority dene</li>
 *   <li>Auto-failover sadece provider-accept öncesi kesin transient failure'da;
 *       ambiguous/accepted durumda manual failover</li>
 * </ul>
 *
 * <p>Cache: per (orgId, channel, env) tuple → list of active providers ordered by
 * priority. TTL: {@code notify.provider.cache-ttl-seconds} (default 30).
 */
@Service
public class ProviderConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfigService.class);

    private final ProviderConfigRepository repo;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderConfigService(
        ProviderConfigRepository repo,
        @Value("${notify.provider.cache-ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.repo = repo;
        this.cacheTtlMs = Duration.ofSeconds(cacheTtlSeconds).toMillis();
        log.info("ProviderConfigService activated: cache-ttl={}s", cacheTtlSeconds);
    }

    /**
     * Lookup providers for (orgId, channel, env) ordered by priority.
     *
     * <p>org-specific override has precedence; falls back to org_id='*'.
     *
     * @return ordered list (lowest priority first); empty if no active config
     */
    public List<ProviderConfig> findProviders(String orgId, String channel, String environment) {
        CacheKey key = new CacheKey(orgId, channel, environment);
        CacheEntry entry = cache.get(key);
        long now = Instant.now().toEpochMilli();
        if (entry != null && (now - entry.cachedAt) < cacheTtlMs) {
            return entry.providers;
        }
        List<ProviderConfig> providers = repo.findActiveByOrgChannelOrderByPriority(
            orgId, channel, environment
        );
        cache.put(key, new CacheEntry(providers, now));
        return providers;
    }

    /**
     * Find primary provider (lowest priority, org-override-first).
     */
    public Optional<ProviderConfig> findPrimary(String orgId, String channel, String environment) {
        List<ProviderConfig> providers = findProviders(orgId, channel, environment);
        return providers.isEmpty() ? Optional.empty() : Optional.of(providers.get(0));
    }

    /**
     * Find next-priority provider for failover (skip current).
     *
     * <p>Codex Q4: Auto-failover sadece provider-accept öncesi kesin transient
     * failure'da. Caller responsibility — bu metot sadece "sonraki" sağlar.
     *
     * @param current çağrı sırasında failed provider
     * @return next provider in priority chain; empty if no fallback
     */
    public Optional<ProviderConfig> findNextFailover(
        String orgId, String channel, String environment, ProviderConfig current
    ) {
        List<ProviderConfig> providers = findProviders(orgId, channel, environment);
        boolean afterCurrent = false;
        for (ProviderConfig p : providers) {
            if (afterCurrent) return Optional.of(p);
            if (p.getId().equals(current.getId())) afterCurrent = true;
        }
        return Optional.empty();
    }

    /**
     * Cache invalidation (operator runbook: provider config DB update sonrası).
     * Hot reload alternatifi: 30-60s TTL doğal expire.
     */
    public void invalidateCache() {
        cache.clear();
        log.info("ProviderConfigService cache invalidated");
    }

    private record CacheKey(String orgId, String channel, String environment) {}
    private record CacheEntry(List<ProviderConfig> providers, long cachedAt) {}
}
