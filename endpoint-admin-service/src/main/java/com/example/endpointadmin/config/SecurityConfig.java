package com.example.endpointadmin.config;

import com.example.endpointadmin.security.AudienceValidator;
import com.example.endpointadmin.security.DeviceCredentialAuthenticationFilter;
import com.example.endpointadmin.security.DeviceCredentialProvider;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@org.springframework.context.annotation.Profile("!local & !dev")
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain agentSecurityFilterChain(HttpSecurity http,
                                                        DeviceCredentialProvider deviceCredentialProvider) throws Exception {
        DeviceCredentialAuthenticationFilter deviceCredentialAuthenticationFilter =
                new DeviceCredentialAuthenticationFilter(deviceCredentialProvider);
        http
                .securityMatcher("/api/v1/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/agent/enrollments/consume").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(deviceCredentialAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                        JwtDecoder jwtDecoder,
                                                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .securityMatcher("/api/v1/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAnyAuthority("ROLE_ADMIN", "ROLE_ENDPOINT_ADMIN", "SCOPE_endpoint-admin")
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                environment.getProperty("SECURITY_JWT_JWK_SET_URI"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("SECURITY_JWT_ISSUER"),
                "http://localhost:8081/realms/serban"
        );

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(resolveAudiences(), resolveAllowedClientIds())
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            LinkedHashSet<org.springframework.security.core.GrantedAuthority> authorities = new LinkedHashSet<>();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null) {
                    roles.stream()
                            .filter(role -> role != null && !role.isBlank())
                            .map(role -> "ROLE_" + role.trim().toUpperCase())
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                Arrays.stream(scope.split(" "))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .map(item -> "SCOPE_" + item)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }

            return authorities;
        });
        return converter;
    }

    private List<String> resolveAudiences() {
        String audienceCsv = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "endpoint-admin-service"
        );
        return csvToList(audienceCsv);
    }

    private List<String> resolveAllowedClientIds() {
        String clientIdCsv = firstNonBlank(
                environment.getProperty("security.jwt.allowed-client-ids"),
                environment.getProperty("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
                "frontend,admin-cli,serban-web,account"
        );
        return csvToList(clientIdCsv);
    }

    private List<String> csvToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
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
