package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.provider.GraphTokenService;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GraphMailAdapter unit test — mockserver kullanılarak Graph API responses simulate.
 *
 * <p>Mock'lanan endpoint'ler:
 * <ul>
 *   <li>POST /v1.0/users/{mailbox}/sendMail — 202/400/401/403/404/429/500 status'ları</li>
 * </ul>
 *
 * <p>Note: GraphTokenService mocked (token fetch test ayrı GraphTokenServiceTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphMailAdapterTest {

    private GraphMailAdapter adapter;
    private GraphTokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void initMocks() {
        tokenService = mock(GraphTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("test-access-token-12345");
    }

    @BeforeEach
    void setUp() {
        adapter = new GraphMailAdapter(
            tokenService,
            "ai@acik.com",
            "Ai - Açık Holding",
            false
        );
    }

    @Test
    void channelKeyReturnsEmail() {
        assertThat(adapter.channelKey()).isEqualTo("email");
    }

    @Test
    void senderMailboxConfigured() {
        // Just verify constructor succeeded with valid config
        assertThat(adapter).isNotNull();
        assertThat(adapter.channelKey()).isEqualTo("email");
    }

    // ====================================================================
    // JSON payload construction tests (verify via reflection / observable side-effect)
    // ====================================================================

    @Test
    void payloadBuildsValidGraphSendMailSchema() throws Exception {
        // Use reflection to invoke private buildMessagePayload (or expose via test)
        // Simpler: send to a mockserver and inspect captured request body
        // This test verifies schema via direct method call equivalent
        DeliveryTarget target = new DeliveryTarget(
            "email", "external", null, "hash-abc",
            "halil.kocoglu@serban.com.tr", "graph"
        );
        RenderedMessage msg = new RenderedMessage(
            "Test Subject",
            "<p>HTML body</p>",
            "Plain text body",
            "tr-TR"
        );

        // Build payload via reflection
        java.lang.reflect.Method method = GraphMailAdapter.class.getDeclaredMethod(
            "buildMessagePayload",
            DeliveryTarget.class, RenderedMessage.class, String.class
        );
        method.setAccessible(true);
        String payload = (String) method.invoke(adapter, target, msg, "<test-msg-id@notify>");

        JsonNode root = objectMapper.readTree(payload);
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("saveToSentItems")).isTrue();
        assertThat(root.get("saveToSentItems").asBoolean()).isFalse();

        JsonNode message = root.get("message");
        assertThat(message.get("subject").asText()).isEqualTo("Test Subject");
        assertThat(message.get("body").get("contentType").asText()).isEqualTo("HTML");
        assertThat(message.get("body").get("content").asText()).contains("HTML body");

        JsonNode toRecipients = message.get("toRecipients");
        assertThat(toRecipients.isArray()).isTrue();
        assertThat(toRecipients.get(0).get("emailAddress").get("address").asText())
            .isEqualTo("halil.kocoglu@serban.com.tr");

        // Codex iter P1 absorb: payload.from KALDIRILDI; sender URL path'ten alınır
        assertThat(message.has("from"))
            .as("payload.from removed — Graph 400 BadRequest risk")
            .isFalse();

        JsonNode headers = message.get("internetMessageHeaders");
        assertThat(headers.isArray()).isTrue();
        assertThat(headers.get(0).get("name").asText()).isEqualTo("X-Notify-Message-ID");
        assertThat(headers.get(0).get("value").asText()).isEqualTo("<test-msg-id@notify>");
    }

    @Test
    void payloadFallsBackToTextWhenHtmlMissing() throws Exception {
        DeliveryTarget target = new DeliveryTarget(
            "email", "external", null, "hash",
            "test@example.com", "graph"
        );
        RenderedMessage msg = new RenderedMessage(
            "Subject", null, "Plain text body only", "en"
        );

        java.lang.reflect.Method method = GraphMailAdapter.class.getDeclaredMethod(
            "buildMessagePayload",
            DeliveryTarget.class, RenderedMessage.class, String.class
        );
        method.setAccessible(true);
        String payload = (String) method.invoke(adapter, target, msg, "<mid@notify>");

        JsonNode message = objectMapper.readTree(payload).get("message");
        assertThat(message.get("body").get("contentType").asText()).isEqualTo("Text");
        assertThat(message.get("body").get("content").asText()).isEqualTo("Plain text body only");
    }

    // ====================================================================
    // Response classification tests
    // ====================================================================

    // ====================================================================
    // Empty body guard (Codex iter P1 absorb — SMTP semantic parity)
    // ====================================================================

    @Test
    void sendWithEmptyBodyReturnsFailed() {
        com.serban.notify.delivery.DeliveryTarget target = new com.serban.notify.delivery.DeliveryTarget(
            "email", "external", null, "hash",
            "test@example.com", "graph"
        );
        com.serban.notify.template.RenderedMessage emptyMsg = new com.serban.notify.template.RenderedMessage(
            "Subject", null, null, "tr-TR"
        );

        ChannelAdapter.DeliveryAttemptResult result = adapter.send(target, emptyMsg);
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).contains("template body empty");
    }

    @Test
    void sendWithBlankWhitespaceBodyReturnsFailed() {
        com.serban.notify.delivery.DeliveryTarget target = new com.serban.notify.delivery.DeliveryTarget(
            "email", "external", null, "hash",
            "test@example.com", "graph"
        );
        com.serban.notify.template.RenderedMessage blankMsg = new com.serban.notify.template.RenderedMessage(
            "Subject", "   \n\t   ", "  \n  ", "tr-TR"
        );

        ChannelAdapter.DeliveryAttemptResult result = adapter.send(target, blankMsg);
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
    }

    @Test
    void classifyResponse202ReturnsDelivered() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(202, "");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(result.providerMessageId()).isNotNull();
    }

    @Test
    void classifyResponse400ReturnsFailed() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(400,
            "{\"error\":{\"code\":\"BadRequest\",\"message\":\"invalid recipient\"}}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.providerResponseCode()).isEqualTo(400);
        assertThat(result.failureReason()).contains("BadRequest");
    }

    @Test
    void classifyResponse401ReturnsRetry() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(401, "{\"error\":\"Unauthorized\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(result.providerResponseCode()).isEqualTo(401);
    }

    @Test
    void classifyResponse403ReturnsFailed() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(403, "{\"error\":\"Forbidden\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.providerResponseCode()).isEqualTo(403);
    }

    @Test
    void classifyResponse404ReturnsFailed() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(404, "{\"error\":\"NotFound\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.providerResponseCode()).isEqualTo(404);
    }

    @Test
    void classifyResponse429ReturnsRetry() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(429, "{\"error\":\"TooManyRequests\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(result.providerResponseCode()).isEqualTo(429);
    }

    @Test
    void classifyResponse500ReturnsRetry() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(500, "{\"error\":\"InternalServerError\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(result.providerResponseCode()).isEqualTo(500);
    }

    @Test
    void classifyResponse503ReturnsRetry() throws Exception {
        ChannelAdapter.DeliveryAttemptResult result = invokeClassify(503, "{\"error\":\"ServiceUnavailable\"}");
        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(result.providerResponseCode()).isEqualTo(503);
    }

    private ChannelAdapter.DeliveryAttemptResult invokeClassify(int statusCode, String body) throws Exception {
        java.lang.reflect.Method method = GraphMailAdapter.class.getDeclaredMethod(
            "classifyResponse",
            int.class, String.class, String.class, String.class, String.class
        );
        method.setAccessible(true);
        return (ChannelAdapter.DeliveryAttemptResult) method.invoke(adapter, statusCode, body,
            "<test-msg-id@notify>", "hash-abc", "Test Subject");
    }
}
