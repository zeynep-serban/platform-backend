package com.example.apigateway.security;

import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebFluxSecurity
@Profile("!local & !dev")
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            CookieAwareBearerTokenConverter sseCookieConverter) {
        http
            .cors(cors -> {})
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .exceptionHandling(handler -> handler
                .authenticationEntryPoint((exchange, ex) -> writeJsonError(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "JWT token zorunludur."))
                .accessDeniedHandler((exchange, ex) -> writeJsonError(exchange, HttpStatus.FORBIDDEN, "forbidden", "Yetkiniz bulunmuyor."))
            )
            .authorizeExchange(ex -> ex
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .matchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                .pathMatchers("/api/auth/cookie/**").permitAll()
                // 2026-04-19: theme-registry public CSS-var metadata — frontend
                // theme-context.provider bootstraps BEFORE auth context, so this
                // call goes without Authorization header. Registry data is not
                // sensitive (just CSS variable key→list mappings). Suppress 401.
                .pathMatchers(HttpMethod.GET, "/api/v1/theme-registry", "/api/v1/theme-registry/**").permitAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth
                // Faz 23.6 hardening (Codex thread `019e0494` AGREE iter-1):
                // SSE auth fix. Browsers cannot attach an Authorization
                // header to native EventSource connections, so the
                // /api/v1/notify/inbox/me/stream endpoint accepts the
                // erp_access_token HttpOnly cookie as the bearer source.
                // Every other authenticated route keeps the standard
                // header-only contract — see CookieAwareBearerTokenConverter
                // for the narrow path scope and rationale.
                .bearerTokenConverter(sseCookieConverter)
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
            );
        return http.build();
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        var response = exchange.getResponse();
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.setStatusCode(status);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", code, message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        // Çoklu issuer desteği: virgülle ayrılmış JWK ve issuer listeleri.
        String primaryJwk = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                env("SECURITY_JWT_JWK_SET_URI"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String primaryIssuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                env("SECURITY_JWT_ISSUER"),
                "http://localhost:8081/realms/serban"
        );
        String extraJwks = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uris"),
                env("SECURITY_JWT_JWK_SET_URIS"),
                ""
        );
        String extraIssuers = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuers"),
                env("SECURITY_JWT_ISSUERS"),
                ""
        );
        java.util.List<String> audiences = resolveAudiences();
        java.util.List<String> allowedClientIds = resolveAllowedClientIds();

        List<NimbusReactiveJwtDecoder> decoders = new ArrayList<>();

        // Helper to build validator per issuer
        java.util.function.Function<String, OAuth2TokenValidator<Jwt>> validatorForIssuer = iss -> {
            if (audiences.isEmpty()) {
                return JwtValidators.createDefaultWithIssuer(iss);
            }
            return new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(iss),
                    new AudienceValidator(audiences, allowedClientIds)
            );
        };

        NimbusReactiveJwtDecoder primary = NimbusReactiveJwtDecoder.withJwkSetUri(primaryJwk).build();
        primary.setJwtValidator(validatorForIssuer.apply(primaryIssuer));
        decoders.add(primary);

        if (extraJwks != null && !extraJwks.isBlank()) {
            String[] uris = extraJwks.split(",");
            String[] issuers = extraIssuers != null ? extraIssuers.split(",") : new String[0];
            for (int i = 0; i < uris.length; i++) {
                String uri = uris[i].trim();
                if (uri.isEmpty()) continue;
                NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withJwkSetUri(uri).build();
                String iss = (i < issuers.length && !issuers[i].isBlank()) ? issuers[i].trim() : primaryIssuer;
                d.setJwtValidator(validatorForIssuer.apply(iss));
                decoders.add(d);
            }
        }

        return token -> tryDecode(decoders, token, 0);
    }

    private String firstFromList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int idx = value.indexOf(',');
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
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

    private Mono<Jwt> tryDecode(List<NimbusReactiveJwtDecoder> decoders, String token, int index) {
        if (index >= decoders.size()) {
            // iter-49 A.1 fix: `JwtException` (raw) — Spring Security
            // `JwtReactiveAuthenticationManager.onError` bunu
            // `AuthenticationServiceException` ("system problem") olarak
            // wrap edip 500 döner. Doğru kontrat: `BadJwtException`
            // (subclass of JwtException) — `JwtReactiveAuthenticationManager`
            // bunu `BadCredentialsException` olarak yeniden fırlatır →
            // 401 Unauthorized.
            //
            // Canlı kanıt 2026-04-30: testai bad-token → 500 Internal
            // Server Error, no-token → 401. Tutarsız + client retry
            // semantiği kırık (500 = "server problem" client bekleme +
            // alarm; 401 = "client should refresh token + retry").
            return Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("No suitable decoder accepted the token"));
        }
        return decoders.get(index).decode(token)
                .onErrorResume(ex -> tryDecode(decoders, token, index + 1));
    }

    private java.util.List<String> resolveAudiences() {
        String audienceProp = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                env("SECURITY_JWT_AUDIENCE"),
                ""
        );
        return splitCsv(audienceProp);
    }

    private java.util.List<String> resolveAllowedClientIds() {
        String allowedClientIds = firstNonBlank(
                environment.getProperty("security.jwt.allowed-client-ids"),
                env("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
                "frontend,admin-cli,serban-web,account"
        );
        return splitCsv(allowedClientIds);
    }

    private static java.util.List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private static String env(String key) {
        return env(key, null);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
