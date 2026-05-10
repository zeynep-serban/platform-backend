package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.provider.GraphTokenService;
import com.serban.notify.template.RenderedMessage;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * GraphMailAdapter — Microsoft Graph REST API mail dispatch (port 443 HTTPS).
 *
 * <p>Faz 23 A8 — outbound TCP 587 (SMTP submission) ISP/datacenter block
 * durumunda alternatif mail dispatch yolu. Office 365 mail relay'e doğrudan
 * HTTPS üzerinden eriş.
 *
 * <p>Endpoint: {@code POST https://graph.microsoft.com/v1.0/users/{senderMailbox}/sendMail}
 *
 * <p>Auth: OAuth 2.0 client_credentials (Application permission / app-only —
 * admin consent required). Token cached + auto-refresh by {@link GraphTokenService}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code notify.adapters.graph.enabled} (default false; prod: true)</li>
 *   <li>{@code notify.adapters.graph.tenant-id} (Azure AD tenant)</li>
 *   <li>{@code notify.adapters.graph.client-id} (Azure AD app registration)</li>
 *   <li>{@code notify.adapters.graph.client-secret} (Vault inject)</li>
 *   <li>{@code notify.adapters.graph.sender-mailbox} (default: ai@acik.com — user principal name)</li>
 *   <li>{@code notify.adapters.graph.save-to-sent-items} (default: false — drafts/sent folder)</li>
 * </ul>
 *
 * <p>Multi-provider switching: {@link com.serban.notify.adapter.SmtpAdapter} ve
 * GraphMailAdapter aynı {@code channelKey()="email"} verir. SmtpAdapter
 * {@code @ConditionalOnProperty(notify.adapters.graph.enabled=false, matchIfMissing=true)}
 * Graph {@code @ConditionalOnProperty(notify.adapters.graph.enabled=true)}. Test
 * profile'da Graph disabled → SmtpAdapter aktif (Mailpit/local). Prod profile'da
 * Graph enabled → Graph aktif (SmtpAdapter conditional off, mutual exclusion).
 *
 * <p>DKIM signing: **otomatik** — Office 365 outbound mail flow DKIM signature
 * ekler ({@code <selector>._domainkey.<domain>} DNS TXT record varsa).
 * App-side DKIM (DkimSigner) Graph path'inde devreye girmez (SMTP-only).
 *
 * <p>Refs:
 * <ul>
 *   <li>https://learn.microsoft.com/en-us/graph/api/user-sendmail</li>
 *   <li>https://learn.microsoft.com/en-us/graph/throttling</li>
 *   <li>Graph throttle limits: 10000 requests/10 min per app per tenant</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "notify.adapters.graph.enabled", havingValue = "true")
public class GraphMailAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(GraphMailAdapter.class);
    private static final String GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0";

    /** HTTP timeout (Codex iter P1 absorb 2026-05-11): bounded failure for adapter. */
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(15);
    private static final Timeout CONNECTION_REQUEST_TIMEOUT = Timeout.ofSeconds(3);

    private final GraphTokenService tokenService;
    private final String senderMailbox;
    private final String fromName;
    private final boolean saveToSentItems;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphMailAdapter(
        GraphTokenService tokenService,
        @Value("${notify.adapters.graph.sender-mailbox:ai@acik.com}") String senderMailbox,
        @Value("${notify.adapters.smtp.from-name:Notification Orchestrator}") String fromName,
        @Value("${notify.adapters.graph.save-to-sent-items:false}") boolean saveToSentItems
    ) {
        this.tokenService = tokenService;
        this.senderMailbox = senderMailbox;
        this.fromName = fromName;
        this.saveToSentItems = saveToSentItems;
        log.info("GraphMailAdapter initialized: senderMailbox={} fromName=\"{}\" saveToSentItems={}",
            senderMailbox, fromName, saveToSentItems);
    }

    @Override
    public String channelKey() {
        return "email";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        String messageId = "<" + UUID.randomUUID() + "@notification-orchestrator-graph>";

        // Codex iter P1 absorb 2026-05-11: empty body guard — SMTP semantic parity.
        // SmtpAdapter blank/null both → FAILED. Graph adapter aynı kontratı korur.
        boolean htmlEmpty = message.bodyHtml() == null || message.bodyHtml().isBlank();
        boolean textEmpty = message.bodyText() == null || message.bodyText().isBlank();
        if (htmlEmpty && textEmpty) {
            log.warn("graph mail: template body empty (no html or text) — FAILED terminal");
            return DeliveryAttemptResult.failed("template body empty (no html or text)", null);
        }

        try {
            // Get Bearer token (cached/refreshed by service)
            String accessToken = tokenService.getAccessToken();

            // Build Graph sendMail message JSON
            String payload = buildMessagePayload(target, message, messageId);

            String url = String.format("%s/users/%s/sendMail", GRAPH_BASE_URL, senderMailbox);
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + accessToken);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));

            // Codex iter P1 absorb: explicit timeout (bounded failure)
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
                    return classifyResponse(statusCode, body, messageId, target.recipientHash(), message.subject());
                });
            }
        } catch (GraphTokenService.GraphTokenException e) {
            log.warn("graph mail send token error (RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("graph token: " + e.getMessage(), null);
        } catch (Exception e) {
            log.warn("graph mail send I/O error (RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("graph I/O: " + e.getClass().getSimpleName(), null);
        }
    }

    /**
     * Build Microsoft Graph sendMail message JSON body.
     *
     * <p>Schema reference:
     * https://learn.microsoft.com/en-us/graph/api/resources/message
     */
    private String buildMessagePayload(DeliveryTarget target, RenderedMessage message,
                                        String messageId) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode msg = root.putObject("message");

        msg.put("subject", message.subject() != null ? message.subject() : "");

        // Body: HTML preferred, text fallback
        ObjectNode body = msg.putObject("body");
        if (message.bodyHtml() != null && !message.bodyHtml().isBlank()) {
            body.put("contentType", "HTML");
            body.put("content", message.bodyHtml());
        } else {
            body.put("contentType", "Text");
            body.put("content", message.bodyText() != null ? message.bodyText() : "");
        }

        // toRecipients
        ObjectNode recipientWrapper = msg.putArray("toRecipients").addObject();
        ObjectNode emailAddr = recipientWrapper.putObject("emailAddress");
        emailAddr.put("address", target.targetRef());

        // Codex iter P1 absorb 2026-05-11: `message.from` field KALDIRILDI.
        // POST /v1.0/users/{senderMailbox}/sendMail — sender path'ten alır.
        // Payload'da from set edilirse Graph 400 BadRequest döner (mailbox ownership /
        // SendAs permission mismatch). FROM_NAME display sadece header görünür
        // — Graph outbound flow sender mailbox display name'i otomatik kullanır.

        // Internet message headers (custom Message-ID for trace)
        ObjectNode headers = msg.putArray("internetMessageHeaders").addObject();
        headers.put("name", "X-Notify-Message-ID");
        headers.put("value", messageId);

        root.put("saveToSentItems", saveToSentItems);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Classify Graph API response → DeliveryAttemptResult.
     *
     * <p>Status code semantics (Microsoft Graph):
     * <ul>
     *   <li>202 Accepted — mail queued for delivery (terminal success)</li>
     *   <li>400 Bad Request — message format invalid (terminal FAILED)</li>
     *   <li>401 Unauthorized — token expired/invalid (RETRY after refresh)</li>
     *   <li>403 Forbidden — permission denied (terminal FAILED)</li>
     *   <li>404 Not Found — sender mailbox missing (terminal FAILED)</li>
     *   <li>429 Too Many Requests — throttled (RETRY with backoff)</li>
     *   <li>5xx — service error (RETRY)</li>
     * </ul>
     */
    private DeliveryAttemptResult classifyResponse(int statusCode, String body, String messageId,
                                                    String recipientHash, String subject) {
        if (statusCode == 202) {
            log.info("graph mail accepted: to=<hash:{}> subject=<{}> message_id={} status=202",
                recipientHash, abbrev(subject), messageId);
            return DeliveryAttemptResult.delivered(messageId);
        }
        if (statusCode == 400) {
            log.warn("graph mail BadRequest (FAILED terminal): body={}", abbrev(body, 200));
            return DeliveryAttemptResult.failed("graph 400 BadRequest: " + abbrev(body, 100), statusCode);
        }
        if (statusCode == 401) {
            log.warn("graph mail Unauthorized (RETRY token refresh): body={}", abbrev(body, 200));
            return DeliveryAttemptResult.retry("graph 401: token may need refresh", statusCode);
        }
        if (statusCode == 403) {
            log.warn("graph mail Forbidden (FAILED — permission): body={}", abbrev(body, 200));
            return DeliveryAttemptResult.failed("graph 403 Forbidden: " + abbrev(body, 100), statusCode);
        }
        if (statusCode == 404) {
            log.warn("graph mail NotFound (FAILED — sender mailbox missing): body={}", abbrev(body, 200));
            return DeliveryAttemptResult.failed("graph 404 NotFound: " + abbrev(body, 100), statusCode);
        }
        if (statusCode == 429) {
            log.warn("graph mail Throttled (RETRY): body={}", abbrev(body, 200));
            return DeliveryAttemptResult.retry("graph 429 Throttled", statusCode);
        }
        if (statusCode >= 500 && statusCode < 600) {
            log.warn("graph mail server error (RETRY): {} body={}", statusCode, abbrev(body, 200));
            return DeliveryAttemptResult.retry("graph " + statusCode + " server error", statusCode);
        }
        // Unknown status — treat as RETRY (conservative)
        log.warn("graph mail unknown status (RETRY): {} body={}", statusCode, abbrev(body, 200));
        return DeliveryAttemptResult.retry("graph " + statusCode + " unknown", statusCode);
    }

    private static String abbrev(String s) {
        return abbrev(s, 60);
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
