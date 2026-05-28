package com.example.endpointadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Faz 22.3 — mTLS self-enroll security filter chain (ADR-0029 backend layer).
 *
 * <p>Authn happens at the TLS layer (gateway / ingress terminates and verifies
 * the AD CS client cert chain). At the Spring Security layer we only need to
 * permit the request so the controller can extract the cert from the standard
 * {@code jakarta.servlet.request.X509Certificate} request attribute.
 *
 * <p>Ordered <strong>before</strong> {@link SecurityConfig} so the
 * {@code /api/v1/endpoint-agent/**} matcher wins over the generic resource
 * server chain that would otherwise require a JWT.
 */
@Configuration
@Profile("!local & !dev")
public class MtlsSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain endpointAgentMtlsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/endpoint-agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/endpoint-agent/endpoint-enrollments/auto").permitAll()
                        .anyRequest().denyAll()
                );
        return http.build();
    }
}
