package com.example.auth.impersonation.wiremock;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised test token / credential placeholders for the impersonation
 * WireMock IT fixture (Codex {@code 019e2022} strategy AGREE'd, Faz 1).
 *
 * <p>No real credential literals live in test sources; the IT class
 * imports the constants below and {@link #buildFakeExchangedJwt} composes
 * JWT-shaped strings on demand. Runtime overrides come from environment
 * variables ({@code IT_BROKER_SECRET}, {@code IT_INTERNAL_KEY}) when an
 * operator wants to parameterise a run.
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** Stand-in for any bearer the IT path forwards; never decoded. */
    public static final String ADMIN_JWT_PLACEHOLDER = "<TEST_TOKEN_PLACEHOLDER>";

    /** Stand-in for the service-token UserServiceClient mints. */
    public static final String SERVICE_TOKEN_PLACEHOLDER = "<TEST_TOKEN_PLACEHOLDER>";

    /** Default broker client secret used by KeycloakBrokerClient at startup. */
    public static final String DEFAULT_BROKER_SECRET = "wiremock-it";

    /** Default internal API key used by the permission-service clients. */
    public static final String DEFAULT_INTERNAL_KEY = "wiremock-it";

    /** Broker secret read from env (overridable per CI run). */
    public static String brokerSecret() {
        String env = System.getenv("IT_BROKER_SECRET");
        return env != null && !env.isBlank() ? env : DEFAULT_BROKER_SECRET;
    }

    /** Internal API key read from env (overridable per CI run). */
    public static String internalKey() {
        String env = System.getenv("IT_INTERNAL_KEY");
        return env != null && !env.isBlank() ? env : DEFAULT_INTERNAL_KEY;
    }

    /**
     * Compose a JWT-shaped string ({@code header.payload.sig}) whose
     * middle segment is a base64url-encoded JSON object containing the
     * supplied claims. {@code KeycloakBrokerClient.decodeClaims()} reads
     * the middle segment only; the signature segment is a dummy.
     */
    public static String buildFakeExchangedJwt(
            String iss, String jti, String sid, String sub,
            String azp, long expEpoch, String email) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "none");
            header.put("typ", "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", iss);
            payload.put("jti", jti);
            payload.put("sid", sid);
            payload.put("sub", sub);
            payload.put("azp", azp);
            payload.put("exp", expEpoch);
            if (email != null) {
                payload.put("email", email);
            }

            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String h = enc.encodeToString(mapper.writeValueAsBytes(header));
            String p = enc.encodeToString(mapper.writeValueAsBytes(payload));
            // Signature segment is a stable non-cryptographic marker.
            String s = enc.encodeToString("not-a-signature".getBytes());
            return h + "." + p + "." + s;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build fake JWT for IT fixture", e);
        }
    }
}
