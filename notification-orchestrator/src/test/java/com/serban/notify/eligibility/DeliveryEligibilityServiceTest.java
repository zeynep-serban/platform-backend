package com.serban.notify.eligibility;

import com.serban.notify.authz.AuthzClient;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.preference.SubscriberPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryEligibilityService matrix test (Codex 019dfaaa PR5).
 *
 * <p>Guard chain: external policy → preference → authz. Verifies short-circuit
 * order + flag gates.
 */
class DeliveryEligibilityServiceTest {

    private SubscriberPreferenceService prefService;
    private AuthzClient authzClient;

    @BeforeEach
    void setUp() {
        prefService = mock(SubscriberPreferenceService.class);
        authzClient = mock(AuthzClient.class);
    }

    @Test
    void allGuardsPassReturnsAllow() {
        DeliveryEligibilityService svc = service(true, true);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void externalRecipientBlockedWhenTemplateInternalOnly() {
        DeliveryEligibilityService svc = service(true, true);
        var decision = svc.evaluate(intent(), externalAllowedTemplate(false), externalTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_EXTERNAL_NOT_ALLOWED);
        assertThat(decision.policy()).isEqualTo("external_not_allowed");
        // Guard short-circuits — preference + authz NOT called
        verify(prefService, never()).evaluate(any(), anyString(), anyString());
        verify(authzClient, never()).check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void preferenceDenyShortCircuits() {
        DeliveryEligibilityService svc = service(true, true);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.deny("preference_disabled"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_BY_PREFERENCE);
        assertThat(decision.policy()).isEqualTo("preference_disabled");
        // Authz NOT called (short-circuit)
        verify(authzClient, never()).check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void authzDenyAfterPreferencePass() {
        DeliveryEligibilityService svc = service(true, true);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("no_tuple"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_BY_AUTHZ);
        assertThat(decision.policy()).isEqualTo("authz_deny");
    }

    @Test
    void preferencesDisabledFlagSkipsPreferenceGuard() {
        DeliveryEligibilityService svc = service(false, true);  // preferences off
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isFalse();
        verify(prefService, never()).evaluate(any(), anyString(), anyString());
    }

    @Test
    void authzDisabledFlagSkipsAuthzGuard() {
        DeliveryEligibilityService svc = service(true, false);  // authz off
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isFalse();
        verify(authzClient, never()).check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void externalRecipientWithExternalAllowedTemplateProceedsToAuthz() {
        DeliveryEligibilityService svc = service(true, true);
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), externalTarget());

        assertThat(decision.blocked()).isFalse();
        // Preference NOT called for external (no subscriber preference row)
        verify(prefService, never()).evaluate(any(), anyString(), anyString());
        // Authz IS called
        verify(authzClient).check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private DeliveryEligibilityService service(boolean prefsOn, boolean authzOn) {
        return new DeliveryEligibilityService(prefService, authzClient, prefsOn, authzOn);
    }

    private NotificationIntent intent() {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("test");
        i.setOrgId("default");
        i.setTopicKey("auth.password-reset");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setTemplateId("auth-password-reset");
        return i;
    }

    private NotificationTemplate externalAllowedTemplate(boolean externalAllowed) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("auth-password-reset");
        t.setExternalAllowed(externalAllowed);
        return t;
    }

    private DeliveryTarget subscriberTarget() {
        return new DeliveryTarget("email", "subscriber", "1204", "rh-1", "u@x.com", "smtp-default");
    }

    private DeliveryTarget externalTarget() {
        return new DeliveryTarget("email", "external", null, "rh-ext", "ext@x.com", "smtp-default");
    }

    private DeliveryTarget channelTarget() {
        return new DeliveryTarget("slack", "channel", null, "rh-ch", "https://hooks.slack/x", "slack-default");
    }

    @Test
    void channelTargetSkipsAuthzGuard() {
        // Codex iter-1 P1 #4 absorb: slack/webhook channel-addressed targets
        // have no per-recipient principal — authz skipped.
        DeliveryEligibilityService svc = service(true, true);

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), channelTarget());

        assertThat(decision.blocked()).isFalse();
        // Authz NOT called (channel target skip)
        verify(authzClient, never()).check(anyString(), anyString(), anyString(), anyString(), anyString());
        // Preference also not called (no subscriber recipient)
        verify(prefService, never()).evaluate(any(), anyString(), anyString());
    }

    private static <T> T any() { return ArgumentMatchers.any(); }
}
