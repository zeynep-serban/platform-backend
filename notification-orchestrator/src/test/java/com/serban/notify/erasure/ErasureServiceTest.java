package com.serban.notify.erasure;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationInboxRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErasureService unit test (Faz 23.2 PR-B — Codex 019dfae5 Q2 + Q3 absorb).
 *
 * <p>Codex Q3 REVISE: 6 minimum tests cover:
 * <ul>
 *   <li>Erasure no-op when no matching intent</li>
 *   <li>Erasure clears payload + recipients_snapshot + metadata + preference_override</li>
 *   <li>Erasure clears delivery.recipient_id (recipient_hash KORUNUR)</li>
 *   <li>Erasure idempotent (second call no additional state change)</li>
 *   <li>Audit append SUBSCRIBER_ERASURE_REQUEST event with non-PII details</li>
 *   <li>Multi-intent: each intent processed independently</li>
 * </ul>
 */
class ErasureServiceTest {

    private NotificationIntentRepository intentRepo;
    private NotificationDeliveryRepository deliveryRepo;
    private NotificationInboxRepository inboxRepo;
    private AuditEventPublisher audit;
    private ErasureService service;

    @BeforeEach
    void setUp() {
        intentRepo = mock(NotificationIntentRepository.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        inboxRepo = mock(NotificationInboxRepository.class);
        audit = mock(AuditEventPublisher.class);
        service = new ErasureService(intentRepo, deliveryRepo, inboxRepo, audit);
    }

    @Test
    void noMatchingIntentReturnsZeroResult() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());

        var result = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket-1234"
        ));

        assertThat(result.intentsErased()).isEqualTo(0);
        assertThat(result.deliveriesAnonymized()).isEqualTo(0);
        verify(audit, never()).publish(anyString(), any(), any(), any(), any());
    }

    @Test
    void erasureClearsAllPiiFields() {
        NotificationIntent intent = newIntent("intent-1", Map.of("user_email", "x@y.com"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of(intent));
        when(deliveryRepo.findByIntentId("intent-1")).thenReturn(List.of());

        service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket-1234"
        ));

        assertThat(intent.getPayload()).isNull();
        assertThat(intent.getRecipientsSnapshot()).isNull();
        assertThat(intent.getMetadata()).isNull();
        assertThat(intent.getPreferenceOverride()).isNull();
        verify(intentRepo).save(intent);
    }

    @Test
    void erasureAnonymizesDeliveriesPreservingRecipientHash() {
        NotificationIntent intent = newIntent("intent-1", Map.of("k", "v"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of(intent));

        NotificationDelivery delivery = newDelivery("intent-1", "1204", "rh-abc");
        when(deliveryRepo.findByIntentId("intent-1")).thenReturn(List.of(delivery));

        service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        assertThat(delivery.getRecipientId()).isNull();
        // recipient_hash KORUNUR (operational analytics)
        assertThat(delivery.getRecipientHash()).isEqualTo("rh-abc");
        verify(deliveryRepo).save(delivery);
    }

    @Test
    void erasureIdempotentNoOpOnSecondCall() {
        // First call: payload + snapshot present
        NotificationIntent intent = newIntent("intent-1", Map.of("k", "v"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of(intent));
        when(deliveryRepo.findByIntentId("intent-1")).thenReturn(List.of());

        var first = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));
        assertThat(first.intentsErased()).isEqualTo(1);

        // Second call: same intent already erased (payload null + snapshot null)
        var second = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));
        assertThat(second.intentsErased()).isEqualTo(0);
        // First call: 1 audit; second call: 0 audit (idempotent skip)
        verify(audit, times(1)).publish(anyString(), any(), any(), any(), any());
    }

    @Test
    void auditAppendsSubscriberErasureRequestEvent() {
        NotificationIntent intent = newIntent("intent-1", Map.of("k", "v"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of(intent));
        when(deliveryRepo.findByIntentId("intent-1")).thenReturn(List.of());

        service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket-1234"
        ));

        verify(audit, atLeastOnce()).publish(
            org.mockito.ArgumentMatchers.eq("SUBSCRIBER_ERASURE_REQUEST"),
            any(NotificationIntent.class),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any()
        );
    }

    @Test
    void multipleIntentsProcessedIndependently() {
        NotificationIntent intent1 = newIntent("intent-1", Map.of("k", "v1"));
        NotificationIntent intent2 = newIntent("intent-2", Map.of("k", "v2"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204"))
            .thenReturn(List.of(intent1, intent2));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of());

        var result = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        assertThat(result.intentsErased()).isEqualTo(2);
        assertThat(intent1.getPayload()).isNull();
        assertThat(intent2.getPayload()).isNull();
        verify(intentRepo, times(2)).save(any(NotificationIntent.class));
    }

    // ─── Faz 23.3 PR-E.1 inbox erasure (Codex iter-1 P1.2 absorb) ───────

    @Test
    void inboxRowsHardDeletedOnErasure() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());
        when(inboxRepo.deleteByOrgIdAndSubscriberId("acme", "1204")).thenReturn(3);

        var result = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        assertThat(result.inboxRowsDeleted()).isEqualTo(3);
        verify(inboxRepo).deleteByOrgIdAndSubscriberId("acme", "1204");
    }

    @Test
    void inboxErasureAuditEventEmittedOnlyWhenRowsDeleted() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());
        when(inboxRepo.deleteByOrgIdAndSubscriberId("acme", "1204")).thenReturn(2);

        service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        // Codex iter-2 P1 absorb: standalone publish (no intent dep)
        verify(audit).publishStandalone(
            org.mockito.ArgumentMatchers.eq("SUBSCRIBER_INBOX_ERASURE"),
            org.mockito.ArgumentMatchers.eq("acme"),
            org.mockito.ArgumentMatchers.isNull(),
            any()
        );
    }

    @Test
    void inboxErasureNoAuditWhenNoRowsDeleted() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());
        when(inboxRepo.deleteByOrgIdAndSubscriberId("acme", "1204")).thenReturn(0);

        service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        // No SUBSCRIBER_INBOX_ERASURE audit event when 0 rows deleted (idempotent silence)
        verify(audit, never()).publishStandalone(
            org.mockito.ArgumentMatchers.eq("SUBSCRIBER_INBOX_ERASURE"),
            any(), any(), any()
        );
    }

    @Test
    void inboxErasureCombinedWithIntentResultIncludesAllCounts() {
        NotificationIntent intent = newIntent("intent-1", Map.of("k", "v"));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of(intent));
        when(deliveryRepo.findByIntentId("intent-1")).thenReturn(List.of());
        when(inboxRepo.deleteByOrgIdAndSubscriberId("acme", "1204")).thenReturn(5);

        var result = service.eraseSubscriber(new ErasureService.EraseRequest(
            "acme", "1204", "subject_request", "ticket"
        ));

        assertThat(result.intentsErased()).isEqualTo(1);
        assertThat(result.inboxRowsDeleted()).isEqualTo(5);
    }

    private NotificationIntent newIntent(String intentId, Map<String, Object> payload) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId(intentId);
        i.setOrgId("acme");
        i.setTopicKey("test");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setDataClassification(NotificationIntent.DataClassification.transactional);
        i.setPayload(new java.util.HashMap<>(payload));
        i.setRecipientsSnapshot(List.of(Map.of("type", "subscriber", "subscriberId", "1204")));
        i.setMetadata(new java.util.HashMap<>(Map.of("source", "test")));
        i.setPreferenceOverride(new java.util.HashMap<>(Map.of("override", "true")));
        return i;
    }

    private NotificationDelivery newDelivery(String intentId, String recipientId, String recipientHash) {
        NotificationDelivery d = new NotificationDelivery();
        d.setIntentId(intentId);
        d.setChannel("email");
        d.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        d.setRecipientId(recipientId);
        d.setRecipientHash(recipientHash);
        d.setProvider("smtp-default");
        d.setStatus(NotificationDelivery.Status.DELIVERED);
        return d;
    }
}
