package com.serban.notify.delivery;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.SlackWebhookAdapter;
import com.serban.notify.adapter.SmtpAdapter;
import com.serban.notify.adapter.WebhookEgressAdapter;
import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.AuditEventRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryDispatchService integration test (Codex 019df9ae Q1 REVISE absorb).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Internal direct-invoke pipeline (PR3 — no auto-dispatch)</li>
 *   <li>Template lookup → render → adapter.send → delivery row + audit event</li>
 *   <li>Status transitions: PENDING → PROCESSING → COMPLETED (all delivered)</li>
 *   <li>Partial failure: stays PROCESSING (PR4 worker decides)</li>
 *   <li>Adapter exception → RETRY result (no propagation)</li>
 * </ul>
 *
 * <p>Strategy: real Spring context + Testcontainers PG; channel adapter
 * implementations replaced with {@link MockBean} so we control adapter return
 * values without real SMTP/HTTP.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class DeliveryDispatchServiceIntegrationTest extends AbstractPostgresTest {

    @Autowired DeliveryDispatchService dispatcher;
    @Autowired NotificationTemplateRepository templateRepo;
    @Autowired NotificationIntentRepository intentRepo;
    @Autowired NotificationDeliveryRepository deliveryRepo;
    @Autowired AuditEventRepository auditRepo;

    // ChannelAdapterRegistry indexes lazily on first access (Codex 019df9ef CI
    // fix), so @MockBean returning null channelKey() at context startup no
    // longer crashes the registry constructor — channelKey() stub in
    // @BeforeEach happens before the first dispatch call.
    @MockBean SmtpAdapter smtpAdapter;
    @MockBean SlackWebhookAdapter slackAdapter;
    @MockBean WebhookEgressAdapter webhookAdapter;

    @BeforeEach
    void seedTemplate() {
        when(smtpAdapter.channelKey()).thenReturn("email");
        when(slackAdapter.channelKey()).thenReturn("slack");
        when(webhookAdapter.channelKey()).thenReturn("webhook");

        if (templateRepo.findByTemplateIdAndVersionAndLocale("dispatch-test", 1, "tr-TR").isPresent()) {
            return;
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("dispatch-test");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject("Sub [[${vars.user_name}]]");
        t.setBodyText("Hello [[${vars.user_name}]]");
        t.setActive(true);
        t.setExternalAllowed(true);  // PR5 absorb: tests use external recipient
        t.setCreatedBy("test");
        templateRepo.save(t);
    }

    @Test
    void dispatchSingleEmailDelivered() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1204", "rh-1", "user@example.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1@host>")
        );

        int attempted = dispatcher.dispatchPlanned(intent, List.of(target));

        assertThat(attempted).isEqualTo(1);
        verify(smtpAdapter, times(1)).send(any(), any(RenderedMessage.class));

        // Intent COMPLETED
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);

        // Delivery row persisted
        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        NotificationDelivery d = deliveries.get(0);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(d.getChannel()).isEqualTo("email");
        assertThat(d.getProvider()).isEqualTo("smtp-default");
        assertThat(d.getProviderMsgId()).isEqualTo("<msg-1@host>");
        assertThat(d.getDeliveredAt()).isNotNull();
        assertThat(d.getRecipientType()).isEqualTo(NotificationDelivery.RecipientType.SUBSCRIBER);

        // Audit events: ATTEMPTED + SUCCEEDED
        List<AuditEvent> events = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(intent.getCorrelationId());
        assertThat(events).extracting(AuditEvent::getEventType)
            .contains("DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
    }

    @Test
    void dispatchPermanentFailureKeepsProcessing() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "external", null, "rh-2", "user2@example.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.failed("550 No such user", 500)
        );

        dispatcher.dispatchPlanned(intent, List.of(target));

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        // PR3: permanent failure keeps PROCESSING; PR4 worker decides terminal state
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        NotificationDelivery d = deliveries.get(0);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(d.getFailureReason()).contains("550");

        List<AuditEvent> events = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(intent.getCorrelationId());
        assertThat(events).extracting(AuditEvent::getEventType)
            .contains("DELIVERY_ATTEMPTED", "DELIVERY_FAILED");
    }

    @Test
    void dispatchRetryKeepsProcessing() {
        NotificationIntent intent = saveIntent("webhook");
        DeliveryTarget target = new DeliveryTarget(
            "webhook", "channel", null, "rh-w", "https://hook.local/x", "webhook-default"
        );
        when(webhookAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.retry("HTTP 503", 503)
        );

        dispatcher.dispatchPlanned(intent, List.of(target));

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.RETRY);
    }

    @Test
    void dispatchAdapterExceptionTreatedAsRetry() {
        NotificationIntent intent = saveIntent("slack");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-s", "https://hooks.slack/x", "slack-default"
        );
        when(slackAdapter.send(any(), any())).thenThrow(new RuntimeException("boom"));

        // Must NOT propagate; pipeline handles
        dispatcher.dispatchPlanned(intent, List.of(target));

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.RETRY);

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);
    }

    @Test
    void deliveredRegressGuardConcurrentStaleDispatch() {
        // Codex 019df9ef iter-3 absorb: simulate concurrent stale dispatch —
        // first dispatch DELIVERED (commits), then a stale concurrent worker
        // re-runs with same target but adapter returns RETRY/FAILED. Existing
        // DELIVERED row MUST NOT regress.
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1", "rh-regress", "u@x.com", "smtp-default"
        );

        // Worker A: DELIVERED — commits
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-A>")
        );
        dispatcher.dispatchPlanned(intent, List.of(target));

        // Worker B (stale): adapter returns RETRY (provider was already done)
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.retry("503 stale", 503)
        );
        // Re-fetch intent (status COMPLETED already)
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        // Force PROCESSING so dispatchPlanned doesn't bypass; in real PR4, worker
        // selects PROCESSING intents. Simulate stale state by clearing.
        reloaded.setStatus(NotificationIntent.Status.PROCESSING);
        intentRepo.save(reloaded);
        dispatcher.dispatchPlanned(reloaded, List.of(target));

        // Row still DELIVERED — regress guard fired (skip path); attempt_count
        // not incremented (no spurious update).
        List<NotificationDelivery> rows = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(rows.get(0).getProviderMsgId()).isEqualTo("<msg-A>");
    }

    @Test
    void dispatchRetryThenDeliverUpsertsAggregate() {
        // Codex 019df9ef P2 absorb iter-2: existing RETRY row → re-dispatch
        // UPDATEs same row (not INSERT new), attempt_count++, status=DELIVERED,
        // delivered_at set, no unique constraint violation.
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1", "rh-up", "user@example.com", "smtp-default"
        );

        // First attempt: RETRY (5xx)
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.retry("503", 503)
        );
        dispatcher.dispatchPlanned(intent, List.of(target));

        List<NotificationDelivery> after1 = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(after1).hasSize(1);
        assertThat(after1.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.RETRY);
        assertThat(after1.get(0).getAttemptCount()).isEqualTo(1);
        assertThat(after1.get(0).getDeliveredAt()).isNull();

        // Second attempt: DELIVERED — should UPDATE the same row, not insert
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-recovered>")
        );
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        dispatcher.dispatchPlanned(reloaded, List.of(target));

        List<NotificationDelivery> after2 = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(after2).hasSize(1);  // STILL 1 row — unique constraint preserved
        NotificationDelivery aggregate = after2.get(0);
        assertThat(aggregate.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(aggregate.getAttemptCount()).isEqualTo(2);  // incremented
        assertThat(aggregate.getDeliveredAt()).isNotNull();
        assertThat(aggregate.getFailureReason()).isNull();  // cleared on recover
        assertThat(aggregate.getProviderMsgId()).isEqualTo("<msg-recovered>");
    }

    @Test
    void dispatchRetryThenFailedUpsertsAggregate() {
        // RETRY → FAILED transition (e.g., transient 5xx → permanent 4xx after re-try)
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "external", null, "rh-rf", "u@x.com", "smtp-default"
        );

        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.retry("503", 503)
        );
        dispatcher.dispatchPlanned(intent, List.of(target));

        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.failed("permanent denial", 500)
        );
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        dispatcher.dispatchPlanned(reloaded, List.of(target));

        List<NotificationDelivery> rows = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(rows).hasSize(1);  // still single aggregate row
        assertThat(rows.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(rows.get(0).getAttemptCount()).isEqualTo(2);
        assertThat(rows.get(0).getFailureReason()).isEqualTo("permanent denial");
    }

    @Test
    void dispatchIdempotentSkipsAlreadyDelivered() {
        // Codex 019df9ef P2 absorb: re-dispatch should NOT re-send DELIVERED targets
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1", "rh-idem", "user@example.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1@host>")
        );

        // First dispatch
        dispatcher.dispatchPlanned(intent, List.of(target));
        verify(smtpAdapter, times(1)).send(any(), any(RenderedMessage.class));

        // Reload intent (status = COMPLETED)
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);

        // Second dispatch (idempotent) — should NOT call adapter.send again
        dispatcher.dispatchPlanned(reloaded, List.of(target));
        verify(smtpAdapter, times(1)).send(any(), any(RenderedMessage.class));  // still 1

        // Single delivery row remains
        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
    }

    @Test
    void dispatchMultiTargetMixedResults() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget t1 = new DeliveryTarget(
            "email", "subscriber", "1", "rh-a", "a@x.com", "smtp-default"
        );
        DeliveryTarget t2 = new DeliveryTarget(
            "email", "subscriber", "2", "rh-b", "b@x.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any()))
            .thenReturn(ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1>"))
            .thenReturn(ChannelAdapter.DeliveryAttemptResult.bounced("550 hard bounce"));

        int attempted = dispatcher.dispatchPlanned(intent, List.of(t1, t2));

        assertThat(attempted).isEqualTo(2);
        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).extracting(NotificationDelivery::getStatus)
            .containsExactlyInAnyOrder(
                NotificationDelivery.Status.DELIVERED,
                NotificationDelivery.Status.BOUNCED
            );

        // BOUNCED is permanent fail → intent stays PROCESSING (PR4 decides)
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);
    }

    private NotificationIntent saveIntent(String channel) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setCorrelationId("trace-" + UUID.randomUUID().toString().substring(0, 8));
        intent.setOrgId("default");
        intent.setTopicKey("dispatch.test");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("user_name", "Halil"));
        intent.setTemplateId("dispatch-test");
        intent.setTemplateVersion(1);
        intent.setLocale("tr-TR");
        intent.setChannels(new String[] { channel });
        intent.setStatus(NotificationIntent.Status.PENDING);
        return intentRepo.save(intent);
    }
}
