package com.serban.notify.delivery;

import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberContact;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.redaction.PiiRedactor;
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

    public DeliveryPlanService(
        PiiRedactor piiRedactor,
        ChannelAdapterRegistry adapterRegistry,
        SubscriberPreferenceService preferenceService
    ) {
        this.piiRedactor = piiRedactor;
        this.adapterRegistry = adapterRegistry;
        this.preferenceService = preferenceService;
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
                case "email" -> targets.addAll(planEmailTargets(intent, recipients));
                case "slack" -> targets.add(planSlackTarget(intent));
                case "webhook" -> targets.add(planWebhookTarget(intent));
                default -> throw new InvalidRequestException(
                    "channel '" + channel + "' planning not implemented in PR3"
                );
            }
        }
        log.debug("delivery plan: intentId={} channels={} target_count={}",
            intent.getIntentId(), java.util.Arrays.toString(intent.getChannels()), targets.size());
        return targets;
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
