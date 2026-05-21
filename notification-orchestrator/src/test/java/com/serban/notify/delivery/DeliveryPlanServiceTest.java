package com.serban.notify.delivery;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.redaction.PiiRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeliveryPlanService unit test (Codex 019df9ae Q2 PARTIAL absorb +
 * Faz 23.3.1 SMS extension).
 *
 * <p>Test edilen contract:
 * <ul>
 *   <li>email: recipient-addressed → N recipient × 1 target</li>
 *   <li>sms: recipient-addressed → N recipient × 1 target (Faz 23.3.1)</li>
 *   <li>slack: target-addressed → 1 target per intent (channel_routing.slack.webhookUrl)</li>
 *   <li>webhook: target-addressed → 1 target per intent (channel_routing.webhook.targetUrl)</li>
 *   <li>missing channel routing → InvalidRequestException</li>
 *   <li>unknown channel → InvalidRequestException</li>
 * </ul>
 */
class DeliveryPlanServiceTest {

    private PiiRedactor redactor;
    private ChannelAdapterRegistry registry;
    private com.serban.notify.preference.SubscriberPreferenceService prefService;
    private com.serban.notify.repository.SubscriberPushEndpointRepository pushEndpointRepo;
    private DeliveryPlanService service;

    @BeforeEach
    void setUp() {
        redactor = mock(PiiRedactor.class);
        registry = mock(ChannelAdapterRegistry.class);
        when(redactor.hashRecipient(anyString(), anyString(), anyString()))
            .thenReturn("hash-mock");
        // All test channels are supported by registry
        when(registry.supports("email")).thenReturn(true);
        when(registry.supports("sms")).thenReturn(true);
        when(registry.supports("in-app")).thenReturn(true);
        when(registry.supports("push")).thenReturn(true);
        when(registry.supports("slack")).thenReturn(true);
        when(registry.supports("webhook")).thenReturn(true);
        when(registry.supports("unknown")).thenReturn(false);
        when(registry.supportedChannels())
            .thenReturn(java.util.Set.of("email", "sms", "in-app", "push", "slack", "webhook"));

        // PR5: SubscriberPreferenceService dependency injected; tests provide
        // ref.email() / ref.phone() directly when contact lookup not desired.
        prefService = mock(com.serban.notify.preference.SubscriberPreferenceService.class);
        // Faz 23.7 M7 T4.2 PR-W2.5: push endpoint repo for fan-out planning
        pushEndpointRepo = mock(com.serban.notify.repository.SubscriberPushEndpointRepository.class);
        service = new DeliveryPlanService(redactor, registry, prefService, pushEndpointRepo);
    }

    @Test
    void planEmailFanoutPerRecipient() {
        NotificationIntent intent = intent(new String[] { "email" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1", "a@x.com", null, "A", "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "b@x.com", null, "B", "en-US"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("email", "email");
        assertThat(targets).extracting(DeliveryTarget::targetRef)
            .containsExactly("a@x.com", "b@x.com");
        assertThat(targets).extracting(DeliveryTarget::recipientType)
            .containsExactly("subscriber", "external");
    }

    @Test
    void planSlackSingleTargetFromRouting() {
        NotificationIntent intent = intent(
            new String[] { "slack" },
            Map.of("slack", Map.of("webhookUrl", "https://hooks.slack/x"))
        );
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            // Slack ignores recipient list — N recipients are audit context only,
            // single target per intent
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber, "1", null, null, null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber, "2", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(1);  // single target despite 2 recipients
        assertThat(targets.get(0).channel()).isEqualTo("slack");
        assertThat(targets.get(0).targetRef()).isEqualTo("https://hooks.slack/x");
        assertThat(targets.get(0).recipientType()).isEqualTo("channel");
    }

    @Test
    void planWebhookSingleTargetFromRouting() {
        NotificationIntent intent = intent(
            new String[] { "webhook" },
            Map.of("webhook", Map.of("targetUrl", "https://api.partner/notify"))
        );

        List<DeliveryTarget> targets = service.plan(intent, List.of());

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).channel()).isEqualTo("webhook");
        assertThat(targets.get(0).targetRef()).isEqualTo("https://api.partner/notify");
    }

    @Test
    void planMixedChannelsFanoutCorrectly() {
        // 2 recipients × email + 1 slack + 1 webhook = 2 + 1 + 1 = 4 targets
        NotificationIntent intent = intent(
            new String[] { "email", "slack", "webhook" },
            Map.of(
                "slack", Map.of("webhookUrl", "https://hooks.slack/x"),
                "webhook", Map.of("targetUrl", "https://api.partner/n")
            )
        );
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber, "1", "a@x.com", null, null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external, null, "b@x.com", null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(4);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("email", "email", "slack", "webhook");
    }

    @Test
    void planSlackWithoutRoutingFails() {
        NotificationIntent intent = intent(new String[] { "slack" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of();

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("slack");
    }

    @Test
    void planWebhookWithoutRoutingFails() {
        NotificationIntent intent = intent(new String[] { "webhook" }, null);

        assertThatThrownBy(() -> service.plan(intent, List.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("webhook");
    }

    @Test
    void planUnknownChannelFails() {
        NotificationIntent intent = intent(new String[] { "unknown" }, null);

        assertThatThrownBy(() -> service.plan(intent, List.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    void planEmailExternalWithoutEmailFails() {
        NotificationIntent intent = intent(new String[] { "email" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external, null, null, "+90123456789", null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("email");
    }

    @Test
    void planSlackUsesDefaultUrlIfRoutingAbsent() {
        // Default url via @Value (PR3 dev/test fallback)
        NotificationIntent intent = intent(new String[] { "slack" }, null);
        ReflectionTestUtils.setField(service, "defaultSlackWebhookUrl", "https://hooks.slack/default");

        List<DeliveryTarget> targets = service.plan(intent, List.of());

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).targetRef()).isEqualTo("https://hooks.slack/default");
    }

    @Test
    void planFallsBackToIntentRecipientsSnapshotWhenParamEmpty() {
        // Codex 019df9ef P2 absorb: PR4 worker path — recipients param empty,
        // DeliveryPlanService reads from intent.recipients_snapshot.
        NotificationIntent intent = intent(new String[] { "email" }, null);
        intent.setRecipientsSnapshot(java.util.List.of(
            new java.util.LinkedHashMap<>(Map.of(
                "type", "subscriber", "subscriberId", "1", "email", "snap@x.com",
                "locale", "tr-TR"
            )),
            new java.util.LinkedHashMap<>(Map.of(
                "type", "external", "email", "ext@x.com", "locale", "en-US"
            ))
        ));

        // Pass null/empty recipients → fall back to snapshot
        List<DeliveryTarget> targets = service.plan(intent, List.of());

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(DeliveryTarget::targetRef)
            .containsExactly("snap@x.com", "ext@x.com");
        assertThat(targets).extracting(DeliveryTarget::recipientType)
            .containsExactly("subscriber", "external");
    }

    @Test
    void planUsesParamRecipientsWhenProvidedNotSnapshot() {
        // PR3 submit-time path: caller passes recipients explicitly; snapshot
        // is ignored when param non-empty (avoids double-counting).
        NotificationIntent intent = intent(new String[] { "email" }, null);
        intent.setRecipientsSnapshot(java.util.List.of(
            new java.util.LinkedHashMap<>(Map.of(
                "type", "external", "email", "snap@x.com", "locale", "en-US"
            ))
        ));
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "param@x.com", null, null, "en-US"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).targetRef()).isEqualTo("param@x.com");
    }

    // ─── Faz 23.3.1 SMS tests ─────────────────────────────────────────────

    @Test
    void planSmsFanoutPerRecipientWithExplicitPhone() {
        // Subscriber + external; both provide phone explicitly → no contact
        // lookup. Validates recipient-addressed pattern parallel to email.
        NotificationIntent intent = intent(new String[] { "sms" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1", null, "+905321111111", "A", "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, null, "+905322222222", "B", "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("sms", "sms");
        assertThat(targets).extracting(DeliveryTarget::targetRef)
            .containsExactly("+905321111111", "+905322222222");
        assertThat(targets).extracting(DeliveryTarget::recipientType)
            .containsExactly("subscriber", "external");
        // Faz 23.3 multi-provider (Codex `019e3f82` absorb #2): SMS plan-time
        // providerKey "sms" placeholder — gerçek provider SmsAdapter failover
        // sonucu DeliveryAttemptResult.actualProviderKey ile runtime'da yazılır.
        assertThat(targets).extracting(DeliveryTarget::providerKey)
            .containsExactly("sms", "sms");
    }

    @Test
    void planSmsSubscriberLooksUpPhoneFromContactWhenRefMissing() {
        // Subscriber without ref.phone() → DeliveryPlanService resolves via
        // SubscriberPreferenceService.findContact (PR5 projection pattern).
        NotificationIntent intent = intent(new String[] { "sms" }, null);
        com.serban.notify.domain.SubscriberContact contact =
            new com.serban.notify.domain.SubscriberContact();
        contact.setOrgId("default");
        contact.setSubscriberId("42");
        contact.setPhone("+905333333333");
        when(prefService.findContact("default", "42"))
            .thenReturn(java.util.Optional.of(contact));

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "42", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).channel()).isEqualTo("sms");
        assertThat(targets.get(0).targetRef()).isEqualTo("+905333333333");
        assertThat(targets.get(0).recipientId()).isEqualTo("42");
    }

    @Test
    void planSmsSubscriberMissingPhoneFails() {
        // Subscriber, no ref.phone() and contact lookup empty → fail-fast.
        NotificationIntent intent = intent(new String[] { "sms" }, null);
        when(prefService.findContact(anyString(), anyString()))
            .thenReturn(java.util.Optional.empty());

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "no-phone-sub", null, null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("phone");
    }

    @Test
    void planSmsExternalWithoutPhoneFails() {
        NotificationIntent intent = intent(new String[] { "sms" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "x@y.z", null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("phone");
    }

    @Test
    void planSmsRejectsNonE164PhoneFromContactProjection() {
        // Codex iter-1 P2 absorb: subscriber contact may carry legacy/non-E.164
        // phone (Workcube ETL); plan-time fail-fast prevents PII leak via
        // adapter error path.
        NotificationIntent intent = intent(new String[] { "sms" }, null);
        com.serban.notify.domain.SubscriberContact contact =
            new com.serban.notify.domain.SubscriberContact();
        contact.setOrgId("default");
        contact.setSubscriberId("legacy-fmt");
        contact.setPhone("05321234567");  // legacy local format, no "+"
        when(prefService.findContact("default", "legacy-fmt"))
            .thenReturn(java.util.Optional.of(contact));

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "legacy-fmt", null, null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("E.164");
    }

    @Test
    void planMixedEmailPlusSmsFanoutBothChannels() {
        // Same recipients, both channels selected → 2 email + 2 sms = 4 targets.
        NotificationIntent intent = intent(new String[] { "email", "sms" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1", "a@x.com", "+905321111111", null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "b@x.com", "+905322222222", null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(4);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("email", "email", "sms", "sms");
    }

    // ─── Faz 23.3 PR-E.2 in-app channel tests ──────────────────────────

    @Test
    void planInAppFanoutPerSubscriber() {
        NotificationIntent intent = intent(new String[] { "in-app" }, null);
        intent.setIntentId("intent-1");
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-A", null, null, null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-B", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("in-app", "in-app");
        assertThat(targets).extracting(DeliveryTarget::recipientType)
            .containsExactly("subscriber", "subscriber");
        assertThat(targets).extracting(DeliveryTarget::recipientId)
            .containsExactly("sub-A", "sub-B");
        // targetRef = "intentId|orgId" — adapter parses for parent intent lookup
        assertThat(targets).extracting(DeliveryTarget::targetRef)
            .containsOnly("intent-1|default");
        assertThat(targets).extracting(DeliveryTarget::providerKey)
            .containsOnly("inapp-default");
    }

    @Test
    void planInAppRejectsExternalRecipient() {
        // External recipient has no inbox account; reject at planning.
        NotificationIntent intent = intent(new String[] { "in-app" }, null);
        intent.setIntentId("intent-1");
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("subscriber");
    }

    @Test
    void planInAppRejectsBlankSubscriberId() {
        NotificationIntent intent = intent(new String[] { "in-app" }, null);
        intent.setIntentId("intent-1");
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "", null, null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("subscriberId");
    }

    @Test
    void planMixedEmailPlusInAppFanoutBothChannels() {
        // Same subscribers; both channels selected → 2 email + 2 in-app = 4 targets
        NotificationIntent intent = intent(new String[] { "email", "in-app" }, null);
        intent.setIntentId("intent-mixed");
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-A", "a@x.com", null, null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-B", "b@x.com", null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(4);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("email", "email", "in-app", "in-app");
    }

    // ─── Faz 23.7 M7 T4.2 PR-W2.5 — Push fan-out tests ────────────────────

    @Test
    void planPushFanoutPerActiveEndpoint() {
        // Subscriber sub-A: 2 active endpoints (Chrome + Firefox simülasyonu)
        // → 2 DeliveryTarget; her endpoint için ayrı NotificationDelivery row.
        NotificationIntent intent = intent(new String[] { "push" }, null);
        intent.setIntentId("intent-push-1");
        com.serban.notify.domain.SubscriberPushEndpoint ep1 =
            new com.serban.notify.domain.SubscriberPushEndpoint();
        java.util.UUID epId1 = java.util.UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        ep1.setEndpointId(epId1);
        ep1.setOrgId("default");
        ep1.setSubscriberId("sub-A");
        ep1.setEndpointUrl("https://fcm.googleapis.com/fcm/send/chrome-token");
        ep1.setP256dhKey("p256dh-chrome");
        ep1.setAuthSecret("auth-chrome");

        com.serban.notify.domain.SubscriberPushEndpoint ep2 =
            new com.serban.notify.domain.SubscriberPushEndpoint();
        java.util.UUID epId2 = java.util.UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        ep2.setEndpointId(epId2);
        ep2.setOrgId("default");
        ep2.setSubscriberId("sub-A");
        ep2.setEndpointUrl("https://updates.push.services.mozilla.com/wpush/firefox-token");
        ep2.setP256dhKey("p256dh-firefox");
        ep2.setAuthSecret("auth-firefox");

        when(pushEndpointRepo.findActiveBySubscriber("default", "sub-A"))
            .thenReturn(java.util.List.of(ep1, ep2));

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-A", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(DeliveryTarget::channel)
            .containsExactly("push", "push");
        assertThat(targets).extracting(DeliveryTarget::recipientType)
            .containsExactly("subscriber", "subscriber");
        assertThat(targets).extracting(DeliveryTarget::recipientId)
            .containsExactly("sub-A", "sub-A");
        // targetRef = endpoint UUID string (WebPushAdapter repository lookup)
        assertThat(targets).extracting(DeliveryTarget::targetRef)
            .containsExactly(epId1.toString(), epId2.toString());
        assertThat(targets).extracting(DeliveryTarget::providerKey)
            .containsOnly("webpush");
    }

    @Test
    void planPushNoActiveEndpointEmitsMarkerTarget() {
        // Codex 019e4a3d P1 absorb: 0-endpoint sessiz skip pattern push-only
        // intent zombie state'e düşürüyordu. Yerine marker target üretilir
        // (PUSH_NO_ENDPOINT_TARGET_REF), DeliveryEligibilityService bunu
        // BLOCKED_NO_PUSH_ENDPOINT terminal delivery row'a çevirir.
        NotificationIntent intent = intent(new String[] { "push" }, null);
        intent.setIntentId("intent-push-empty");
        when(pushEndpointRepo.findActiveBySubscriber("default", "sub-X"))
            .thenReturn(java.util.List.of());

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-X", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).channel()).isEqualTo("push");
        assertThat(targets.get(0).recipientId()).isEqualTo("sub-X");
        assertThat(targets.get(0).targetRef())
            .isEqualTo(DeliveryPlanService.PUSH_NO_ENDPOINT_TARGET_REF);
        assertThat(targets.get(0).providerKey()).isEqualTo("webpush");
    }

    @Test
    void planPushRejectsExternalRecipient() {
        // Push subscription account-bound (browser PushManager.subscribe
        // subscriber identity'sine bağlı). External recipient → reject.
        NotificationIntent intent = intent(new String[] { "push" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("subscriber");
    }

    @Test
    void planPushRejectsBlankSubscriberId() {
        NotificationIntent intent = intent(new String[] { "push" }, null);
        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "", null, null, null, "tr-TR"
            )
        );

        assertThatThrownBy(() -> service.plan(intent, recipients))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("subscriberId");
    }

    @Test
    void planPushMultipleSubscribersEachWithOwnEndpoints() {
        // 2 subscribers × farklı endpoint sayıları (sub-A: 1, sub-B: 2)
        // → 3 toplam target. Subscriber boundary intact.
        NotificationIntent intent = intent(new String[] { "push" }, null);
        intent.setIntentId("intent-push-multi");

        com.serban.notify.domain.SubscriberPushEndpoint epA =
            new com.serban.notify.domain.SubscriberPushEndpoint();
        epA.setEndpointId(java.util.UUID.fromString("11111111-aaaa-bbbb-cccc-dddddddddddd"));
        epA.setOrgId("default");
        epA.setSubscriberId("sub-A");
        epA.setEndpointUrl("https://example.push/a");
        epA.setP256dhKey("p");
        epA.setAuthSecret("a");
        when(pushEndpointRepo.findActiveBySubscriber("default", "sub-A"))
            .thenReturn(java.util.List.of(epA));

        com.serban.notify.domain.SubscriberPushEndpoint epB1 =
            new com.serban.notify.domain.SubscriberPushEndpoint();
        epB1.setEndpointId(java.util.UUID.fromString("22222222-aaaa-bbbb-cccc-dddddddddddd"));
        epB1.setOrgId("default");
        epB1.setSubscriberId("sub-B");
        epB1.setEndpointUrl("https://example.push/b1");
        epB1.setP256dhKey("p");
        epB1.setAuthSecret("a");

        com.serban.notify.domain.SubscriberPushEndpoint epB2 =
            new com.serban.notify.domain.SubscriberPushEndpoint();
        epB2.setEndpointId(java.util.UUID.fromString("33333333-aaaa-bbbb-cccc-dddddddddddd"));
        epB2.setOrgId("default");
        epB2.setSubscriberId("sub-B");
        epB2.setEndpointUrl("https://example.push/b2");
        epB2.setP256dhKey("p");
        epB2.setAuthSecret("a");
        when(pushEndpointRepo.findActiveBySubscriber("default", "sub-B"))
            .thenReturn(java.util.List.of(epB1, epB2));

        List<SubmitIntentRequest.RecipientRef> recipients = List.of(
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-A", null, null, null, "tr-TR"
            ),
            new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "sub-B", null, null, null, "tr-TR"
            )
        );

        List<DeliveryTarget> targets = service.plan(intent, recipients);

        assertThat(targets).hasSize(3);
        assertThat(targets).extracting(DeliveryTarget::recipientId)
            .containsExactly("sub-A", "sub-B", "sub-B");
    }

    private NotificationIntent intent(String[] channels, Map<String, Object> routing) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("intent-1");
        i.setOrgId("default");
        i.setTopicKey("test.topic");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setDataClassification(NotificationIntent.DataClassification.transactional);
        i.setTemplateId("t");
        i.setTemplateVersion(1);
        i.setLocale("tr-TR");
        i.setChannels(channels);
        i.setChannelRouting(routing);
        return i;
    }
}
