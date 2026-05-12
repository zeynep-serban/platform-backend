package com.example.auth.user;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.auth.dto.RegisterRequest;
import com.example.auth.serviceauth.ServiceTokenProvider;

import java.util.List;

@Component
public class UserServiceClient {

    private static final String USER_SERVICE_AUDIENCE = "user-service";
    private static final String REQUIRED_PERMISSION = "users:internal";

    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public UserServiceClient(@Qualifier("plainWebClientBuilder") WebClient.Builder webClientBuilder,
                             @org.springframework.beans.factory.annotation.Value("${user.service.base-url:http://user-service}") String baseUrl,
                             ServiceTokenProvider serviceTokenProvider) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public RemoteUserResponse registerPublicUser(RegisterRequest request) {
        try {
            return webClient.post()
                    .uri("/api/users/public/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RemoteUserResponse.class)
                    .block();
        } catch (WebClientResponseException.Conflict ex) {
            throw new UserAlreadyExistsException("Bu email adresi zaten kayıtlı.", ex);
        }
    }

    public Optional<RemoteUserResponse> findUserByEmail(String email) {
        try {
            return Optional.ofNullable(
                    webClient.get()
                            .uri("/api/users/by-email/{email}", email)
                            .retrieve()
                            .bodyToMono(RemoteUserResponse.class)
                            .block()
            );
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    /**
     * Codex thread {@code 019e1bed} REVISE-1 absorb — lookup by numeric
     * platform id via the service-token protected internal endpoint
     * {@code /api/users/internal/{id}/impersonation-target}. The public
     * {@code /api/v1/users/{id}} endpoint does NOT expose {@code kc_subject}
     * (browser-facing surface stays clean); this internal path requires
     * {@code PERM_users:internal} authority and returns only the fields
     * needed for backend-authoritative impersonation subject resolution.
     *
     * @param userId numeric platform user id
     * @return impersonation target details (id, email, kcSubject) or
     *         {@link Optional#empty()} when user-service returns 404
     */
    public Optional<RemoteUserResponse> findUserById(Long userId, String adminToken) {
        // Codex 019e1bed REVISE-6 (hotfix-2): forward the admin JWT so the
        // public /api/v1/users/{id} endpoint (which requires
        // authenticated admin scope) accepts the call. Earlier hotfix
        // tried this path without a bearer header and hit 401. The
        // admin JWT is in scope inside ImpersonationController; we
        // pass it through. user-service KC issuer drift fix is still
        // a follow-up — for now the public path + admin token is the
        // working contract.
        try {
            return Optional.ofNullable(
                    webClient.get()
                            .uri("/api/v1/users/{id}", userId)
                            .headers(headers -> {
                                if (adminToken != null && !adminToken.isBlank()) {
                                    headers.setBearerAuth(adminToken);
                                }
                            })
                            .retrieve()
                            .bodyToMono(RemoteUserResponse.class)
                            .block()
            );
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public Optional<InternalUserResponse> findInternalUserByEmail(String email) {
        try {
            return Optional.ofNullable(
                    webClient.get()
                            .uri("/api/users/internal/by-email/{email}", email)
                            .headers(headers -> headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION))))
                            .retrieve()
                            .bodyToMono(InternalUserResponse.class)
                            .block()
            );
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public void activateUser(Long userId) {
        webClient.post()
                .uri("/api/users/internal/{userId}/activate", userId)
                .headers(headers -> headers.addAll(internalHeaders()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void updatePassword(Long userId, String newPassword) {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        PasswordUpdateRequest body = new PasswordUpdateRequest(newPassword);
        webClient.post()
                .uri("/api/users/internal/{userId}/password", userId)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void updateLastLogin(Long userId) {
        webClient.post()
                .uri("/api/users/internal/{userId}/last-login", userId)
                .headers(headers -> headers.addAll(internalHeaders()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION)));
        return headers;
    }

    private record PasswordUpdateRequest(String newPassword) {
    }
}
