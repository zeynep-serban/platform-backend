package com.serban.notify.config;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Basit audience doğrulayıcı; token {@code aud} claim'i beklenen değeri
 * içermiyorsa doğrulama başarısız olur. {@code azp} ve {@code client_id}
 * claim'leri için ek allow-list desteği vardır (Keycloak'ın varsayılan
 * davranışında {@code aud} her zaman set edilmez).
 *
 * <p>Faz 23.6 hardening (2026-05-08): permission-service'in
 * {@code com.example.permission.security.AudienceValidator} sınıfının birebir
 * uyarlamasıdır. Aynı pattern api-gateway tarafında da çalışıyor
 * (multi-issuer fallback ile). notification-orchestrator için ayrı bir paket
 * (yeni dependency yok) altında tutulur ve test cluster'da egress hairpin
 * sorununu yaşamayan diğer servislerle aynı JwtDecoder mimarisini
 * benimsemek için kullanılır.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> expectedAudiences;
    private final Set<String> allowedClientIds;

    public AudienceValidator(String expectedAudience) {
        this(expectedAudience == null ? Set.of() : Set.of(expectedAudience), Set.of());
    }

    public AudienceValidator(Collection<String> expectedAudiences, Collection<String> allowedClientIds) {
        this.expectedAudiences = normalize(expectedAudiences);
        this.allowedClientIds = normalize(allowedClientIds);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expectedAudiences.isEmpty() && allowedClientIds.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }

        Collection<String> audiences = token.getAudience();
        if (audiences != null && audiences.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(expectedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }

        String azp = stringClaim(token, "azp");
        if (azp != null && allowedClientIds.contains(azp)) {
            return OAuth2TokenValidatorResult.success();
        }

        String clientId = stringClaim(token, "client_id");
        if (clientId != null && allowedClientIds.contains(clientId)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The required audience is missing",
                null
        );
        return OAuth2TokenValidatorResult.failure(error);
    }

    private static String stringClaim(Jwt token, String claimName) {
        Object claim = token.getClaims().get(claimName);
        if (claim instanceof String value) {
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
