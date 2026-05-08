package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.commonauth.scope.OpenFgaScopeReader;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.permission.service.AuthzVersionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true", matchIfMissing = false)
public class OpenFgaAuthzConfig {

    @Bean
    @ConfigurationProperties(prefix = "erp.openfga")
    public OpenFgaProperties openFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    public OpenFgaAuthzService openFgaAuthzService(OpenFgaProperties props,
                                                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // B3/B4 (Rev 19): MeterRegistry optional — null safe when actuator absent (test context)
        return OpenFgaConfig.createAuthzService(props, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public ScopeContextCache scopeContextCache(
            @Value("${scope.cache.enabled:true}") boolean enabled,
            @Value("${scope.cache.ttl-seconds:30}") int ttlSeconds,
            @Value("${scope.cache.ttl-jitter-seconds:3}") int jitterSeconds,
            @Value("${scope.cache.max-size:5000}") long maxSize) {
        return new ScopeContextCache(java.time.Duration.ofSeconds(ttlSeconds), java.time.Duration.ofSeconds(jitterSeconds), maxSize, enabled);
    }

    @Bean
    public AuthzVersionProvider authzVersionProvider(AuthzVersionService authzVersionService) {
        return authzVersionService::getCurrentVersion;
    }

    /**
     * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3+4):
     * shared OpenFGA scope reader bean. Used by both ScopeContextFilter
     * (request-scope binding) and admin-side services
     * (UserScopeService, AuthorizationQueryService) to read scope tuples
     * from OpenFGA via the same parallel-fetch + cache + relation map
     * path. Aligns reads with the canonical Faz 21.3 ADR-0008
     * explicit-scope model (all object types use {@code viewer}).
     */
    @Bean
    public OpenFgaScopeReader openFgaScopeReader(OpenFgaAuthzService authzService,
                                                  OpenFgaProperties properties,
                                                  ObjectProvider<ScopeContextCache> cacheProvider,
                                                  ObjectProvider<AuthzVersionProvider> versionProvider) {
        return new OpenFgaScopeReader(
                authzService,
                properties,
                cacheProvider.getIfAvailable(),
                versionProvider.getIfAvailable());
    }
}
