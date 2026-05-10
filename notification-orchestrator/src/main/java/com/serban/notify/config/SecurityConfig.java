package com.serban.notify.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
 *   <li>{@code /api/v1/notify/dlr/**}: permitAll (Faz 23.4 PR-F — provider
 *       webhooks; controller-level shared-secret token gate)</li>
 *   <li>{@code /api/v1/admin/notify/**}: authenticated + ROLE_PRIVACY_OFFICER
 *       (method-level @PreAuthorize on AdminErasureController)</li>
 *   <li>{@code /api/v1/notify/**}: authenticated (intent submission)</li>
 *   <li>others: authenticated</li>
 * </ul>
 *
 * <p>JWT decoder: custom {@link #jwtDecoder()} bean (Faz 23.6 hardening,
 * mirroring permission-service / api-gateway). Issuer comes from
 * {@code SECURITY_JWT_ISSUER} env, JWK URL from
 * {@code SECURITY_JWT_JWK_SET_URI}. JwtAuthenticationConverter maps:
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

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator probe + Prometheus scrape (api-gateway permitAll'da
                // zaten ama defense-in-depth — direct port erişimi için)
                .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                // Faz 23.4 PR-F: provider DLR webhooks (NetGSM, ileride
                // İletimerkezi vs.) — public path; auth controller seviyesinde
                // shared-secret token (X-NetGSM-DLR-Token) ile constant-time
                // compare. Provider Internet'ten POST yapacağı için JWT yok.
                .requestMatchers("/api/v1/notify/dlr/**").permitAll()
                // T1.1.8 (Faz 23.2.A) — public unsubscribe endpoint. Email
                // recipient browser'da link tıklar; HMAC-SHA256 signed token
                // controller seviyesinde verify edilir (UnsubscribeTokenService).
                // JWT yok çünkü subscriber may not have an active session.
                .requestMatchers("/api/v1/notify/unsubscribe").permitAll()
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

    /**
     * Custom JwtDecoder bean — Faz 23.6 hardening (2026-05-08).
     *
     * <h3>Why we don't rely on Spring Boot auto-config</h3>
     *
     * <p>Spring Boot's {@code OAuth2ResourceServerJwtConfiguration} prefers
     * {@code NimbusJwtDecoder.withIssuerLocation()} whenever
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is non-null,
     * which forces a {@code .well-known/openid-configuration} discovery call
     * on first decode. The platform clusters have a {@code default-deny-egress}
     * NetworkPolicy and no public-domain hairpin routing, so a public issuer
     * URL (the natural value because the token's {@code iss} claim is the
     * Keycloak frontend URL) times out and surfaces as
     * {@code JwtDecoderInitializationException → HTTP 500} on every JWT
     * request. Live evidence (testai 2026-05-08): 3088 stream + 26 inbox 500s
     * before this change.
     *
     * <h3>Pattern</h3>
     *
     * <p>This class mirrors the long-standing
     * {@code permission-service / SecurityConfig.jwtDecoder()} bean (and the
     * {@code api-gateway / SecurityConfig.jwtDecoder()} reactive variant). The
     * shape is:
     * <ul>
     *   <li>{@link NimbusJwtDecoder#withJwkSetUri(String)} — internal Keycloak
     *       JWK URL only ({@code http://keycloak:8080/...}); resolves on
     *       cluster DNS in milliseconds, never triggers auto-discovery.</li>
     *   <li>{@link JwtValidators#createDefaultWithIssuer(String)} — issuer
     *       claim assertion against the public issuer URL ({@code iss} claim
     *       must match exactly). Token signature is validated against the
     *       JWK set, while the {@code iss} claim assertion is a string
     *       comparison — no network call.</li>
     *   <li>{@link AudienceValidator} — {@code aud} claim allow-list with
     *       {@code azp} / {@code client_id} fallback (Keycloak {@code aud}
     *       behaviour).</li>
     * </ul>
     *
     * <h3>Configuration sources</h3>
     *
     * <p>Property resolution mirrors permission-service for operational
     * parity (the same overlay env vars work):
     * <ul>
     *   <li>{@code SECURITY_JWT_JWK_SET_URI} — required, internal Keycloak
     *       JWK URL.</li>
     *   <li>{@code SECURITY_JWT_ISSUER} (or
     *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}) —
     *       required for issuer claim assertion (public Keycloak URL).</li>
     *   <li>{@code SECURITY_JWT_AUDIENCE} (or
     *       {@code spring.security.oauth2.resourceserver.jwt.audiences}) —
     *       comma-separated audience list.</li>
     *   <li>{@code SECURITY_AUTH_ALLOWED_CLIENT_IDS} — comma-separated
     *       Keycloak {@code azp} / {@code client_id} allow-list (defaults
     *       cover {@code frontend, admin-cli, serban-web}).</li>
     * </ul>
     *
     * <p>Local profile ({@code local} / {@code test}) bypasses this bean
     * entirely via {@link Profile @Profile("!local & !test")} — Testcontainers
     * fixtures use {@code @MockBean JwtDecoder} or skip security via
     * {@link com.serban.notify.config.SecurityConfigTest}.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        // Codex `019e0649` REVISE iter-1 absorb (#2): direct env vars are
        // read FIRST so the previous application.yml `${...:#{null}}`
        // sentinel — if it ever re-appears in a future drift — cannot
        // shadow the canonical override. The literal "#{null}" string
        // would otherwise satisfy `StringUtils.hasText` and produce a
        // bogus issuer URL. `firstNonBlank` itself also rejects the
        // sentinel string defensively.
        String jwkSetUri = firstNonBlank(
                environment.getProperty("SECURITY_JWT_JWK_SET_URI"),
                environment.getProperty("security.jwt.jwk-set-uri"),
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
                environment.getProperty("SECURITY_JWT_ISSUER"),
                environment.getProperty("security.jwt.issuer"),
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                "http://localhost:8081/realms/serban"
        );
        Collection<String> audiences = resolveCsv(
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("spring.application.name"),
                "notification-orchestrator"
        );
        Collection<String> allowedClientIds = resolveCsv(
                environment.getProperty("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
                environment.getProperty("security.jwt.allowed-client-ids"),
                "frontend,admin-cli,serban-web"
        );

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(buildServiceValidator(issuer, audiences, allowedClientIds));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> buildServiceValidator(
            String issuer,
            Collection<String> audiences,
            Collection<String> allowedClientIds) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        if (StringUtils.hasText(issuer)) {
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        if ((audiences != null && !audiences.isEmpty())
                || (allowedClientIds != null && !allowedClientIds.isEmpty())) {
            validators.add(new AudienceValidator(audiences, allowedClientIds));
        }
        return new DelegatingOAuth2TokenValidator<>(
                validators.toArray(new OAuth2TokenValidator[0]));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            String trimmed = value.trim();
            // Codex `019e0649` REVISE iter-1 absorb: defensive guard
            // against a `${VAR:#{null}}` placeholder that Spring's
            // PropertySourcesPropertyResolver did NOT expand to a real
            // null. Treat the literal SpEL sentinel as "missing" so a
            // future application.yml drift cannot poison the validator.
            if ("#{null}".equals(trimmed) || "null".equalsIgnoreCase(trimmed)) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private static Collection<String> resolveCsv(String... candidates) {
        if (candidates == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) continue;
            Arrays.stream(candidate.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(result::add);
            if (!result.isEmpty()) {
                // Mirror permission-service: first non-blank source wins.
                break;
            }
        }
        return result;
    }

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
