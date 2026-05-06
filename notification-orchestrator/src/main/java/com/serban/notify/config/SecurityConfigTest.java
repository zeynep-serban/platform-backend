package com.serban.notify.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test/local permissive security (Faz 23.2 PR-D.3.x).
 *
 * <p>{@link SecurityConfig} guarded by {@code !local & !test} — when those
 * profiles active, SecurityConfig SKIPPED but Spring Security on classpath
 * still applies default secure-all-endpoints chain → 401 everywhere.
 *
 * <p>This config provides permissive chain for test/local: no auth, all
 * requests permitted. Existing tests (Testcontainers integration etc.) run
 * unaffected.
 *
 * <p>Production safety: this bean ONLY activates with `local` or `test`
 * profile; production deployments use `prod` profile → never permissive.
 */
@Configuration
@EnableWebSecurity
@Profile("local | test")
public class SecurityConfigTest {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
