package com.example.auth.impersonation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * SuperAdmin authority resolver — Codex 019e0dfb iter-27 P0 absorb.
 *
 * <p>JWT-based @PreAuthorize unreliable (auth-service @EnableMethodSecurity
 * yok + KC realm role converter eksik). Permission-service /api/v1/authz/me
 * authoritative authz source kullanırız.
 *
 * <p>Response contains `superAdmin: true|false` field; this client checks it.
 */
@Component
public class SuperAdminAuthority {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminAuthority.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public SuperAdminAuthority(
            @Qualifier("plainWebClientBuilder") WebClient.Builder builder,
            @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Check superAdmin authority via /api/v1/authz/me (authoritative source).
     *
     * @param userId admin user id (for log)
     * @param adminJwt full admin JWT to forward
     * @return true if superAdmin=true; false if not or on any failure
     */
    public boolean isSuperAdmin(Long userId, String adminJwt) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = webClient.get()
                    .uri("/api/v1/authz/me")
                    .headers(h -> h.setBearerAuth(adminJwt))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();

            if (body == null) {
                log.warn("authz/me returned null for user={}", userId);
                return false;
            }
            Object flag = body.get("superAdmin");
            boolean isSuperAdmin = flag instanceof Boolean b && b;
            if (!isSuperAdmin) {
                log.debug("user_id={} superAdmin=false (authz/me)", userId);
            }
            return isSuperAdmin;
        } catch (WebClientResponseException e) {
            log.warn("authz/me failed for user={} status={}: {}",
                    userId, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("authz/me unexpected error for user={}: {}", userId, e.getMessage());
            return false;
        }
    }
}
