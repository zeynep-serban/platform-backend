package com.serban.notify.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Security config (Faz 23.2 PR-D.3.x — Codex 019dfdec follow-up absorb).
 *
 * <p>Re-introduces Spring Security after PR-B's intentional removal (deferred
 * to "PR-C/follow-up"). PR-D.3.x activates @PreAuthorize on AdminErasureController
 * with ROLE_PRIVACY_OFFICER gate.
 *
 * <p>Auth contract:
 * <ul>
 *   <li>{@code /actuator/health} + {@code /actuator/prometheus}: permitAll
 *       (probe + metrics scrape no-auth)</li>
 *   <li>{@code /api/v1/admin/notify/**}: authenticated + ROLE_PRIVACY_OFFICER
 *       (method-level @PreAuthorize on AdminErasureController)</li>
 *   <li>{@code /api/v1/notify/**}: authenticated (intent submission)</li>
 *   <li>others: authenticated</li>
 * </ul>
 *
 * <p>JWT decoder: Keycloak realm `serban` issuer-uri (overridable via
 * {@code SECURITY_JWT_ISSUER_URI}). JwtAuthenticationConverter maps:
 * <ul>
 *   <li>{@code permissions} claim → PERM_* authorities</li>
 *   <li>{@code realm_access.roles} → ROLE_* authorities (Keycloak standard)</li>
 * </ul>
 *
 * <p>Profile gating: {@code !local & !test} — local + Testcontainers profile'da
 * security devre dışı (test fixture'lar @WithMockUser / @WithJwt kullanır;
 * pure unit + integration tests SecurityConfig'i bypass eder).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // @PreAuthorize aktif
@Profile("!local & !test")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator probe + Prometheus scrape (api-gateway permitAll'da
                // zaten ama defense-in-depth — direct port erişimi için)
                .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                // /api/v1/admin/notify/** — @PreAuthorize method-level (AdminErasureController)
                // Burada path-level authenticated() yeterli; role gate method seviyesinde.
                .requestMatchers("/api/v1/admin/notify/**").authenticated()
                // /api/v1/notify/** intent submission API
                .requestMatchers("/api/v1/notify/**").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                .decoder(jwtDecoder)
                .jwtAuthenticationConverter(notifyJwtAuthenticationConverter())
            ));
        return http.build();
    }

    // JwtDecoder bean: Spring Boot auto-config (spring-boot-starter-oauth2-resource-server)
    // creates it from `spring.security.oauth2.resourceserver.jwt.issuer-uri` property.
    // No explicit @Bean here — auto-config respects @ConditionalOnMissingBean and tests
    // can @Primary override.

    /**
     * Map JWT claims to Spring Security authorities.
     *
     * <p>Two claim sources:
     * <ul>
     *   <li>{@code permissions} (frontend-injected by permission-service) → PERM_*</li>
     *   <li>{@code realm_access.roles} (Keycloak standard) → ROLE_*</li>
     * </ul>
     */
    @Bean
    public JwtAuthenticationConverter notifyJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();

            // 1. permissions claim
            List<String> permissions = jwt.getClaimAsStringList("permissions");
            if (permissions != null) {
                permissions.forEach(p ->
                    authorities.add(new SimpleGrantedAuthority(p)));
            }

            // 2. realm_access.roles (Keycloak)
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                Object roles = realmAccess.get("roles");
                if (roles instanceof List<?> roleList) {
                    for (Object role : roleList) {
                        if (role instanceof String roleStr) {
                            // Prefix with ROLE_ (Spring convention) if not already
                            String authority = roleStr.startsWith("ROLE_")
                                ? roleStr : "ROLE_" + roleStr;
                            authorities.add(new SimpleGrantedAuthority(authority));
                        }
                    }
                }
            }

            return authorities;
        });
        return converter;
    }
}
