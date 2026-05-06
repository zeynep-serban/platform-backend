package com.serban.notify.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AuthzClient — HTTP client to permission-service internal authorize endpoint
 * (Codex 019dfaaa PR5 Q4 + lock-in #1, #2 absorb).
 *
 * <p>Codex absorb:
 * <ul>
 *   <li>Q4 zero-cache (PR5 scope; PR6+'da Caffeine 30s + version-aware)</li>
 *   <li>Lock-in #1: raw principal API (NOT JWT-resolved userId);
 *       template:{id}#can_receive@subscriber:{id} tuple model</li>
 *   <li>Lock-in #2: X-Internal-Api-Key header (matching InternalApiKeyAuthFilter
 *       pattern in permission-service)</li>
 * </ul>
 *
 * <p>Endpoint: POST {permission-service}/api/v1/internal/authz/check
 * <pre>
 * Request:
 *   X-Internal-Api-Key: ***
 *   { "principal_type": "subscriber", "principal_id": "1204",
 *     "relation": "can_receive", "object_type": "template",
 *     "object_id": "auth-password-reset" }
 * Response 200:
 *   { "allowed": true, "reason": "tuple_match" }
 * </pre>
 *
 * <p>Fail-closed semantics: HTTP error / timeout → DENY ("authz_unreachable")
 * — security-critical (default-allow on auth fail = vuln class).
 */
@Component
public class AuthzClient {

    private static final Logger log = LoggerFactory.getLogger(AuthzClient.class);
    private static final int CONNECT_TIMEOUT_SEC = 3;
    private static final int RESPONSE_TIMEOUT_SEC = 5;

    private final ObjectMapper objectMapper;
    private final String permissionServiceUrl;
    private final String internalApiKey;

    public AuthzClient(
        ObjectMapper objectMapper,
        @Value("${notify.authz.permission-service.url:http://permission-service:8080}")
            String permissionServiceUrl,
        @Value("${notify.authz.internal-api-key:dev-only-key-not-for-production}")
            String internalApiKey
    ) {
        this.objectMapper = objectMapper;
        this.permissionServiceUrl = permissionServiceUrl;
        this.internalApiKey = internalApiKey;
    }

    /**
     * Check authorization for principal-relation-object tuple.
     *
     * <p>Fail-closed: HTTP error → DENY (NOT default-allow). Latency budget:
     * 5s response timeout; per-delivery overhead ≈ 50ms p50.
     *
     * @param principalType "subscriber" | "external"
     * @param principalId   subscriber numeric id OR external email_hash
     * @param relation      OpenFGA relation (e.g., "can_receive")
     * @param objectType    OpenFGA object type (e.g., "template")
     * @param objectId      OpenFGA object id (e.g., "auth-password-reset")
     * @return AuthzDecision with allowed + reason
     */
    public AuthzDecision check(String principalType, String principalId,
                                String relation, String objectType, String objectId) {
        String url = permissionServiceUrl + "/api/v1/internal/authz/check";

        Map<String, Object> body = Map.of(
            "principal_type", principalType,
            "principal_id", principalId,
            "relation", relation,
            "object_type", objectType,
            "object_id", objectId
        );

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            post.setHeader("X-Internal-Api-Key", internalApiKey);
            String json = objectMapper.writeValueAsString(body);
            post.setEntity(new StringEntity(json, StandardCharsets.UTF_8));

            return client.execute(post, response -> {
                int code = response.getCode();
                if (code != 200) {
                    log.warn("authz HTTP non-200 (fail-closed DENY): code={} principal={}:{} obj={}:{}",
                        code, principalType, principalId, objectType, objectId);
                    return AuthzDecision.deny("authz_http_" + code);
                }
                String respBody = new String(response.getEntity().getContent().readAllBytes(),
                    StandardCharsets.UTF_8);
                JsonNode node = objectMapper.readTree(respBody);
                boolean allowed = node.path("allowed").asBoolean(false);
                String reason = node.path("reason").asText("unknown");
                return new AuthzDecision(allowed, reason);
            });
        } catch (IOException e) {
            log.warn("authz IOException (fail-closed DENY): {} principal={}:{} obj={}:{}",
                e.getMessage(), principalType, principalId, objectType, objectId);
            return AuthzDecision.deny("authz_unreachable");
        }
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    /** Authz decision (immutable). */
    public record AuthzDecision(boolean allowed, String reason) {
        public static AuthzDecision allow(String reason) { return new AuthzDecision(true, reason); }
        public static AuthzDecision deny(String reason) { return new AuthzDecision(false, reason); }
    }
}
