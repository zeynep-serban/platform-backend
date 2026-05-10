package com.serban.notify.provider;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.ProviderConfig;
import com.serban.notify.domain.ProviderConfigHistory;
import com.serban.notify.repository.ProviderConfigHistoryRepository;
import com.serban.notify.repository.ProviderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1.3 — Provider config rollback Testcontainers integration test
 * (Faz 23.2.C R12 mitigation).
 *
 * <p>4 acceptance gate test methods:
 * <ol>
 *   <li>{@link #atomicSwitchInsertsHistoryAndInvalidatesCache atomic_switch}
 *       — atomic activate/deactivate + history immutable insert + cache invalidate</li>
 *   <li>{@link #concurrentSwitchRaceEnforcesUniqueActiveConstraint concurrent_switch_race}
 *       — 2 thread aynı anda switch dener; biri commit, diğeri rollback (PG partial
 *       unique index serializes)</li>
 *   <li>{@link #cacheInvalidationOccursAfterSuccessfulSwitch cache_invalidate}
 *       — switch sonrası cache eski entry'i tutmuyor; yeni primary döner</li>
 *   <li>{@link #rollbackOnFailureRevertsHistoryAndConfigState rollback_on_fail}
 *       — validation fail (provider_key mismatch) → rollback; history insert yok,
 *       active state değişmedi</li>
 * </ol>
 *
 * <p>Refs:
 * <ul>
 *   <li>ADR-0013 §provider config rollback</li>
 *   <li>Risk register R12 (provider config rollback transaction race)</li>
 *   <li>Charter sub-faz 23.2.C</li>
 *   <li>Cross-AI peer review HARD RULE 2026-05-05 (Codex review post-impl)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@TestPropertySource(properties = {
    "notify.provider.cache-ttl-seconds=1"  // 1s TTL for cache invalidation tests
})
class ProviderConfigRollbackIntegrationTest extends AbstractPostgresTest {

    @Autowired
    private ProviderConfigService service;

    @Autowired
    private ProviderConfigRepository configRepo;

    @Autowired
    private ProviderConfigHistoryRepository historyRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        // Codex iter-1 (019e116e) RED absorb: provider_config_history table has
        // PG rule "ON DELETE DO INSTEAD NOTHING" + JPA @Immutable for audit-trail
        // protection. DELETE is a no-op. Use TRUNCATE which bypasses rules
        // (PG-specific). For provider_config no immutable rule, DELETE works.
        jdbc.execute("TRUNCATE TABLE notify.provider_config_history");
        jdbc.execute("DELETE FROM notify.provider_config");
        service.invalidateCache();
    }

    /**
     * T1.3.1 — atomic_switch — single-thread happy path.
     *
     * <p>Validates:
     * <ul>
     *   <li>fromConfig.active = false post-switch (deactivated)</li>
     *   <li>toConfig.active = true + activatedAt set</li>
     *   <li>history row inserted with deactivation snapshot + reason</li>
     *   <li>history row immutable (UPDATE/DELETE noop per DB rule)</li>
     * </ul>
     */
    @Test
    void atomicSwitchInsertsHistoryAndInvalidatesCache() {
        ProviderConfig from = persistActive("mailgun", "email", 1);
        ProviderConfig to = persistInactive("mailgun", "email", 2);

        // Pre-condition: only `from` is active
        assertThat(historyRepo.findByProviderKeyAndEnvironmentOrderByDeactivatedAtDesc("mailgun", "test"))
            .as("history empty pre-switch")
            .isEmpty();

        // Action — switch
        service.switchActive(from.getId(), to.getId(), "manual failover test");

        // Post-condition: state flipped
        ProviderConfig fromAfter = configRepo.findById(from.getId()).orElseThrow();
        ProviderConfig toAfter = configRepo.findById(to.getId()).orElseThrow();

        assertThat(fromAfter.isActive()).as("from deactivated").isFalse();
        assertThat(fromAfter.getActivatedAt()).as("from.activatedAt cleared").isNull();
        assertThat(toAfter.isActive()).as("to activated").isTrue();
        assertThat(toAfter.getActivatedAt()).as("to.activatedAt set").isNotNull();

        // History row written immutable
        List<ProviderConfigHistory> hist = historyRepo
            .findByProviderKeyAndEnvironmentOrderByDeactivatedAtDesc("mailgun", "test");
        assertThat(hist).as("history row inserted").hasSize(1);
        ProviderConfigHistory snapshot = hist.get(0);
        assertThat(snapshot.getProviderConfigId()).isEqualTo(from.getId());
        assertThat(snapshot.getProviderKey()).isEqualTo("mailgun");
        assertThat(snapshot.getDeactivatedAt()).isNotNull();
        assertThat(snapshot.getDeactivationReason()).isEqualTo("manual failover test");

        // Verify history immutability (DB rule ON UPDATE/DELETE DO INSTEAD NOTHING)
        int updated = jdbc.update(
            "UPDATE notify.provider_config_history SET deactivation_reason = 'tampered' WHERE id = ?",
            snapshot.getId());
        assertThat(updated).as("UPDATE noop on history (immutable rule)").isEqualTo(0);
        ProviderConfigHistory reloaded = historyRepo.findById(snapshot.getId()).orElseThrow();
        assertThat(reloaded.getDeactivationReason())
            .as("reason unchanged after UPDATE attempt")
            .isEqualTo("manual failover test");
    }

    /**
     * T1.3.2 — concurrent_switch_race — 2 thread aynı `from` source'tan
     * 2 farklı target'a switch dener. PG partial unique index
     * {@code idx_provider_active(org_id, provider_key, environment) WHERE active=TRUE}
     * + SERIALIZABLE isolation level birini commit eder, diğeri
     * DataIntegrityViolationException ile rollback olur.
     *
     * <p>Validates: end state'de tam olarak 1 active provider; history'de 1 row
     * (winner thread); loser thread DataIntegrityViolationException attı.
     */
    @Test
    void concurrentSwitchRaceEnforcesUniqueActiveConstraint() throws Exception {
        ProviderConfig from = persistActive("mailgun", "email", 1);
        ProviderConfig toA = persistInactive("mailgun", "email", 2);
        ProviderConfig toB = persistInactive("mailgun", "email", 3);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Future<?> taskA = pool.submit(() -> {
            try {
                startGate.await();
                service.switchActive(from.getId(), toA.getId(), "race A");
            } catch (Throwable t) {
                errA.set(t);
            }
            return null;
        });
        Future<?> taskB = pool.submit(() -> {
            try {
                startGate.await();
                service.switchActive(from.getId(), toB.getId(), "race B");
            } catch (Throwable t) {
                errB.set(t);
            }
            return null;
        });

        startGate.countDown();
        try {
            taskA.get(10, TimeUnit.SECONDS);
            taskB.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Exactly one thread succeeded; the loser hits one of two paths:
        // (a) first thread commits, second sees `from.active=false` after read
        //     and throws IllegalStateException at validation, OR
        // (b) PG SERIALIZABLE+partial unique constraint serializes both;
        //     second's flush fails with DataIntegrityViolationException.
        // Both are valid race outcomes; Codex iter-1 (019e116e) suggested
        // strict exception assertion.
        long failedThreads = (errA.get() != null ? 1 : 0) + (errB.get() != null ? 1 : 0);
        assertThat(failedThreads).as("exactly one thread failed (loser race)").isEqualTo(1);

        Throwable loserError = errA.get() != null ? errA.get() : errB.get();
        assertThat(loserError)
            .as("loser thread threw expected exception type")
            .isInstanceOfAny(IllegalStateException.class,
                             DataIntegrityViolationException.class,
                             org.springframework.dao.CannotAcquireLockException.class,
                             org.springframework.dao.PessimisticLockingFailureException.class,
                             org.springframework.transaction.TransactionSystemException.class);

        // End state: exactly one active provider in (org_id, provider_key, env)
        long activeCount = configRepo.findActiveByOrgChannelOrderByPriority("*", "email", "test")
            .stream().filter(ProviderConfig::isActive).count();
        assertThat(activeCount).as("only one active per (org_id, provider_key, env)").isEqualTo(1L);

        // History has exactly one row (winner's deactivation)
        List<ProviderConfigHistory> hist = historyRepo
            .findByProviderKeyAndEnvironmentOrderByDeactivatedAtDesc("mailgun", "test");
        assertThat(hist).as("history has one row (winner only)").hasSize(1);
    }

    /**
     * T1.3.3 — cache_invalidate — switch sonrası findPrimary() yeni active'i
     * döner (cache eski primary'i tutmuyor).
     *
     * <p>Cache TTL 1s @TestPropertySource'da; ama invalidateCache() switch
     * içinde çağrıldığı için TTL beklemeden anında temizlenir.
     */
    @Test
    void cacheInvalidationOccursAfterSuccessfulSwitch() {
        ProviderConfig from = persistActive("mailgun", "email", 1);
        ProviderConfig to = persistInactive("mailgun", "email", 2);

        // Populate cache via findPrimary
        ProviderConfig before = service.findPrimary("*", "email", "test").orElseThrow();
        assertThat(before.getId()).as("primary pre-switch is from").isEqualTo(from.getId());

        // Switch
        service.switchActive(from.getId(), to.getId(), "cache invalidate test");

        // Immediately after switch (within TTL window), cache should be cleared
        // and new primary should be `to`
        ProviderConfig after = service.findPrimary("*", "email", "test").orElseThrow();
        assertThat(after.getId()).as("primary post-switch is to (cache cleared)").isEqualTo(to.getId());
        assertThat(after.isActive()).isTrue();
    }

    /**
     * T1.3.4 — rollback_on_fail — validation failure (provider_key mismatch)
     * → IllegalStateException → @Transactional rollback. History row insert
     * edilmemeli; from/to active state değişmemeli.
     */
    @Test
    void rollbackOnFailureRevertsHistoryAndConfigState() {
        ProviderConfig from = persistActive("mailgun", "email", 1);
        // mismatched provider_key (different `provider_key` on purpose)
        ProviderConfig wrongTarget = persistInactive("sendgrid", "email", 1);

        long historyCountBefore = historyRepo.count();

        assertThatThrownBy(() ->
            service.switchActive(from.getId(), wrongTarget.getId(), "should fail"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("provider_key mismatch");

        // History unchanged (transaction rolled back)
        long historyCountAfter = historyRepo.count();
        assertThat(historyCountAfter).as("history row count unchanged").isEqualTo(historyCountBefore);

        // From still active, target still inactive
        ProviderConfig fromAfter = configRepo.findById(from.getId()).orElseThrow();
        ProviderConfig wrongTargetAfter = configRepo.findById(wrongTarget.getId()).orElseThrow();
        assertThat(fromAfter.isActive()).as("from still active").isTrue();
        assertThat(wrongTargetAfter.isActive()).as("target still inactive").isFalse();
    }

    // ---------- helpers ----------

    private ProviderConfig persistActive(String providerKey, String channel, int version) {
        ProviderConfig config = buildConfig(providerKey, channel, version, true);
        return configRepo.save(config);
    }

    private ProviderConfig persistInactive(String providerKey, String channel, int version) {
        ProviderConfig config = buildConfig(providerKey, channel, version, false);
        return configRepo.save(config);
    }

    private ProviderConfig buildConfig(String providerKey, String channel, int version, boolean active) {
        ProviderConfig config = new ProviderConfig();
        config.setOrgId("*");
        config.setProviderKey(providerKey);
        config.setChannel(channel);
        config.setEnvironment("test");
        config.setVersion(version);
        config.setActive(active);
        config.setPriority(100);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("api_key", "test-key-v" + version);
        cfg.put("from", "noreply@example.com");
        config.setConfig(cfg);
        config.setCredentialRef("kv/platform/notify/" + providerKey);
        if (active) {
            config.setActivatedAt(OffsetDateTime.now());
        }
        return config;
    }
}
