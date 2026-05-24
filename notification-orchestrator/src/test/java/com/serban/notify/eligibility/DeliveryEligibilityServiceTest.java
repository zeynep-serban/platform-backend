package com.serban.notify.eligibility;

import com.serban.notify.authz.AuthzClient;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.worker.WorkerMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        assertThat(decision.reason()).isEqualTo("no_tuple");
    }

    @Test
    void authzDenyEmitsMetricWithChannelAndReasonClass() {
        // Faz 23.2 v2 (Codex 019e59eb REVISE absorb): per-channel authz deny
        // counter — Layer-2 OpenFGA enforce observability. Mock WorkerMetrics
        // injected via setter; verify notify.authz.denied{channel,reason_class}
        // increment with normalized reason class.
        DeliveryEligibilityService svc = service(true, true);
        WorkerMetrics metrics = mock(WorkerMetrics.class);
        svc.setWorkerMetrics(metrics);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("no_tuple"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_BY_AUTHZ);
        // Subscriber target.channel() = "email"; raw "no_tuple" → "no_tuple"
        verify(metrics).authzDeny(eq("email"), eq("no_tuple"));
    }

    @Test
    void authzUnreachableMappedToAuthzUnreachableClass() {
        // Fail-closed path: permission-service HTTP error → AuthzDecision.deny
        // with reason "authz_unreachable". WorkerMetrics.classifyAuthzReason
        // maps to "authz_unreachable" class (distinct from no_tuple — different
        // alert semantics: connectivity issue vs missing grant).
        DeliveryEligibilityService svc = service(true, true);
        WorkerMetrics metrics = mock(WorkerMetrics.class);
        svc.setWorkerMetrics(metrics);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("authz_unreachable"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        verify(metrics).authzDeny(eq("email"), eq("authz_unreachable"));
    }

    @Test
    void authzHttp500MappedToAuthzHttpErrorClass() {
        // permission-service returns 500 → AuthzClient produces reason
        // "authz_http_500"; classifier collapses any "authz_http_*" into
        // "authz_http_error" class (cardinality-safe label).
        DeliveryEligibilityService svc = service(true, true);
        WorkerMetrics metrics = mock(WorkerMetrics.class);
        svc.setWorkerMetrics(metrics);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("authz_http_500"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        verify(metrics).authzDeny(eq("email"), eq("authz_http_error"));
    }

    @Test
    void authzDenyMetricNotEmittedWhenWorkerMetricsNull() {
        // Legacy unit-test path: WorkerMetrics not injected. Eligibility
        // service must NOT NPE when authz deny occurs without metrics bean
        // (defensive null guard).
        DeliveryEligibilityService svc = service(true, true);
        // No setWorkerMetrics call — metrics is null.
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("no_tuple"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        // No exception — null-safe path proven.
    }

    @Test
    void externalRecipientAuthzDenyEmitsMetric() {
        // Codex 019e59f3 review point #4: external path also calls Guard 3
        // (shared code branch with subscriber). Verify metric emission for
        // external recipient — locks the PR claim "subscriber + external
        // Layer-2 deny observability".
        DeliveryEligibilityService svc = service(true, true);
        WorkerMetrics metrics = mock(WorkerMetrics.class);
        svc.setWorkerMetrics(metrics);
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("no_tuple"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), externalTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_BY_AUTHZ);
        // externalTarget() uses email channel
        verify(metrics).authzDeny(eq("email"), eq("no_tuple"));
    }

    @Test
    void authzDisabledReasonClassEmitted() {
        // Codex 019e59f3 REVISE absorb: permission-service emits "authz_disabled"
        // when OpenFGA bean is absent. Verify classifyAuthzReason maps to its
        // own distinct class so SLO/alert surfaces the security-config
        // regression instead of hiding under "other" or "no_tuple".
        DeliveryEligibilityService svc = service(true, true);
        WorkerMetrics metrics = mock(WorkerMetrics.class);
        svc.setWorkerMetrics(metrics);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.deny("authz_disabled"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), subscriberTarget());

        assertThat(decision.blocked()).isTrue();
        verify(metrics).authzDeny(eq("email"), eq("authz_disabled"));
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

    @Test
    void pushNoEndpointMarkerBlocksWithBlockedNoPushEndpoint() {
        // Faz 23.7 M7 T4.2 PR-W2.5+W2.6 (Codex 019e4a3d P1 absorb):
        // DeliveryPlanService 0-endpoint subscriber için marker target
        // (targetRef=PUSH_NO_ENDPOINT_TARGET_REF) üretir. Eligibility
        // guard yakalar → BLOCKED_NO_PUSH_ENDPOINT terminal status.
        DeliveryEligibilityService svc = service(true, true);

        DeliveryTarget noEndpointMarker = new DeliveryTarget(
            "push", "subscriber", "sub-X", "rh-push-marker",
            com.serban.notify.delivery.DeliveryPlanService.PUSH_NO_ENDPOINT_TARGET_REF,
            "webpush"
        );

        var decision = svc.evaluate(intent(), externalAllowedTemplate(false), noEndpointMarker);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status())
            .isEqualTo(com.serban.notify.domain.NotificationDelivery.Status.BLOCKED_NO_PUSH_ENDPOINT);
        assertThat(decision.policy()).isEqualTo("no_push_endpoint");
        assertThat(decision.reason()).contains("no active push endpoint");
        // Adapter çağrılmaz; preference + authz check'leri de tetiklenmez
        verify(prefService, never()).evaluate(any(), anyString(), anyString());
        verify(authzClient, never())
            .check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void pushActiveEndpointPassesNoEndpointGuard() {
        // Normal push target (targetRef=endpoint UUID) marker guard'ı
        // tetiklemez; subscriber preference + authz akışına devam eder.
        DeliveryEligibilityService svc = service(true, true);
        when(prefService.evaluate(any(), anyString(), anyString()))
            .thenReturn(
                com.serban.notify.preference.SubscriberPreferenceService.PreferenceDecision
                    .allow("test_allow"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        DeliveryTarget activeEndpoint = new DeliveryTarget(
            "push", "subscriber", "sub-A", "rh-push-active",
            "11111111-2222-3333-4444-555555555555",
            "webpush"
        );

        var decision = svc.evaluate(intent(), externalAllowedTemplate(false), activeEndpoint);

        assertThat(decision.blocked()).isFalse();
        // Preference + authz check'leri tetiklendi (subscriber path)
        verify(prefService).evaluate(any(), anyString(), anyString());
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
