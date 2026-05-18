package com.example.user.config;

import com.example.user.security.JwtAutoProvisionGate;
import com.example.user.security.KeycloakUserAutoProvisionFilter;
import com.example.user.service.UserService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wiring for the Keycloak user lazy-provision bridge.
 *
 * <p>Registers {@link KeycloakUserAutoProvisionFilter} so it runs AFTER
 * the Spring Security filter chain (which populates the JWT
 * {@code Authentication}) and BEFORE
 * {@code com.example.commonauth.scope.ScopeContextFilter}.
 *
 * <h2>Ordering rationale</h2>
 * <ul>
 *   <li>The Spring Security {@code FilterChainProxy} is registered by
 *       Spring Boot at order {@code SecurityProperties.DEFAULT_FILTER_ORDER}
 *       ({@code -100}).</li>
 *   <li>{@code ScopeContextFilter} is registered (in
 *       {@link OpenFgaAuthzConfig}) at order
 *       {@code Ordered.LOWEST_PRECEDENCE - 10}.</li>
 *   <li>This filter uses {@code Ordered.LOWEST_PRECEDENCE - 20}, which is
 *       a smaller number than {@code -10} — therefore it runs earlier
 *       than {@code ScopeContextFilter} — while still being far larger
 *       than {@code -100}, so it runs after Spring Security. Net effect:
 *       JWT validated → auto-provision → scope resolution sees the new
 *       row on the first request.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AutoProvisionProperties.class)
public class AutoProvisionConfig {

    /**
     * Order for the auto-provision filter — after Spring Security
     * ({@code -100}), before {@code ScopeContextFilter}
     * ({@code LOWEST_PRECEDENCE - 10}).
     */
    static final int AUTO_PROVISION_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 20;

    @Bean
    public FilterRegistrationBean<KeycloakUserAutoProvisionFilter> keycloakUserAutoProvisionFilter(
            JwtAutoProvisionGate autoProvisionGate,
            UserService userService) {
        var registration = new FilterRegistrationBean<>(
                new KeycloakUserAutoProvisionFilter(autoProvisionGate, userService));
        // Same surface as ScopeContextFilter — all /api/* traffic; the
        // filter's own shouldNotFilter() excludes internal/public paths.
        registration.addUrlPatterns("/api/*");
        registration.setOrder(AUTO_PROVISION_FILTER_ORDER);
        registration.setName("keycloakUserAutoProvisionFilter");
        return registration;
    }
}
