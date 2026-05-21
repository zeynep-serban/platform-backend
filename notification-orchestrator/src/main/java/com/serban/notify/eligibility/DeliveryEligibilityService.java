package com.serban.notify.eligibility;

import com.serban.notify.authz.AuthzClient;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.worker.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DeliveryEligibilityService — pre-dispatch guard chain (Codex 019dfaaa PR5 absorb).
 *
 * <p>Codex absorb:
 * <ul>
 *   <li>Q1 PARTIAL: extracted small guard service (NOT inlined into DispatchService);
 *       deterministic sequential guard chain</li>
 *   <li>Q5 REVISE: external policy in dispatch guard layer (not submit-time)</li>
 *   <li>Lock-in #5: BLOCKED_* delivery row + DELIVERY_BLOCKED audit (NOT
 *       DELIVERY_ATTEMPTED — adapter not invoked)</li>
 * </ul>
 *
 * <p>Sequential guard chain:
 * <ol>
 *   <li><b>External policy</b>: template.external_allowed=false AND recipient
 *       is external → BLOCKED_EXTERNAL_NOT_ALLOWED</li>
 *   <li><b>Preference</b>: subscriber preference disabled (no critical bypass)
 *       → BLOCKED_BY_PREFERENCE</li>
 *   <li><b>Authz</b>: permission-service deny → BLOCKED_BY_AUTHZ</li>
 * </ol>
 *
 * <p>Feature flags (test profile false; production true):
 * <ul>
 *   <li>{@code notify.preferences.enabled} — preference guard</li>
 *   <li>{@code notify.authz.enabled} — authz guard</li>
 * </ul>
 * External policy ALWAYS enforced (template.external_allowed is invariant data).
 */
@Service
public class DeliveryEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEligibilityService.class);

    private final SubscriberPreferenceService preferenceService;
    private final AuthzClient authzClient;
    private final boolean preferencesEnabled;
    private final boolean authzEnabled;
    private WorkerMetrics workerMetrics;  // optional — Codex Q6 absorb
    // Faz 23.8 M7 T4.3.b — email suppression guard (Codex 019e492f).
    // Optional setter injection: nullable in legacy unit tests; Spring
    // populates in production context. Guard skipped for non-email
    // channels (SMS suppression is separate scope).
    private com.serban.notify.suppression.EmailSuppressionService emailSuppressionService;

    public DeliveryEligibilityService(
        SubscriberPreferenceService preferenceService,
        AuthzClient authzClient,
        @Value("${notify.preferences.enabled:true}") boolean preferencesEnabled,
        @Value("${notify.authz.enabled:true}") boolean authzEnabled
    ) {
        this.preferenceService = preferenceService;
        this.authzClient = authzClient;
        this.preferencesEnabled = preferencesEnabled;
        this.authzEnabled = authzEnabled;
        log.info("DeliveryEligibilityService activated: preferences={} authz={}",
            preferencesEnabled, authzEnabled);
    }

    /**
     * Optional EmailSuppressionService injection (Faz 23.8 M7 T4.3.b).
     * Setter injection — null in legacy unit tests; Spring populates in
     * production context. Guard skipped silently when the service is
     * absent (suppression feature is additive; existing behavior
     * preserved).
     */
    @Autowired(required = false)
    public void setEmailSuppressionService(
        com.serban.notify.suppression.EmailSuppressionService service
    ) {
        this.emailSuppressionService = service;
    }

    /**
     * Optional WorkerMetrics injection (Codex 019dfae5 PR-B Q6 absorb).
     * Setter injection — null in legacy unit tests; Spring populates in
     * production context.
     */
    @Autowired(required = false)
    public void setWorkerMetrics(WorkerMetrics workerMetrics) {
        this.workerMetrics = workerMetrics;
    }

    /**
     * Evaluate eligibility for a delivery target.
     *
     * @param intent   intent context (orgId, severity, topic_key)
     * @param template template definition (external_allowed)
     * @param target   delivery target (recipient_type, recipient_id, channel)
     * @return EligibilityDecision (status=null if ALLOWED; else BLOCKED_*)
     */
    public EligibilityDecision evaluate(
        NotificationIntent intent, NotificationTemplate template, DeliveryTarget target
    ) {
        // Guard 0a (Faz 23.7 M7 T4.2 PR-W2.5+W2.6 — Codex 019e4a3d P1):
        // push channel + no-endpoint marker → BLOCKED_NO_PUSH_ENDPOINT.
        // DeliveryPlanService 0-endpoint subscriber için marker target
        // üretir (zombie intent state'i önle; eski "sessiz skip" pattern'i
        // push-only intent'leri PROCESSING limbo'da bırakıyordu). Adapter
        // çağrılmaz; raw endpoint detayı audit'e girmez.
        if ("push".equals(target.channel())
            && com.serban.notify.delivery.DeliveryPlanService.PUSH_NO_ENDPOINT_TARGET_REF
                .equals(target.targetRef())) {
            return EligibilityDecision.blocked(
                NotificationDelivery.Status.BLOCKED_NO_PUSH_ENDPOINT,
                "no_push_endpoint",
                "subscriber has no active push endpoint (browser/device not "
                    + "subscribed or all endpoints soft-deleted)"
            );
        }

        // Guard 0 (Faz 23.8 M7 T4.3.b): email suppression list match
        // Provider IP reputation koruma — hard-bounce / spam-complaint /
        // soft-bounce-repeated alıcılar SMTP edge'e gönderilmez. Critical
        // bypass YOK (Codex 019e492f Q3 absorb): permanent reasons OTP/
        // security mesajları için bile bypass edilmez (yanlış adrese
        // OTP göndermek = reputation tarama; mesaj zaten teslim olmaz).
        if (emailSuppressionService != null
            && "email".equals(target.channel())
            && target.recipientHash() != null) {
            String orgId = intent.getOrgId();
            var suppressed = emailSuppressionService.isCurrentlyActive(orgId, target.recipientHash());
            if (suppressed.isPresent()) {
                var row = suppressed.get();
                // Locale.ROOT — Turkish default locale dotless-I rule would
                // mangle SPAM_COMPLAINT.lowercase() into "spam_complaınt"
                // (note dotted small letter "ı" instead of "i"), breaking
                // policy keys + downstream string matching.
                return EligibilityDecision.blocked(
                    NotificationDelivery.Status.BLOCKED_BY_SUPPRESSION,
                    "email_suppression_" + row.getReason().name().toLowerCase(java.util.Locale.ROOT),
                    "email suppression match: reason=" + row.getReason()
                        + " bounce_count=" + row.getBounceCount()
                );
            }
        }

        // Guard 1: external policy
        if (isExternalRecipient(target) && !template.isExternalAllowed()) {
            return EligibilityDecision.blocked(
                NotificationDelivery.Status.BLOCKED_EXTERNAL_NOT_ALLOWED,
                "external_not_allowed",
                "template.external_allowed=false; external recipient blocked"
            );
        }

        // Guard 2: preference (subscriber only — external recipients have no preference row)
        if (preferencesEnabled && isSubscriberRecipient(target) && target.recipientId() != null) {
            var pref = preferenceService.evaluate(intent, target.channel(), target.recipientId());
            if (!pref.allowed()) {
                return EligibilityDecision.blocked(
                    NotificationDelivery.Status.BLOCKED_BY_PREFERENCE,
                    "preference_disabled",
                    pref.reason()
                );
            }
        }

        // Guard 3: authz — Codex 019dfaaa P1 #4 absorb:
        // channel-addressed targets (slack/webhook) have no meaningful per-recipient
        // principal — skip authz. Subscriber/external have authz tuples.
        // PR5 D29 scope: subscriber + external authorization. Channel-level authz
        // (slack workspace, webhook endpoint) → Faz 23.2 v2.
        if (!authzEnabled && !isChannelRecipient(target)) {
            // Codex 019dfae5 PR-B Q6 absorb: authz disabled bypass counter
            // (production alert: notify.authz.disabled.bypass rate > 0 → CRITICAL)
            if (workerMetrics != null) {
                workerMetrics.authzBypass(target.channel());
            }
        }
        if (authzEnabled && !isChannelRecipient(target)) {
            String principalType = isSubscriberRecipient(target) ? "subscriber" : "external";
            String principalId = isSubscriberRecipient(target)
                ? target.recipientId()
                : target.recipientHash();  // external uses recipient_hash as opaque id
            var decision = authzClient.check(
                principalType, principalId,
                "can_receive", "template", intent.getTemplateId()
            );
            if (!decision.allowed()) {
                return EligibilityDecision.blocked(
                    NotificationDelivery.Status.BLOCKED_BY_AUTHZ,
                    "authz_deny",
                    decision.reason()
                );
            }
        }

        return EligibilityDecision.allow();
    }

    private static boolean isChannelRecipient(DeliveryTarget target) {
        return "channel".equals(target.recipientType());
    }

    private static boolean isExternalRecipient(DeliveryTarget target) {
        return "external".equals(target.recipientType());
    }

    private static boolean isSubscriberRecipient(DeliveryTarget target) {
        return "subscriber".equals(target.recipientType());
    }

    /**
     * Eligibility decision.
     *
     * @param blocked false → ALLOWED (caller proceeds to adapter.send)
     * @param status  BLOCKED_* status (only valid if blocked=true)
     * @param policy  short policy identifier (for audit/metrics)
     * @param reason  human-readable detail
     */
    public record EligibilityDecision(
        boolean blocked,
        NotificationDelivery.Status status,
        String policy,
        String reason
    ) {
        public static EligibilityDecision allow() {
            return new EligibilityDecision(false, null, null, null);
        }
        public static EligibilityDecision blocked(
            NotificationDelivery.Status status, String policy, String reason
        ) {
            return new EligibilityDecision(true, status, policy, reason);
        }
    }
}
