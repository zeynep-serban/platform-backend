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
 * DeliveryPlanService unit test (Codex 019df9ae Q2 PARTIAL absorb).
 *
 * <p>Test edilen contract:
 * <ul>
 *   <li>email: recipient-addressed → N recipient × 1 target</li>
 *   <li>slack: target-addressed → 1 target per intent (channel_routing.slack.webhookUrl)</li>
 *   <li>webhook: target-addressed → 1 target per intent (channel_routing.webhook.targetUrl)</li>
 *   <li>missing channel routing → InvalidRequestException</li>
 *   <li>unknown channel → InvalidRequestException</li>
 * </ul>
 */
class DeliveryPlanServiceTest {

    private PiiRedactor redactor;
    private ChannelAdapterRegistry registry;
    private DeliveryPlanService service;

    @BeforeEach
    void setUp() {
        redactor = mock(PiiRedactor.class);
        registry = mock(ChannelAdapterRegistry.class);
        when(redactor.hashRecipient(anyString(), anyString(), anyString()))
            .thenReturn("hash-mock");
        // All test channels are supported by registry
        when(registry.supports("email")).thenReturn(true);
        when(registry.supports("slack")).thenReturn(true);
        when(registry.supports("webhook")).thenReturn(true);
        when(registry.supports("unknown")).thenReturn(false);
        when(registry.supportedChannels()).thenReturn(java.util.Set.of("email", "slack", "webhook"));

        // PR5: SubscriberPreferenceService dependency injected; tests provide
        // ref.email() directly so contact lookup not invoked.
        com.serban.notify.preference.SubscriberPreferenceService prefService =
            mock(com.serban.notify.preference.SubscriberPreferenceService.class);
        service = new DeliveryPlanService(redactor, registry, prefService);
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
