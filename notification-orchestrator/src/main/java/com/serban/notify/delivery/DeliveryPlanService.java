package com.serban.notify.delivery;

import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberContact;
import com.serban.notify.domain.SubscriberPushEndpoint;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.SubscriberPushEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeliveryPlanService — channel-aware fan-out (Codex 019df9ae Q2 PARTIAL absorb).
 *
 * <p>Channel addressing semantics:
 * <ul>
 *   <li>email: recipient-addressed → N recipient × 1 target</li>
 *   <li>sms: recipient-addressed → N recipient × 1 target (Faz 23.3.1)</li>
 *   <li>in-app: recipient-addressed, subscriber-only → N subscribers × 1 target
 *       (Faz 23.3 PR-E.2)</li>
 *   <li>slack/webhook: target-addressed → 1 target per channel (recipients
 *       become audit context, not delivery rows)</li>
 * </ul>
 *
 * <p>Codex Q2 önerdiği DeliveryTarget intermediate model:
 * channel × routing-target × recipient (eligible) → N planned targets.
 * PR4 priority/fallback logic bu model üzerine kurulacak.
 */
@Service
public class DeliveryPlanService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPlanService.class);

    private final PiiRedactor piiRedactor;
    private final ChannelAdapterRegistry adapterRegistry;
    private final SubscriberPreferenceService preferenceService;
    private final SubscriberPushEndpointRepository pushEndpointRepo;

    /**
     * Default Slack webhook URL (PR3 dev/test). Production Faz 23.2'da
     * provider_config table + ESO ExternalSecret üzerinden inject.
     */
    @Value("${notify.adapters.slack.default-webhook-url:}")
    private String defaultSlackWebhookUrl;

    /**
     * Default webhook target URL (PR3 dev/test). Production: per-tenant
     * channel_routing OR provider_config.
     */
    @Value("${notify.adapters.webhook.default-target-url:}")
    private String defaultWebhookTargetUrl;

    /**
     * Default Microsoft Teams Power Automate flow URL (Faz 23.6 M7
     * T4.1.2 — Codex {@code 019e496d} AGREE). Müşteri/admin Power
     * Automate'te flow oluşturup webhook URL'i üretir; Vault'a
     * {@code kv/platform/notification-orchestrator/teams_power_automate_flow_url}
     * yolundan seedlenir; per-tenant override için
     * {@code channel_routing.teams.flowUrl} intent body.
     */
    @Value("${notify.adapters.teams.default-flow-url:}")
    private String defaultTeamsFlowUrl;

    public DeliveryPlanService(
        PiiRedactor piiRedactor,
        ChannelAdapterRegistry adapterRegistry,
        SubscriberPreferenceService preferenceService,
        SubscriberPushEndpointRepository pushEndpointRepo
    ) {
        this.piiRedactor = piiRedactor;
        this.adapterRegistry = adapterRegistry;
        this.preferenceService = preferenceService;
        this.pushEndpointRepo = pushEndpointRepo;
    }

    /**
     * Build delivery plan for an intent.
     *
     * <p>Codex 019df9ef P2 absorb: if {@code recipients} param empty/null, fall
     * back to {@link NotificationIntent#getRecipientsSnapshot()} (PR4 worker
     * path: poller sees only intent row, recipients reconstructed from snapshot).
     *
     * @param intent persisted notification_intent (status=PENDING)
     * @param recipients original recipients list (PR3 submit-time direct call;
     *                   PR4 worker passes null — uses intent.recipients_snapshot)
     * @return planned delivery targets per channel
     */
    public List<DeliveryTarget> plan(
        NotificationIntent intent,
        List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        if (recipients == null || recipients.isEmpty()) {
            recipients = deserializeSnapshot(intent.getRecipientsSnapshot());
        }
        List<DeliveryTarget> targets = new ArrayList<>();
        for (String channel : intent.getChannels()) {
            if (!adapterRegistry.supports(channel)) {
                throw new InvalidRequestException(
                    "channel '" + channel + "' has no registered adapter; supported: "
                        + adapterRegistry.supportedChannels()
                );
            }
            switch (channel) {
                case "email"  -> targets.addAll(planEmailTargets(intent, recipients));
                case "sms"    -> targets.addAll(planSmsTargets(intent, recipients));
                case "in-app" -> targets.addAll(planInAppTargets(intent, recipients));
                case "push"   -> targets.addAll(planPushTargets(intent, recipients));
                case "slack"  -> targets.add(planSlackTarget(intent));
                case "teams"  -> targets.add(planTeamsTarget(intent));
                case "webhook" -> targets.add(planWebhookTarget(intent));
                default -> throw new InvalidRequestException(
                    "channel '" + channel + "' planning not implemented"
                );
            }
        }
        log.debug("delivery plan: intentId={} channels={} target_count={}",
            intent.getIntentId(), java.util.Arrays.toString(intent.getChannels()), targets.size());
        return targets;
    }

    /** E.164 phone format (matches DTO bean validation pattern). */
    private static final java.util.regex.Pattern E164 =
        java.util.regex.Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    /**
     * SMS: recipient-addressed; one target per eligible recipient
     * (Faz 23.3.1 — Production MVP geniş scope).
     *
     * <p>Target ref: E.164 phone number (e.g. +905321234567); SMS provider
     * adapter strips leading "+" before sending to provider endpoint.
     *
     * <p>Subscriber type: phone resolved from {@link SubscriberContact} (PR5
     * projection). Optional explicit {@code ref.phone()} fallback (test fixture
     * / external override).
     *
     * <p>External type: {@code ref.phone()} required; DTO bean validation
     * already enforces E.164 pattern.
     *
     * <p>Plan-time E.164 validation (Codex iter-1 P2 absorb): subscriber
     * contact projection may carry legacy/non-E.164 phone (Workcube ETL
     * source). Fail-fast at planning time so PII never leaks into adapter
     * error path.
     */
    private List<DeliveryTarget> planSmsTargets(
        NotificationIntent intent, List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        List<DeliveryTarget> result = new ArrayList<>(recipients.size());
        for (SubmitIntentRequest.RecipientRef ref : recipients) {
            String type = ref.type().name();
            String phone;
            String hashInput;
            if (ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber) {
                if (ref.phone() != null && !ref.phone().isBlank()) {
                    phone = ref.phone();
                } else {
                    java.util.Optional<SubscriberContact> contact = preferenceService.findContact(
                        intent.getOrgId(), ref.subscriberId()
                    );
                    if (contact.isEmpty() || contact.get().getPhone() == null
                        || contact.get().getPhone().isBlank()) {
                        throw new InvalidRequestException(
                            "subscriber " + ref.subscriberId() + " has no phone contact "
                                + "(orgId=" + intent.getOrgId() + ") and recipient.phone "
                                + "not provided"
                        );
                    }
                    phone = contact.get().getPhone();
                }
                hashInput = ref.subscriberId();
            } else {
                if (ref.phone() == null || ref.phone().isBlank()) {
                    throw new InvalidRequestException(
                        "external recipient requires phone (E.164) for SMS channel"
                    );
                }
                phone = ref.phone();
                hashInput = ref.phone();
            }
            // Codex iter-1 P2 absorb: plan-time E.164 validation (fail-fast,
            // no raw phone in adapter error path even when contact is bad).
            if (!E164.matcher(phone).matches()) {
                String safeRef = ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber
                    ? "subscriber " + ref.subscriberId()
                    : "external recipient";
                throw new InvalidRequestException(
                    safeRef + " has invalid phone format (must be E.164: + + 8-15 digits) "
                        + "for SMS channel"
                );
            }
            String hash = piiRedactor.hashRecipient(intent.getOrgId(), type, hashInput);
            // Faz 23.3 multi-provider (Codex `019e3f82` absorb #2): plan-time
            // providerKey "sms" (failover-aware placeholder) — gerçek provider
            // SmsAdapter failover sonucu DeliveryAttemptResult.actualProviderKey
            // ile runtime'da belirlenir, DeliveryDispatchService persist eder.
            //
            // Faz 23.3.2 PR-A3.1 (Codex thread 019e4514): routingMetadata
            // SMS-specific channel hints — JetSmsProvider topic/template
            // allowlist'inden VFO/VF channel select. Map.of() null-safe değil
            // (Codex absorb); putIfNotBlank helper kullanılır.
            java.util.Map<String, Object> routingMeta = new java.util.LinkedHashMap<>();
            if (intent.getSeverity() != null) {
                routingMeta.put("severity", intent.getSeverity().name());
            }
            putIfNotBlank(routingMeta, "topic_key", intent.getTopicKey());
            putIfNotBlank(routingMeta, "template_id", intent.getTemplateId());
            result.add(new DeliveryTarget(
                "sms", type, ref.subscriberId(), hash, phone, "sms", routingMeta
            ));
        }
        return result;
    }

    /** Null/blank guard — Map.of() null reject eder; Codex P3 absorb. */
    private static void putIfNotBlank(java.util.Map<String, Object> map,
                                      String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * In-app: recipient-addressed; subscriber-only (Faz 23.3 PR-E.2).
     *
     * <p>External recipient type rejected — in-app inbox requires subscriber
     * identity (no inbox without an account). DTO-side bean validation does
     * not enforce this; planning-time guard covers it explicitly.
     *
     * <p>Target ref encoding: {@code "intentId|orgId"} — InAppInboxAdapter
     * parses to look up parent intent (topic_key + severity) for the inbox
     * row insert. Pipe ('|') is invalid in both intent_id (VARCHAR(64),
     * UUID-like) and org_id (alphanumeric) so collision-safe.
     */
    private List<DeliveryTarget> planInAppTargets(
        NotificationIntent intent, List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        List<DeliveryTarget> result = new ArrayList<>(recipients.size());
        for (SubmitIntentRequest.RecipientRef ref : recipients) {
            if (ref.type() != SubmitIntentRequest.RecipientRef.Type.subscriber) {
                throw new InvalidRequestException(
                    "in-app channel requires subscriber recipient type "
                        + "(external recipients have no inbox account)"
                );
            }
            if (ref.subscriberId() == null || ref.subscriberId().isBlank()) {
                throw new InvalidRequestException(
                    "in-app channel: subscriberId required (was null/blank)"
                );
            }
            String hash = piiRedactor.hashRecipient(
                intent.getOrgId(), ref.type().name(), ref.subscriberId()
            );
            // targetRef = "intentId|orgId" — adapter parses for parent intent lookup.
            String targetRef = intent.getIntentId() + "|" + intent.getOrgId();
            result.add(new DeliveryTarget(
                "in-app", ref.type().name(), ref.subscriberId(), hash, targetRef, "inapp-default"
            ));
        }
        return result;
    }

    /**
     * Marker target ref kullanılan no-endpoint planı için
     * (Faz 23.7 M7 T4.2 PR-W2.5+W2.6 — Codex {@code 019e4a3d} P1 absorb).
     *
     * <p>Subscriber'ın aktif endpoint kayıtı yokken push channel hâlâ
     * intent.channels içinde olduğunda zombie state'e (PROCESSING + lease
     * null + boş delivery list) düşmesin diye plan-time'da bu sabit
     * targetRef ile bir DeliveryTarget üretilir. {@code DeliveryEligibilityService}
     * bunu yakalar → {@code BLOCKED_NO_PUSH_ENDPOINT} terminal delivery
     * row + audit event. Adapter çağrılmaz; raw endpoint detayı audit'e
     * girmez.
     */
    public static final String PUSH_NO_ENDPOINT_TARGET_REF = "no-endpoint";

    /**
     * Push: recipient-addressed, subscriber-only, multi-endpoint fan-out
     * (Faz 23.7 M7 T4.2 PR-W2.5+W2.6 — Codex {@code 019e49e7} P5 plan
     * AGREE + {@code 019e4a3d} P1 absorb).
     *
     * <p>Pattern: subscriber → 1..N active push endpoints (browser/device
     * başına ayrı endpoint kayıtı). Endpoint başına ayrı
     * {@link DeliveryTarget} emit edilir; her endpoint için ayrı
     * NotificationDelivery row (PR4 worker bağımsız retry/backoff state'i
     * tutar). Şu an scope: browser-only (Web Push Protocol RFC 8030);
     * mobile FCM/APNS Faz 22.2 dep, scope dışı.
     *
     * <p>External recipient type rejected at submit gate
     * ({@code IntentSubmissionService.validateRecipientsAndChannels}) —
     * push subscription browser-side {@code PushManager.subscribe()} ile
     * üretilir, account-bound. Planning-time fallback guard burada da
     * tutuluyor (defense-in-depth).
     *
     * <p>0-endpoint subscriber (Codex 019e4a3d P1 absorb): zombie intent
     * state'i (push-only intent + 0 endpoint → PROCESSING limbo) önlemek
     * için marker target üretilir ({@code targetRef =
     * PUSH_NO_ENDPOINT_TARGET_REF}). {@link
     * com.serban.notify.eligibility.DeliveryEligibilityService} bu marker'ı
     * yakalar → {@code BLOCKED_NO_PUSH_ENDPOINT} terminal delivery row.
     * Multi-channel intent'te (push + email) email tarafı normal akar.
     *
     * <p>Target ref: endpoint UUID string (active path) veya
     * {@code PUSH_NO_ENDPOINT_TARGET_REF} (no-endpoint marker). WebPushAdapter
     * UUID parse hatası fırlatır ama oraya hiç gelmemeli — Eligibility
     * guard adapter öncesi yakalar.
     *
     * <p>Recipient hash: endpoint-level
     * ({@code orgId + "push_endpoint" + subscriberId + "|" + endpoint_id}) —
     * multi-endpoint subscriber için per-device delivery row'larını
     * audit'te ayrıştırmak için. Codex 019e4a3d P2 absorb: hashRecipient'a
     * geçen kategori adı {@code "push_endpoint"} (önceki "subscriber"
     * domain yorum/kod mismatch'i düzeltildi). No-endpoint marker
     * subscriber-level hash kullanır ({@code "push" + subscriberId}).
     */
    private List<DeliveryTarget> planPushTargets(
        NotificationIntent intent, List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        List<DeliveryTarget> result = new ArrayList<>();
        for (SubmitIntentRequest.RecipientRef ref : recipients) {
            if (ref.type() != SubmitIntentRequest.RecipientRef.Type.subscriber) {
                throw new InvalidRequestException(
                    "push channel requires subscriber recipient type "
                        + "(external recipients have no push subscription)"
                );
            }
            if (ref.subscriberId() == null || ref.subscriberId().isBlank()) {
                throw new InvalidRequestException(
                    "push channel: subscriberId required (was null/blank)"
                );
            }
            List<SubscriberPushEndpoint> endpoints =
                pushEndpointRepo.findActiveBySubscriber(intent.getOrgId(), ref.subscriberId());
            if (endpoints.isEmpty()) {
                // Codex 019e4a3d P1 absorb: marker target ile zombie
                // state'i önle. Eligibility guard
                // BLOCKED_NO_PUSH_ENDPOINT'e çevirir + audit row.
                String hash = piiRedactor.hashRecipient(
                    intent.getOrgId(), "push", ref.subscriberId()
                );
                result.add(new DeliveryTarget(
                    "push",
                    ref.type().name(),
                    ref.subscriberId(),
                    hash,
                    PUSH_NO_ENDPOINT_TARGET_REF,
                    "webpush"
                ));
                log.debug("push plan: subscriber has no active endpoints; emit "
                    + "no-endpoint marker (eligibility guard will BLOCKED)");
                continue;
            }
            for (SubscriberPushEndpoint endpoint : endpoints) {
                // Codex 019e4a3d P2 absorb: hash domain "push_endpoint"
                // (subscriber-level "push" marker ile ayrışır)
                String hashInput = ref.subscriberId() + "|" + endpoint.getEndpointId();
                String hash = piiRedactor.hashRecipient(
                    intent.getOrgId(), "push_endpoint", hashInput
                );
                result.add(new DeliveryTarget(
                    "push",
                    ref.type().name(),
                    ref.subscriberId(),
                    hash,
                    endpoint.getEndpointId().toString(),
                    "webpush"
                ));
            }
        }
        log.debug("push plan: intentId={} recipient_count={} target_count={}",
            intent.getIntentId(), recipients.size(), result.size());
        return result;
    }

    /** Email: recipient-addressed; one target per eligible recipient. */
    private List<DeliveryTarget> planEmailTargets(
        NotificationIntent intent, List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        List<DeliveryTarget> result = new ArrayList<>(recipients.size());
        for (SubmitIntentRequest.RecipientRef ref : recipients) {
            String type = ref.type().name();
            String address;
            String hashInput;
            if (ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber) {
                // PR5 absorb (Codex 019dfaaa lock-in #4): subscriber email
                // lookup via SubscriberContact projection (planning enrichment).
                // Fallback to ref.email() if explicitly provided (test fixture
                // path); else look up from subscriber_contact.
                if (ref.email() != null && !ref.email().isBlank()) {
                    address = ref.email();
                } else {
                    java.util.Optional<SubscriberContact> contact = preferenceService.findContact(
                        intent.getOrgId(), ref.subscriberId()
                    );
                    if (contact.isEmpty() || contact.get().getEmail() == null
                        || contact.get().getEmail().isBlank()) {
                        throw new InvalidRequestException(
                            "subscriber " + ref.subscriberId() + " has no email contact "
                                + "(orgId=" + intent.getOrgId() + ") and recipient.email "
                                + "not provided"
                        );
                    }
                    address = contact.get().getEmail();
                }
                hashInput = ref.subscriberId();
            } else {
                if (ref.email() == null || ref.email().isBlank()) {
                    throw new InvalidRequestException(
                        "external recipient requires email for SMTP channel"
                    );
                }
                address = ref.email();
                hashInput = ref.email();
            }
            String hash = piiRedactor.hashRecipient(intent.getOrgId(), type, hashInput);
            result.add(new DeliveryTarget(
                "email", type, ref.subscriberId(), hash, address, "smtp-default"
            ));
        }
        return result;
    }

    /** Slack: target-addressed; single target per intent (provider config / routing). */
    private DeliveryTarget planSlackTarget(NotificationIntent intent) {
        Map<String, Object> routing = intent.getChannelRouting();
        String url = routingString(routing, "slack.webhookUrl", defaultSlackWebhookUrl);
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException(
                "slack channel requires channel_routing.slack.webhookUrl or "
                    + "notify.adapters.slack.default-webhook-url config"
            );
        }
        // Slack target opaque (no recipient identifier); audit hash org-namespaced channel
        String hash = piiRedactor.hashRecipient(intent.getOrgId(), "channel", "slack");
        return new DeliveryTarget("slack", "channel", null, hash, url, "slack-default");
    }

    /**
     * Microsoft Teams: target-addressed via Power Automate flow webhook
     * (Faz 23.6 M7 T4.1.2 — Codex {@code 019e496d} AGREE).
     * Single target per intent — same channel-addressed pattern as
     * Slack/webhook. Threading + Graph API path deferred.
     */
    private DeliveryTarget planTeamsTarget(NotificationIntent intent) {
        Map<String, Object> routing = intent.getChannelRouting();
        String url = routingString(routing, "teams.flowUrl", defaultTeamsFlowUrl);
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException(
                "teams channel requires channel_routing.teams.flowUrl or "
                    + "notify.adapters.teams.default-flow-url config"
            );
        }
        String hash = piiRedactor.hashRecipient(intent.getOrgId(), "channel", "teams");
        return new DeliveryTarget("teams", "channel", null, hash, url, "teams-default");
    }

    /** Webhook: target-addressed. */
    private DeliveryTarget planWebhookTarget(NotificationIntent intent) {
        Map<String, Object> routing = intent.getChannelRouting();
        String url = routingString(routing, "webhook.targetUrl", defaultWebhookTargetUrl);
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException(
                "webhook channel requires channel_routing.webhook.targetUrl or "
                    + "notify.adapters.webhook.default-target-url config"
            );
        }
        String hash = piiRedactor.hashRecipient(intent.getOrgId(), "channel", "webhook");
        return new DeliveryTarget("webhook", "channel", null, hash, url, "webhook-default");
    }

    /**
     * Deserialize recipients snapshot (List of maps) → List of RecipientRef
     * (Codex 019df9ef P2 absorb — PR4 worker reconstruction path).
     */
    private static List<SubmitIntentRequest.RecipientRef> deserializeSnapshot(
        List<Map<String, Object>> snapshot
    ) {
        if (snapshot == null || snapshot.isEmpty()) return List.of();
        List<SubmitIntentRequest.RecipientRef> out = new ArrayList<>(snapshot.size());
        for (Map<String, Object> entry : snapshot) {
            String typeStr = (String) entry.get("type");
            SubmitIntentRequest.RecipientRef.Type type =
                SubmitIntentRequest.RecipientRef.Type.valueOf(typeStr);
            out.add(new SubmitIntentRequest.RecipientRef(
                type,
                (String) entry.get("subscriberId"),
                (String) entry.get("email"),
                (String) entry.get("phone"),
                (String) entry.get("name"),
                (String) entry.get("locale")
            ));
        }
        return out;
    }

    /** Best-effort lookup of nested key (e.g., "slack.webhookUrl") in JSON. */
    @SuppressWarnings("unchecked")
    private static String routingString(Map<String, Object> routing, String dotPath, String fallback) {
        if (routing == null) return fallback;
        Object current = routing;
        for (String part : dotPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return fallback;
            current = ((Map<String, Object>) map).get(part);
        }
        return current instanceof String s ? s : fallback;
    }
}
