package com.example.schema.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                    // Codex 019dda1c iter-31: master-data internal endpoint
                    // (gateway-private, controller-side X-Internal-Api-Key
                    // gate). iter-29..30e tried bare String matchers which
                    // Spring Boot 3.x routes through MvcRequestMatcher; the
                    // /master-data/** glob did NOT extend to nested paths
                    // like /master-data/diagnostic/{kind} in live test
                    // (smoke returned 401 even with two explicit matchers).
                    //
                    // iter-31 fix: force AntPathRequestMatcher so the glob
                    // is path-only (no MVC handler-mapping pre-filter). One
                    // matcher now covers the entire master-data subtree,
                    // including /diagnostic/{kind}. Path is in-cluster only;
                    // gateway does not surface it.
                    .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/schema/master-data/**")).permitAll()
                    // Phase 2 Program 8a (2026-05-07): Schema Truth Integration
                    // Tier 1 — report-service SchemaServiceClient internal call.
                    // Codex iter-1 §3 absorb: report-service uses
                    // X-Internal-Api-Key (matches existing master-data pattern);
                    // controller-level guard with empty-key dev/test fallback
                    // preserves existing JWT-only frontend access. NetworkPolicy
                    // ensures in-cluster only.
                    .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/schema/snapshot")).permitAll()
                    .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3008}")
    private List<String> allowedOrigins;

    private CorsConfigurationSource corsSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
