package com.serban.notify.provider;

import com.serban.notify.domain.ProviderConfig;
import com.serban.notify.domain.ProviderConfigHistory;
import com.serban.notify.repository.ProviderConfigHistoryRepository;
import com.serban.notify.repository.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
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
    private final ProviderConfigHistoryRepository historyRepo;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderConfigService(
        ProviderConfigRepository repo,
        ProviderConfigHistoryRepository historyRepo,
        @Value("${notify.provider.cache-ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.repo = repo;
        this.historyRepo = historyRepo;
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

    /**
     * Atomic provider switch (Faz 23.2.C — R12 mitigation, T1.3 acceptance gate).
     *
     * <p>Source config'i deactivate eder, history snapshot yazar (immutable),
     * target config'i activate eder, cache invalidate eder — hepsi tek
     * transaction içinde. PG partial unique constraint
     * {@code idx_provider_active(org_id, provider_key, environment) WHERE active=TRUE}
     * concurrent switch race'i serialize eder.
     *
     * <p>SERIALIZABLE isolation level concurrent provider config güncellemelerini
     * birbirinden izole eder. İki thread aynı anda switchActive çağırırsa biri
     * commit eder, diğeri DataIntegrityViolationException ile rollback olur.
     *
     * <p>History row immutable (ON UPDATE/DELETE DO INSTEAD NOTHING DB rule + JPA
     * {@code @Immutable}); insert-only.
     *
     * <p>Validation:
     * <ul>
     *   <li>fromConfig MUST be active</li>
     *   <li>toConfig MUST be inactive</li>
     *   <li>Both MUST share (orgId, providerKey, environment) tuple</li>
     * </ul>
     *
     * @param fromConfigId currently active provider config (will deactivate)
     * @param toConfigId target inactive provider config (will activate)
     * @param reason audit-friendly deactivation reason (e.g. "manual failover",
     *               "credential rotation", "version rollback")
     * @throws IllegalStateException validation fail (not found, wrong state, mismatched tuple)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void switchActive(Long fromConfigId, Long toConfigId, String reason) {
        ProviderConfig from = repo.findById(fromConfigId)
            .orElseThrow(() -> new IllegalStateException(
                "switchActive: source config not found: " + fromConfigId));
        ProviderConfig to = repo.findById(toConfigId)
            .orElseThrow(() -> new IllegalStateException(
                "switchActive: target config not found: " + toConfigId));

        if (!from.isActive()) {
            throw new IllegalStateException(
                "switchActive: source config not active (id=" + fromConfigId + ")");
        }
        if (to.isActive()) {
            throw new IllegalStateException(
                "switchActive: target config already active (id=" + toConfigId + ")");
        }
        if (!Objects.equals(from.getProviderKey(), to.getProviderKey())) {
            throw new IllegalStateException(
                "switchActive: provider_key mismatch (from=" + from.getProviderKey()
                + " to=" + to.getProviderKey() + ")");
        }
        if (!Objects.equals(from.getOrgId(), to.getOrgId())) {
            throw new IllegalStateException(
                "switchActive: org_id mismatch (from=" + from.getOrgId()
                + " to=" + to.getOrgId() + ")");
        }
        if (!Objects.equals(from.getEnvironment(), to.getEnvironment())) {
            throw new IllegalStateException(
                "switchActive: environment mismatch (from=" + from.getEnvironment()
                + " to=" + to.getEnvironment() + ")");
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Insert immutable history snapshot of `from` deactivation
        ProviderConfigHistory history = new ProviderConfigHistory();
        history.setProviderConfigId(from.getId());
        history.setProviderKey(from.getProviderKey());
        history.setEnvironment(from.getEnvironment());
        history.setVersion(from.getVersion());
        history.setConfig(from.getConfig());
        history.setCredentialRef(from.getCredentialRef());
        history.setActivatedAt(from.getActivatedAt());
        history.setDeactivatedAt(now);
        history.setDeactivationReason(reason);
        historyRepo.save(history);

        // Deactivate source
        from.setActive(false);
        from.setActivatedAt(null);
        repo.save(from);

        // Activate target — partial unique index serializes any concurrent attempt
        to.setActive(true);
        to.setActivatedAt(now);
        repo.save(to);

        // Cache invalidation (after commit; transaction sync would be better but
        // simple post-commit semantic acceptable: cache TTL expires within 30s
        // worst-case if cache invalidation visible before commit replication).
        invalidateCache();

        log.info("Provider config switch: orgId={} channel={} from={} to={} reason='{}'",
            from.getOrgId(), from.getChannel(), fromConfigId, toConfigId, reason);
    }

    private record CacheKey(String orgId, String channel, String environment) {}
    private record CacheEntry(List<ProviderConfig> providers, long cachedAt) {}
}
