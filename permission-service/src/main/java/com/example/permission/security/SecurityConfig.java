package com.example.permission.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
// STORY-0002: Backend Keycloak JWT Hardening
@Profile("!local & !dev")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   CompositeJwtAuthenticationConverter jwtAuthenticationConverter,
                                                   InternalApiKeyAuthFilter internalApiKeyAuthFilter,
                                                   ImpersonationContextFilter impersonationContextFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpStatus.UNAUTHORIZED, "unauthorized", "JWT token zorunludur."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN, "forbidden", "Bu işlem için yetkiniz bulunmuyor."))
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                );

        http.addFilterBefore(internalApiKeyAuthFilter, BearerTokenAuthenticationFilter.class);
        // Codex iter-27 P0 absorb: jti_session_lookup binding enforcement
        // runs AFTER bearer auth populates SecurityContext so JwtAuthenticationToken
        // is available to the extractor.
        http.addFilterAfter(impersonationContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    private void writeJsonError(HttpServletResponse response,
                                HttpStatus status,
                                String code,
                                String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", code, message);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                environment.getProperty("security.jwt.user-jwk-set-uri"),
                environment.getProperty("security.jwt.jwk-set-uri"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("security.jwt.issuer"),
                "http://localhost:8081/realms/serban"
        );
        Collection<String> audiences = resolveCsv(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "permission-service"
        );
        Collection<String> allowedClientIds = resolveCsv(
                environment.getProperty("security.jwt.allowed-client-ids"),
                environment.getProperty("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
                "frontend,admin-cli,serban-web"
        );
        String secondaryJwkSetUri = firstNonBlank(
                environment.getProperty("security.jwt.secondary-user-jwk-set-uri"),
                environment.getProperty("security.jwt.secondary-jwk-set-uri")
        );
        String secondaryIssuer = firstNonBlank(
                environment.getProperty("security.jwt.secondary-issuer")
        );
        Collection<String> secondaryAudiences = resolveCsv(
                environment.getProperty("security.jwt.secondary-audience")
        );
        Collection<String> secondaryAllowedClientIds = resolveCsv(
                environment.getProperty("security.jwt.secondary-allowed-client-ids")
        );

        JwtDecoder primaryDecoder = buildDecoder(jwkSetUri, issuer, audiences, allowedClientIds);
        if (!StringUtils.hasText(secondaryJwkSetUri)) {
            return primaryDecoder;
        }

        JwtDecoder secondaryDecoder = buildDecoder(
                secondaryJwkSetUri,
                secondaryIssuer,
                secondaryAudiences,
                secondaryAllowedClientIds
        );
        return new FallbackJwtDecoder(List.of(primaryDecoder, secondaryDecoder));
    }

    private JwtDecoder buildDecoder(String jwkSetUri,
                                    String issuer,
                                    Collection<String> audiences,
                                    Collection<String> allowedClientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(buildServiceValidator(issuer, audiences, allowedClientIds));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> buildServiceValidator(String issuer,
                                                            Collection<String> audiences,
                                                            Collection<String> allowedClientIds) {
        java.util.List<OAuth2TokenValidator<Jwt>> validators = new java.util.ArrayList<>();
        if (StringUtils.hasText(issuer)) {
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        if ((audiences != null && !audiences.isEmpty()) || (allowedClientIds != null && !allowedClientIds.isEmpty())) {
            validators.add(new AudienceValidator(audiences, allowedClientIds));
        }
        return new DelegatingOAuth2TokenValidator<>(
                validators.toArray(new OAuth2TokenValidator[0])
        );
    }

    @Bean
    public CompositeJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter serviceAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        serviceAuthoritiesConverter.setAuthoritiesClaimName("perm");
        serviceAuthoritiesConverter.setAuthorityPrefix("PERM_");

        JwtAuthenticationConverter serviceConverter = new JwtAuthenticationConverter();
        serviceConverter.setPrincipalClaimName("svc");
        serviceConverter.setJwtGrantedAuthoritiesConverter(serviceAuthoritiesConverter);

        JwtAuthenticationConverter userConverter = new JwtAuthenticationConverter();
        userConverter.setPrincipalClaimName("sub");
        userConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            java.util.Set<org.springframework.security.core.GrantedAuthority> authorities = new java.util.LinkedHashSet<>();

            // ADR-0012 Phase 3: "permissions" claim removed from JWT.
            // All permission checks now go through OpenFGA via @RequireModule.
            // JWT only carries identity (sub, email, realm_access.roles).

            // "realm_access.roles" (Keycloak standard — identity only, not for authz)
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                java.util.List<String> roles = (java.util.List<String>) realmAccess.get("roles");
                if (roles != null) {
                    // ADR-0012 Phase 3: Only ROLE_ authorities from realm_access.
                    // Hardcoded admin→permission mapping REMOVED.
                    // All fine-grained permissions now via OpenFGA @RequireModule.
                    roles.forEach(r ->
                        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                    );
                }
            }

            return authorities;
        });

        // Varsayılan davranış: kullanıcı token'ı olduğunda user converter, aksi halde service converter
        return new CompositeJwtAuthenticationConverter(serviceConverter, userConverter);
    }

    @Bean
    public InternalApiKeyAuthFilter internalApiKeyAuthFilter() {
        String value = environment.getProperty("security.internal-api-key.value", "");
        String enabledProperty = environment.getProperty("security.internal-api-key.enabled");
        boolean enabled = StringUtils.hasText(enabledProperty)
                ? Boolean.parseBoolean(enabledProperty)
                : StringUtils.hasText(value);
        return new InternalApiKeyAuthFilter(value, enabled);
    }

    private String firstFromList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int idx = value.indexOf(',');
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
    }

    private Collection<String> resolveCsv(String... values) {
        String raw = firstNonBlank(values);
        if (!StringUtils.hasText(raw)) {
            return java.util.List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
