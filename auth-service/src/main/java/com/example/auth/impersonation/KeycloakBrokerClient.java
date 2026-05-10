package com.example.auth.impersonation;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Base64;

/**
 * Keycloak Token Exchange (RFC 8693 v1) client — User Impersonation v1.
 *
 * <p>Spike-2 kanıtlanmış akış (PASS_JTI_SESSION_LOOKUP):
 *
 * <pre>{@code
 * POST /realms/{realm}/protocol/openid-connect/token
 *   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
 *   client_id=impersonation-broker
 *   client_secret=<broker_secret>
 *   subject_token=<admin_jwt>
 *   requested_subject=<target_user_uuid>
 *   audience=frontend
 * }</pre>
 *
 * <p>Codex 019e0dfb iter-26 contract: KC exchange başarılı olsa bile
 * permission-service DB session insert OK olmadan exchanged token
 * frontend'e DÖNMEMELİ. Bu client sadece exchange yapar; controller
 * akışında DB insert sonrasına token return ertelenir.
 */
@Component
public class KeycloakBrokerClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakBrokerClient.class);
    private static final String GRANT_TYPE_TOKEN_EXCHANGE =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    /** Codex iter-27 P1 absorb: KC exchange hang prevention. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String realmTokenUrl;
    private final String brokerClientId;
    private final String brokerClientSecret;
    private final String exchangeAudience;

    public KeycloakBrokerClient(
            @Qualifier("plainWebClientBuilder") WebClient.Builder builder,
            @Value("${auth.impersonation.keycloak-token-url}") String realmTokenUrl,
            @Value("${auth.impersonation.broker-client-id:impersonation-broker}") String brokerClientId,
            @Value("${auth.impersonation.broker-client-secret:}") String brokerClientSecret,
            @Value("${auth.impersonation.exchange-audience:frontend}") String exchangeAudience) {
        this.webClient = builder.build();
        this.realmTokenUrl = realmTokenUrl;
        this.brokerClientId = brokerClientId;
        this.brokerClientSecret = brokerClientSecret;
        this.exchangeAudience = exchangeAudience;
    }

    /**
     * RFC 8693 token exchange — admin's JWT → target user's JWT.
     *
     * @param adminJwt SuperAdmin token (subject_token)
     * @param targetUserSubject target KC user UUID (requested_subject)
     * @return ExchangeResult with access_token + decoded claims
     * @throws TokenExchangeException on Keycloak error or invalid response
     */
    public ExchangeResult exchange(String adminJwt, String targetUserSubject) {
        if (brokerClientSecret == null || brokerClientSecret.isBlank()) {
            throw new TokenExchangeException("Broker client secret not configured (auth.impersonation.broker-client-secret)");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE_TOKEN_EXCHANGE);
        form.add("client_id", brokerClientId);
        form.add("client_secret", brokerClientSecret);
        form.add("subject_token", adminJwt);
        form.add("requested_subject", targetUserSubject);
        form.add("audience", exchangeAudience);

        try {
            JsonNode response = webClient.post()
                    .uri(realmTokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            if (response == null || !response.hasNonNull("access_token")) {
                throw new TokenExchangeException(
                        "Keycloak exchange returned no access_token: " + response);
            }

            String accessToken = response.get("access_token").asText();
            long expiresIn = response.has("expires_in") ? response.get("expires_in").asLong() : 3600L;
            DecodedClaims claims = decodeClaims(accessToken);
            return new ExchangeResult(accessToken, expiresIn, claims);
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.warn("Keycloak token exchange failed: status={} body={}", e.getStatusCode(), body);
            throw new TokenExchangeException(
                    "Keycloak token exchange failed (" + e.getStatusCode() + "): " + body);
        } catch (TokenExchangeException e) {
            // Internal validation throws (invalid response) — propagate untouched.
            throw e;
        } catch (RuntimeException e) {
            // Codex iter-27 P0 absorb: timeout / DNS / connection-reset /
            // any non-WebClientResponseException must NOT escape as raw
            // RuntimeException, otherwise the controller's
            // TokenExchangeException catch misses and IMPERSONATION_FAILED
            // audit row never gets written.
            log.warn("Keycloak token exchange unexpected error for target_subject_present={}: {}",
                    targetUserSubject != null, e.getMessage());
            throw new TokenExchangeException(
                    "Keycloak token exchange failed (network/timeout): " + e.getMessage());
        }
    }

    /**
     * JWT payload (middle part) base64url decode + extract critical claims.
     *
     * <p>Codex iter-19 binding contract: iss/jti/sid/sub/exp şart.
     */
    private DecodedClaims decodeClaims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new TokenExchangeException("Invalid JWT structure (parts < 2)");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]));
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode payload = mapper.readTree(json);

            String iss = textOrNull(payload, "iss");
            String jti = textOrNull(payload, "jti");
            String sid = textOrNull(payload, "sid");
            String sub = textOrNull(payload, "sub");
            String azp = textOrNull(payload, "azp");
            long exp = payload.has("exp") ? payload.get("exp").asLong() : 0L;
            String email = textOrNull(payload, "email");

            if (iss == null || jti == null || sid == null || sub == null) {
                throw new TokenExchangeException(
                        "Exchanged token missing required claims (iss/jti/sid/sub)");
            }
            return new DecodedClaims(iss, jti, sid, sub, azp, exp, email);
        } catch (Exception e) {
            throw new TokenExchangeException("JWT payload decode failed: " + e.getMessage());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /**
     * Exchange result — exchanged token + decoded binding claims.
     */
    public record ExchangeResult(
            String accessToken,
            long expiresInSeconds,
            DecodedClaims claims) {
    }

    /**
     * Decoded JWT payload — claim binding for permission-service session row.
     */
    public record DecodedClaims(
            String issuer,
            String jti,
            String sid,
            String subject,
            String authorizedParty,
            long expEpoch,
            String email) {
    }

    public static class TokenExchangeException extends RuntimeException {
        public static final String ERROR_CODE = "TOKEN_EXCHANGE_FAILED";

        public TokenExchangeException(String message) {
            super(message);
        }

        public String errorCode() {
            return ERROR_CODE;
        }
    }
}
