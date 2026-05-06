package com.serban.notify.adapter.inapp;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.inbox.InboxEventPublisher;
import com.serban.notify.repository.NotificationInboxRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * InAppInboxAdapter unit test (Faz 23.3 PR-E.2).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Happy path — inbox row inserted with correct fields, DELIVERED returned</li>
 *   <li>Idempotent — pre-existing row → DELIVERED no-op, no second insert</li>
 *   <li>Missing subscriberId → FAILED (defensive validation)</li>
 *   <li>Malformed targetRef → FAILED (intentId|orgId expected)</li>
 *   <li>Parent intent not found → FAILED (downstream lookup safety)</li>
 *   <li>channelKey returns "in-app"</li>
 * </ul>
 */
class InAppInboxAdapterTest {

    private NotificationInboxRepository inboxRepository;
    private NotificationIntentRepository intentRepository;
    private InboxEventPublisher eventPublisher;
    private InAppInboxAdapter adapter;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(NotificationInboxRepository.class);
        intentRepository = mock(NotificationIntentRepository.class);
        eventPublisher = mock(InboxEventPublisher.class);
        adapter = new InAppInboxAdapter(inboxRepository, intentRepository, eventPublisher);
    }

    @Test
    void channelKeyIsInApp() {
        assertThat(adapter.channelKey()).isEqualTo("in-app");
    }

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void deliveredHappyPathInsertsInboxRow() {
        when(inboxRepository.findByOrgIdAndIntentIdAndSubscriberId(
            "default", "intent-1", "sub-1")).thenReturn(Optional.empty());
        NotificationIntent intent = stubIntent("intent-1", "default", "auth.password-reset");
        when(intentRepository.findByIntentIdAndOrgId("intent-1", "default"))
            .thenReturn(Optional.of(intent));
        when(inboxRepository.save(any(NotificationInbox.class))).thenAnswer(inv -> {
            NotificationInbox row = inv.getArgument(0);
            row.setId(42L);
            return row;
        });

        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash-x",
            "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("Subject", "<p>HTML</p>", "text body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).isEqualTo("inbox-42");

        org.mockito.ArgumentCaptor<NotificationInbox> captor =
            org.mockito.ArgumentCaptor.forClass(NotificationInbox.class);
        verify(inboxRepository).save(captor.capture());
        NotificationInbox saved = captor.getValue();
        assertThat(saved.getOrgId()).isEqualTo("default");
        assertThat(saved.getIntentId()).isEqualTo("intent-1");
        assertThat(saved.getSubscriberId()).isEqualTo("sub-1");
        assertThat(saved.getSubject()).isEqualTo("Subject");
        assertThat(saved.getBodyHtml()).isEqualTo("<p>HTML</p>");
        assertThat(saved.getBodyText()).isEqualTo("text body");
        assertThat(saved.getLocale()).isEqualTo("tr-TR");
        assertThat(saved.getTopicKey()).isEqualTo("auth.password-reset");
        assertThat(saved.getSeverity()).isEqualTo("info");
        assertThat(saved.getState()).isEqualTo(NotificationInbox.State.UNREAD);
        // PR-E.3: badge SSE event published on row insert
        verify(eventPublisher).publishInboxUpdated("default", "sub-1");
    }

    @Test
    void deliveredFallsBackToTrTrLocaleWhenMessageLocaleNull() {
        when(inboxRepository.findByOrgIdAndIntentIdAndSubscriberId(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(intentRepository.findByIntentIdAndOrgId(anyString(), anyString()))
            .thenReturn(Optional.of(stubIntent("intent-1", "default", "test.topic")));
        when(inboxRepository.save(any())).thenAnswer(inv -> {
            NotificationInbox row = inv.getArgument(0);
            row.setId(1L);
            return row;
        });

        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash", "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", null);  // locale null

        adapter.send(target, msg);

        org.mockito.ArgumentCaptor<NotificationInbox> captor =
            org.mockito.ArgumentCaptor.forClass(NotificationInbox.class);
        verify(inboxRepository).save(captor.capture());
        assertThat(captor.getValue().getLocale()).isEqualTo("tr-TR");
    }

    // ─── Idempotency ─────────────────────────────────────────────────────

    @Test
    void idempotentExistingRowReturnsDeliveredWithoutInsert() {
        NotificationInbox existing = new NotificationInbox();
        existing.setId(99L);
        existing.setOrgId("default");
        existing.setIntentId("intent-dup");
        existing.setSubscriberId("sub-1");
        when(inboxRepository.findByOrgIdAndIntentIdAndSubscriberId(
            "default", "intent-dup", "sub-1")).thenReturn(Optional.of(existing));

        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash", "intent-dup|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).isEqualTo("inbox-99");
        verify(inboxRepository, never()).save(any());
        // Intent lookup also short-circuited (idempotent path)
        verify(intentRepository, never()).findByIntentIdAndOrgId(anyString(), anyString());
        // PR-E.3: NO event when row already existed (badge already correct)
        verify(eventPublisher, never()).publishInboxUpdated(anyString(), anyString());
    }

    // ─── Pre-flight validation ───────────────────────────────────────────

    @Test
    void missingSubscriberIdFails() {
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", null, "hash", "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("subscriberId");
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void blankSubscriberIdFails() {
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "", "hash", "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
    }

    @Test
    void malformedTargetRefMissingPipeFails() {
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash", "intent-only-no-org", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("targetRef");
    }

    @Test
    void nullTargetRefFails() {
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash", null, "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("targetRef");
    }

    @Test
    void nonSubscriberRecipientTypeRejectedAtAdapterLevel() {
        // Codex iter-1 P3 absorb: defense-in-depth. Even if planner is bypassed,
        // adapter enforces its own subscriber-only contract.
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "external", "ext-id-1", "hash",
            "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("subscriber");
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void channelRecipientTypeRejectedAtAdapterLevel() {
        // "channel" recipient type (used by slack/webhook) is also invalid for in-app.
        DeliveryTarget target = new DeliveryTarget(
            "in-app", "channel", null, "hash",
            "intent-1|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("subscriber");
    }

    @Test
    void parentIntentNotFoundFails() {
        when(inboxRepository.findByOrgIdAndIntentIdAndSubscriberId(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(intentRepository.findByIntentIdAndOrgId("missing-intent", "default"))
            .thenReturn(Optional.empty());

        DeliveryTarget target = new DeliveryTarget(
            "in-app", "subscriber", "sub-1", "hash",
            "missing-intent|default", "inapp-default"
        );
        RenderedMessage msg = new RenderedMessage("S", null, "body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("intent not found");
        verify(inboxRepository, never()).save(any());
    }

    private static NotificationIntent stubIntent(String intentId, String orgId, String topicKey) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(intentId);
        intent.setOrgId(orgId);
        intent.setTopicKey(topicKey);
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setTemplateId("t");
        intent.setTemplateVersion(1);
        intent.setLocale("tr-TR");
        return intent;
    }
}
