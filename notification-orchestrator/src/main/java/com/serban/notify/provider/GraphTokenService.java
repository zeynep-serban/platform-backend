package com.serban.notify.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GraphTokenService — Microsoft Graph OAuth 2.0 client_credentials flow.
 *
 * <p>Faz 23 A8 — Microsoft Graph REST API mail adapter. Ana use case:
 * outbound TCP 587 (SMTP submission) ISP/datacenter level block durumunda
 * port 443 HTTPS üzerinden Office 365 mail dispatch.
 *
 * <p>OAuth flow (RFC 6749 §4.4 client_credentials grant):
 * <ol>
 *   <li>POST {@code https://login.microsoftonline.com/{tenant_id}/oauth2/v2.0/token}</li>
 *   <li>{@code grant_type=client_credentials} + client_id + client_secret +
 *       {@code scope=https://graph.microsoft.com/.default}</li>
 *   <li>Response: {@code access_token} (Bearer) + {@code expires_in} (typically 3600s)</li>
 * </ol>
 *
 * <p>Token cache:
 * <ul>
 *   <li>In-memory single-instance ({@link AtomicReference} for thread safety)</li>
 *   <li>Refresh 5 min before expiry (token TTL - 300s)</li>
 *   <li>Fail-closed on refresh fail (exception propagates to adapter)</li>
 * </ul>
 *
 * <p>Azure AD App Registration prerequisites (operator action):
 * <ul>
 *   <li>App Registration → API permissions → Mail.Send (Application permission)</li>
 *   <li>Admin consent granted</li>
 *   <li>Client secret created (Vault prod seed: graph_client_secret)</li>
 *   <li>Tenant ID + Client ID from app registration overview</li>
 * </ul>
 *
 * <p>Refs:
 * <ul>
 *   <li>https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-client-creds-grant-flow</li>
 *   <li>https://learn.microsoft.com/en-us/graph/auth-v2-service</li>
 *   <li>RFC 6749 §4.4 Client Credentials Grant</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "notify.adapters.graph.enabled", havingValue = "true")
public class GraphTokenService {

    private static final Logger log = LoggerFactory.getLogger(GraphTokenService.class);
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final int REFRESH_BUFFER_SECONDS = 300;  // refresh 5 min before expiry

    /**
     * HTTP timeout config (Codex iter P1 absorb 2026-05-11).
     * Adapter'ın varlık sebebi outbound TCP 587 ISP block timeout'a karşı bypass.
     * Graph/AAD DNS/TLS/TCP stall'ında bounded failure → adapter RETRY.
     */
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(10);
    private static final Timeout CONNECTION_REQUEST_TIMEOUT = Timeout.ofSeconds(3);

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<CachedToken> cache = new AtomicReference<>();
    private Clock clock = Clock.systemUTC();

    public GraphTokenService(
        @Value("${notify.adapters.graph.tenant-id}") String tenantId,
        @Value("${notify.adapters.graph.client-id}") String clientId,
        @Value("${notify.adapters.graph.client-secret}") String clientSecret
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("notify.adapters.graph.tenant-id required");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("notify.adapters.graph.client-id required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("notify.adapters.graph.client-secret required");
        }
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        log.info("GraphTokenService initialized: tenantId={} clientId-prefix={}***",
            tenantId.substring(0, Math.min(8, tenantId.length())),
            clientId.substring(0, Math.min(8, clientId.length())));
    }

    /** Setter for deterministic tests (Clock.fixed). */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Get a valid Microsoft Graph access token. Cached + auto-refresh.
     *
     * @return Bearer access_token (use as "Authorization: Bearer <token>")
     * @throws GraphTokenException on OAuth fetch failure
     */
    public String getAccessToken() {
        CachedToken cached = cache.get();
        Instant now = clock.instant();
        if (cached != null && cached.isStillValid(now)) {
            return cached.token;
        }
        // Refresh (fail-closed)
        CachedToken refreshed = fetchToken();
        cache.set(refreshed);
        return refreshed.token;
    }

    private CachedToken fetchToken() {
        String url = String.format(
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenantId);
        HttpPost post = new HttpPost(url);

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "client_credentials"));
        params.add(new BasicNameValuePair("client_id", clientId));
        params.add(new BasicNameValuePair("client_secret", clientSecret));
        params.add(new BasicNameValuePair("scope", GRAPH_SCOPE));
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT)
            .setResponseTimeout(RESPONSE_TIMEOUT)
            .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
            .build();
        post.setConfig(requestConfig);

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            return http.execute(post, response -> {
                int statusCode = response.getCode();
                String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                    : "";
                if (statusCode != 200) {
                    throw new GraphTokenException(
                        "Graph token fetch failed: HTTP " + statusCode + " body=" + redactBody(body)
                    );
                }
                JsonNode json = objectMapper.readTree(body);
                String accessToken = json.path("access_token").asText();
                int expiresIn = json.path("expires_in").asInt(3600);
                if (accessToken.isEmpty()) {
                    throw new GraphTokenException(
                        "Graph token response missing access_token: " + redactBody(body)
                    );
                }
                Instant expiresAt = clock.instant().plusSeconds(expiresIn);
                log.debug("Graph access_token refreshed: expires_in={} (cache until {})",
                    expiresIn, expiresAt);
                return new CachedToken(accessToken, expiresAt);
            });
        } catch (GraphTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphTokenException("Graph token fetch I/O error: " + e.getMessage(), e);
        }
    }

    /** Redact token-like content from log/error strings (security hygiene). */
    private static String redactBody(String body) {
        if (body == null) return "";
        // Truncate to first 200 chars + strip access_token value
        String truncated = body.length() > 200 ? body.substring(0, 200) + "..." : body;
        return truncated.replaceAll("(?i)\"access_token\"\\s*:\\s*\"[^\"]+\"", "\"access_token\":\"<redacted>\"");
    }

    /**
     * Cached token holder.
     */
    private record CachedToken(String token, Instant expiresAt) {
        boolean isStillValid(Instant now) {
            return now.plusSeconds(REFRESH_BUFFER_SECONDS).isBefore(expiresAt);
        }
    }

    /**
     * Graph token fetch exception.
     */
    public static class GraphTokenException extends RuntimeException {
        public GraphTokenException(String message) {
            super(message);
        }

        public GraphTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
