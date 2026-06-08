package com.example.user.config;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.RemoteAuthzVersionProvider;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.commonauth.scope.ScopeContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
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
    public AuthenticatedUserLookupService authenticatedUserLookupService(
            JdbcTemplate jdbcTemplate,
            @Value("${authz.user-table:user_service.users}") String userTable,
            @Value("${authz.user-soft-delete-column:deleted_at}") String softDeleteColumn) {
        // Soft-delete (Codex 019ea573, #770 Phase 2): the user-service users
        // table carries a deleted_at tombstone, so a soft-deleted identity
        // must not resolve a numeric userId in the OpenFGA authz context.
        // Other services keep the 2-arg (no soft-delete column) behaviour.
        return new AuthenticatedUserLookupService(jdbcTemplate, userTable, softDeleteColumn);
    }

    @Bean
    public RemoteAuthzVersionProvider remoteAuthzVersionProvider(
            @Value("${permission.service.base-url:http://127.0.0.1:8091}") String baseUrl) {
        return new RemoteAuthzVersionProvider(baseUrl + "/api/v1/authz/version", 5000);
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
    public FilterRegistrationBean<ScopeContextFilter> scopeContextFilter(
            OpenFgaAuthzService authzService,
            OpenFgaProperties props,
            AuthenticatedUserLookupService authenticatedUserLookupService,
            ScopeContextCache scopeContextCache,
            RemoteAuthzVersionProvider remoteAuthzVersionProvider) {
        var reg = new FilterRegistrationBean<>(
                new ScopeContextFilter(authzService, props, authenticatedUserLookupService,
                        scopeContextCache, remoteAuthzVersionProvider)
        );
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return reg;
    }
}
