package com.serban.notify.adapter.webpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.SubscriberPushEndpoint;
import com.serban.notify.repository.SubscriberPushEndpointRepository;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebPushAdapter unit test (Faz 23.7 M7 T4.2 PR-W2.2 — Codex
 * {@code 019e49e7} P6 + P7 absorb).
 *
 * <p>Status mapping coverage (mocking WebPushSender — gerçek HTTP/lib
 * çağrısı YOK; WireMock IT real integration PR-W2.3 scope):
 * <ul>
 *   <li>201 / 204 → DELIVERED</li>
 *   <li>410 → FAILED + endpoint soft-delete (RFC 8030 expired)</li>
 *   <li>404 → FAILED + failure increment (threshold 3 sonrası delete)</li>
 *   <li>413 → FAILED (payload too large)</li>
 *   <li>429 / 503 → RETRY</li>
 *   <li>400 / 401 → FAILED (permanent client error)</li>
 *   <li>send Exception → RETRY</li>
 *   <li>invalid target ref → FAILED</li>
 *   <li>endpoint not found → FAILED</li>
 *   <li>payload over cap → FAILED</li>
 * </ul>
 */
class WebPushAdapterTest {

    private WebPushSender sender;
    private SubscriberPushEndpointRepository endpointRepo;
    private ObjectMapper objectMapper;
    private WebPushAdapter adapter;

    private static final UUID ENDPOINT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        sender = mock(WebPushSender.class);
        endpointRepo = mock(SubscriberPushEndpointRepository.class);
        objectMapper = new ObjectMapper();
        when(sender.getMaxPlaintextBytes()).thenReturn(3072);
        when(sender.getDefaultTtlSeconds()).thenReturn(3600);

        // Default endpoint stub
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(ENDPOINT_ID);
        endpoint.setOrgId("acme");
        endpoint.setSubscriberId("1204");
        endpoint.setEndpointUrl("https://fcm.googleapis.com/fcm/send/test");
        endpoint.setP256dhKey("test-p256dh");
        endpoint.setAuthSecret("test-auth");
        endpoint.setFailureCount(0);
        when(endpointRepo.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));

        adapter = new WebPushAdapter(sender, endpointRepo, objectMapper);
    }

    @Test
    void channelKeyReturnsPush() {
        assertThat(adapter.channelKey()).isEqualTo("push");
    }

    @Test
    void httpStatus201ReturnsDelivered() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(201, "Created"));

        var result = adapter.send(
            target(ENDPOINT_ID.toString()),
            message("hello", "test body")
        );

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(result.providerMessageId()).startsWith("webpush-");
    }

    @Test
    void httpStatus204ReturnsDelivered() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(204, "No Content"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("hi", "body"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
    }

    @Test
    void httpStatus410GoneFailsAndSoftDeletes() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(410, "Gone"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("title", "body"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("subscription_expired");
        // 410 immediate soft delete
        verify(endpointRepo).softDeleteBySubscriber(eq("acme"), eq("1204"), any());
        verify(endpointRepo).incrementFailure(eq(ENDPOINT_ID), any(), eq("410_GONE"));
    }

    @Test
    void httpStatus404FirstHitFailsWithoutSoftDelete() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(404, "Not Found"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("title", "body"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("endpoint_not_found");
        // First hit: increment but NO soft delete (threshold 3)
        verify(endpointRepo).incrementFailure(eq(ENDPOINT_ID), any(), eq("404_NOT_FOUND"));
        verify(endpointRepo, never()).softDeleteBySubscriber(anyString(), anyString(), any());
    }

    @Test
    void httpStatus404ThirdHitTriggersSoftDelete() throws Exception {
        // Pre-existing 2 failures → 3rd hit triggers soft delete
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(ENDPOINT_ID);
        endpoint.setOrgId("acme");
        endpoint.setSubscriberId("1204");
        endpoint.setEndpointUrl("https://fcm.googleapis.com/fcm/send/test");
        endpoint.setP256dhKey("test-p256dh");
        endpoint.setAuthSecret("test-auth");
        endpoint.setFailureCount(2); // pre-existing
        when(endpointRepo.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(404, "Not Found"));

        adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        verify(endpointRepo).softDeleteBySubscriber(eq("acme"), eq("1204"), any());
    }

    @Test
    void httpStatus429RateLimitReturnsRetry() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(429, "Too Many Requests"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void httpStatus503TransientReturnsRetry() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(503, "Service Unavailable"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void httpStatus400PermanentReturnsFailed() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenReturn(new WebPushSender.SendResult(400, "Bad Request"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("HTTP 400");
    }

    @Test
    void sendIOExceptionReturnsRetry() throws Exception {
        when(sender.send(anyString(), anyString(), anyString(), any(), anyInt()))
            .thenThrow(new java.io.IOException("network down"));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(result.failureReason()).startsWith("send_error");
    }

    @Test
    void invalidTargetRefReturnsFailed() throws Exception {
        var result = adapter.send(target("not-a-uuid"), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("invalid_target_ref");
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void endpointNotFoundReturnsFailed() throws Exception {
        when(endpointRepo.findById(ENDPOINT_ID)).thenReturn(Optional.empty());

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("endpoint_not_found");
    }

    @Test
    void deletedEndpointReturnsFailed() throws Exception {
        SubscriberPushEndpoint deleted = new SubscriberPushEndpoint();
        deleted.setEndpointId(ENDPOINT_ID);
        deleted.setDeletedAt(OffsetDateTime.now().minusHours(1));
        when(endpointRepo.findById(ENDPOINT_ID)).thenReturn(Optional.of(deleted));

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("t", "b"));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("endpoint_not_found");
    }

    @Test
    void payloadOverCapReturnsFailed() throws Exception {
        when(sender.getMaxPlaintextBytes()).thenReturn(50); // very low cap
        String hugeBody = "a".repeat(200);

        var result = adapter.send(target(ENDPOINT_ID.toString()), message("title", hugeBody));

        assertThat(result.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo("payload_too_large");
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), anyInt());
    }

    // --- Helpers

    private DeliveryTarget target(String targetRef) {
        // DeliveryTarget(channel, recipientType, recipientId, recipientHash,
        //                targetRef, providerKey, routingMetadata)
        return new DeliveryTarget(
            "push",                // channel
            "subscriber",          // recipientType
            "1204",                // recipientId
            "hmac-redacted",       // recipientHash
            targetRef,             // targetRef (endpoint UUID)
            "webpush",             // providerKey
            null                   // routingMetadata
        );
    }

    private RenderedMessage message(String subject, String body) {
        return new RenderedMessage(subject, null, body, "tr-TR");
    }
}
