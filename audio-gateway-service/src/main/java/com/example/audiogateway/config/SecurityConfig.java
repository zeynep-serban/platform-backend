package com.example.audiogateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * JWT validation chain — Keycloak SSO realm reuse (auth-service pattern).
 *
 * <p>Fail-closed by default: any non-public endpoint requires a valid JWT.
 * Public: {@code /actuator/health}, {@code /actuator/info}, {@code /actuator/prometheus}.
 *
 * <p>tenantId / userId / roles are derived from JWT claims AFTER validation —
 * NEVER trusted from client payload (Codex {@code 019e879c} explicit RED).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(final ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .anyExchange()
                        .authenticated()
                )
                .oauth2ResourceServer(o -> o.jwt(jwt -> {
                    // Default JwtDecoder picks up spring.security.oauth2.resourceserver.jwt.issuer-uri
                }))
                .build();
    }
}
