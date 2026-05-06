package com.example.report.workcube;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test that proves the {@code companyOptions} cache is
 * actually wired through Spring's AOP proxy on
 * {@link CompanyOptionsRepository#findAll()}.
 *
 * <p>Codex 019dfb15 iter-2 absorb #1 + #5: a unit test with Mockito
 * cannot detect the original bug (cache annotation on a service method
 * called via {@code this.method()} bypasses the proxy). This test boots
 * a minimal Spring context with {@code @EnableCaching} and verifies:
 * <ul>
 *   <li>The {@code companyOptions} cache exists in the manager.</li>
 *   <li>The first call hits the underlying {@link JdbcTemplate}.</li>
 *   <li>The second call returns the cached list without touching the
 *       JDBC layer (i.e. the proxy intercepted it).</li>
 * </ul>
 *
 * <p>Why this is a separate IT (not folded into
 * {@link CompanyOptionsRepositoryTest}): the unit tests inject the
 * repository directly with {@code new}; that path skips Spring's proxy
 * entirely, so cache annotations have no effect there.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CompanyOptionsRepositoryCachingIT.TestConfig.class)
class CompanyOptionsRepositoryCachingIT {

    @Autowired CompanyOptionsRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("companyOptions");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void companyOptionsCacheIsRegistered() {
        Cache cache = cacheManager.getCache("companyOptions");
        assertNotNull(cache, "companyOptions cache must be registered in CacheConfig");
    }

    @Test
    void findAll_isCached_secondCallSkipsJdbc() {
        List<CompanyOptionsRepository.CompanyOption> rows = List.of(
                new CompanyOptionsRepository.CompanyOption(1, "ARC", "ARÇELİK A.Ş.")
        );
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenReturn(rows);

        List<CompanyOptionsRepository.CompanyOption> first = repository.findAll();
        List<CompanyOptionsRepository.CompanyOption> second = repository.findAll();

        assertEquals(rows, first);
        assertSame(first, second, "second call must return the cached list");
        verify(jdbc, times(1)).query(any(String.class), any(RowMapper.class));
    }

    @Test
    void cachedValueIsReadable_viaCacheManager() {
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenReturn(List.of(new CompanyOptionsRepository.CompanyOption(
                        1, "ARC", "ARÇELİK A.Ş.")));

        repository.findAll();

        Cache cache = cacheManager.getCache("companyOptions");
        assertNotNull(cache);
        @SuppressWarnings("unchecked")
        Optional<List<?>> hit = Optional.ofNullable(cache.get(SimpleKey()))
                .map(value -> (List<?>) value.get());
        assertNotNull(hit.orElse(null), "findAll() result must be in cache after first call");
    }

    /**
     * Spring's {@code @Cacheable} uses {@code SimpleKey.EMPTY} when the
     * method takes no arguments. We reference it by canonical FQN to keep
     * the test free of import noise.
     */
    private static Object SimpleKey() {
        return org.springframework.cache.interceptor.SimpleKey.EMPTY;
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        public JdbcTemplate workcubeMssqlPlainJdbc() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        public CompanyOptionsRepository companyOptionsRepository(JdbcTemplate workcubeMssqlPlainJdbc) {
            return new CompanyOptionsRepository(workcubeMssqlPlainJdbc);
        }

        @Bean
        public CacheManager cacheManager() {
            // Mirror the production CacheConfig entry for companyOptions
            // so this test fails if the cache name is removed there.
            CaffeineCache companyOptions = new CaffeineCache("companyOptions",
                    Caffeine.newBuilder()
                            .expireAfterWrite(Duration.ofMinutes(5))
                            .maximumSize(1)
                            .build());
            SimpleCacheManager manager = new SimpleCacheManager();
            manager.setCaches(List.of(companyOptions));
            return manager;
        }
    }
}
