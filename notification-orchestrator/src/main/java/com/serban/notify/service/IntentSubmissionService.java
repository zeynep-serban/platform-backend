package com.serban.notify.service;

import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.IntakeCapacityExceededException;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.template.TemplateResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * IntentSubmissionService — orchestrator core for Faz 23.1 PR2 (Codex 019df9ae absorb).
 *
 * <p>Submit pipeline (single transaction):
 * <ol>
 *   <li>Bounded intake check (intake.maxPending vs PENDING count) — Codex Q3</li>
 *   <li>Idempotency advisory lock + active key lookup (Codex Q1)</li>
 *   <li>If duplicate: return original intent_id (REPLAYED)</li>
 *   <li>Template resolve only — no render (Codex Q2)</li>
 *   <li>Compute recipient_hash for first recipient (audit primary)</li>
 *   <li>NotificationIntent INSERT (status=PENDING)</li>
 *   <li>IdempotencyKey INSERT (24h TTL)</li>
 *   <li>AuditEventPublisher INTENT_CREATED (PII-redacted)</li>
 *   <li>Return ACCEPTED + tracking URL</li>
 * </ol>
 *
 * <p>dispatch.enabled=false: status PENDING'de kalır, worker yok (PR4).
 * Channel adapter yok (PR3). Render yok (PR3).
 */
@Service
public class IntentSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(IntentSubmissionService.class);

    private final IdempotencyService idempotencyService;
    private final TemplateResolver templateResolver;
    private final NotificationIntentRepository intentRepository;
    private final AuditEventPublisher auditPublisher;
    private final PiiRedactor piiRedactor;
    private final NotifyConfig config;

    public IntentSubmissionService(
        IdempotencyService idempotencyService,
        TemplateResolver templateResolver,
        NotificationIntentRepository intentRepository,
        AuditEventPublisher auditPublisher,
        PiiRedactor piiRedactor,
        NotifyConfig config
    ) {
        this.idempotencyService = idempotencyService;
        this.templateResolver = templateResolver;
        this.intentRepository = intentRepository;
        this.auditPublisher = auditPublisher;
        this.piiRedactor = piiRedactor;
        this.config = config;
    }

    /**
     * Submit notification intent transactionally.
     *
     * @param request validated DTO from controller
     * @return ACCEPTED if new, REPLAYED if duplicate idempotency_key
     */
    /**
     * PR2 Kernel allowed channels (Codex post-impl bulgu #4 absorb).
     * PR3+'da SMTP/Slack/Webhook adapter'lar geldikçe set genişletilir.
     * push-fcm, push-apns, web-push, teams, whatsapp, voice PR3-23.X
     * tier'larında. Faz 23.3.1: sms (NetGSM) eklendi.
     * Faz 23.3 PR-E.2: in-app (inbox) eklendi.
     */
    private static final java.util.Set<String> PR2_ALLOWED_CHANNELS =
        java.util.Set.of("email", "sms", "in-app", "slack", "webhook");

    @Transactional
    public SubmitIntentResponse submit(SubmitIntentRequest request) {
        // Step 0: Recipient + channel validation (Codex post-impl bulgu #3, #4)
        validateRecipientsAndChannels(request);

        // Step 1: Idempotency advisory lock + lookup ÖNCE (Codex post-impl bulgu #2).
        // Eski sıra (capacity check ÖNCE) duplicate key'i 503 ile bloklayabilirdi —
        // idempotent client retry/backpressure kontratını bozar. Idempotent replay
        // kapasiteden bağımsız REPLAYED dönmeli.
        Optional<String> existingIntentId =
            idempotencyService.findActiveOriginal(request.orgId(), request.idempotencyKey());
        if (existingIntentId.isPresent()) {
            log.info("idempotency replay: orgId={} key={} originalIntentId={}",
                request.orgId(), request.idempotencyKey(), existingIntentId.get());
            return SubmitIntentResponse.replayed(existingIntentId.get());
        }

        // Step 2: Bounded intake check sadece YENİ intent için (Codex Q3 PARTIAL)
        long pendingCount = intentRepository.countByStatus(NotificationIntent.Status.PENDING);
        if (pendingCount >= config.intake().maxPending()) {
            throw new IntakeCapacityExceededException(
                "intake.maxPending exceeded: " + pendingCount + " / " + config.intake().maxPending()
                    + " (dispatch.enabled=" + config.dispatch().enabled() + ")"
            );
        }

        // Step 3: Template resolve only (Codex Q2)
        NotificationTemplate resolved = templateResolver.resolve(
            request.template().templateId(),
            request.template().locale(),
            request.template().version()
        );

        // Step 4: Compute primary recipient hash (audit single primary; full
        // fan-out to delivery rows PR3+'da)
        SubmitIntentRequest.RecipientRef primary = request.recipients().get(0);
        String recipientHash = computeRecipientHash(request.orgId(), primary);

        // Step 5: Persist intent
        NotificationIntent intent = mapToEntity(request, resolved);
        intentRepository.save(intent);

        // Step 6: Register idempotency key
        idempotencyService.registerKey(request.orgId(), request.idempotencyKey(), request.intentId());

        // Step 7: Audit event INTENT_CREATED (PII-redacted via whitelist)
        auditPublisher.publishIntentCreated(intent, recipientHash, primary.type().name());

        log.info("intent accepted: intentId={} orgId={} topic={} severity={} "
            + "channels={} dispatch.enabled={}",
            request.intentId(), request.orgId(), request.topicKey(),
            request.severity(), request.channels(), config.dispatch().enabled());

        return SubmitIntentResponse.accepted(request.intentId());
    }

    /** Build NotificationIntent from validated request + resolved template. */
    private NotificationIntent mapToEntity(SubmitIntentRequest request, NotificationTemplate template) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(request.intentId());
        intent.setCorrelationId(request.correlationId());
        intent.setOrgId(request.orgId());
        intent.setTopicKey(request.topicKey());
        intent.setSeverity(request.severity());
        intent.setDataClassification(request.dataClassification());
        intent.setPayload(request.payload());
        intent.setTemplateId(template.getTemplateId());
        intent.setTemplateVersion(template.getVersion());  // resolved version
        intent.setLocale(template.getLocale());            // resolved locale (fallback chain)
        intent.setChannels(request.channels().toArray(new String[0]));
        intent.setChannelRouting(request.channelRouting());
        intent.setScheduledAt(request.scheduledAt());
        intent.setExpireAt(request.expireAt());
        intent.setMetadata(request.metadata());
        intent.setPreferenceOverride(request.preferenceOverride());
        // Codex 019df9ef P2 absorb: persist recipients snapshot for PR4 worker
        // email N-target reconstruction.
        intent.setRecipientsSnapshot(serializeRecipients(request.recipients()));
        intent.setStatus(NotificationIntent.Status.PENDING);
        return intent;
    }

    private static java.util.List<java.util.Map<String, Object>> serializeRecipients(
        java.util.List<SubmitIntentRequest.RecipientRef> recipients
    ) {
        if (recipients == null) return java.util.List.of();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>(recipients.size());
        for (SubmitIntentRequest.RecipientRef r : recipients) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("type", r.type().name());
            if (r.subscriberId() != null) entry.put("subscriberId", r.subscriberId());
            if (r.email() != null) entry.put("email", r.email());
            if (r.phone() != null) entry.put("phone", r.phone());
            if (r.name() != null) entry.put("name", r.name());
            if (r.locale() != null) entry.put("locale", r.locale());
            out.add(entry);
        }
        return out;
    }

    private String computeRecipientHash(String orgId, SubmitIntentRequest.RecipientRef ref) {
        String type = ref.type().name();
        String value;
        if (ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber) {
            value = ref.subscriberId();  // validated non-null upstream
        } else if (ref.email() != null && !ref.email().isBlank()) {
            value = ref.email();
        } else {
            value = ref.phone();  // validated non-null upstream
        }
        return piiRedactor.hashRecipient(orgId, type, value);
    }

    /**
     * Recipient + channel validation (Codex post-impl bulgu #3, #4 absorb).
     *
     * <p>Bulgu #3: Bean Validation DTO seviyesinde type-specific zorunluluk
     * yetmiyordu — subscriber için subscriberId, external için email/phone
     * birinin zorunlu. Service-layer cross-field check.
     *
     * <p>Bulgu #4: Channel set kilitlenmiş — PR2'de email/slack/webhook only;
     * PR3+'da adapter'lar geldikçe set genişler. Bilinmeyen channel reject
     * (silent persist sonra delivery time fail önler).
     *
     * <p>External recipient + template.externalAllowed=false → reject (PR5'e
     * kadar source-service authority yok).
     */
    private void validateRecipientsAndChannels(SubmitIntentRequest request) {
        // Channel allow-set check (PR2 kernel)
        for (String channel : request.channels()) {
            if (!PR2_ALLOWED_CHANNELS.contains(channel)) {
                throw new com.serban.notify.exception.InvalidRequestException(
                    "channel '" + channel + "' not supported in PR2 kernel; allowed: "
                        + PR2_ALLOWED_CHANNELS
                );
            }
        }

        // Channel-specific addressing requirements (Codex iter-2 P1 absorb).
        // External recipient with email-only must NOT pass submit gate when
        // SMS channel selected — would later fail at DeliveryPlanService and
        // leave intent in PENDING limbo. Same for phone-only + email channel.
        // Faz 23.3 PR-E.2: in-app channel requires subscriber type (no
        // inbox without account); reject external for in-app at submit gate.
        boolean hasSmsChannel = request.channels().contains("sms");
        boolean hasEmailChannel = request.channels().contains("email");
        boolean hasInAppChannel = request.channels().contains("in-app");

        // Recipient type-specific zorunluluk
        for (SubmitIntentRequest.RecipientRef ref : request.recipients()) {
            if (ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber) {
                if (ref.subscriberId() == null || ref.subscriberId().isBlank()) {
                    throw new com.serban.notify.exception.InvalidRequestException(
                        "recipient.subscriberId required when type=subscriber"
                    );
                }
            } else if (ref.type() == SubmitIntentRequest.RecipientRef.Type.external) {
                // Faz 23.3 PR-E.2: in-app channel forbids external recipient
                // (no inbox without subscriber identity). Reject at submit gate.
                if (hasInAppChannel) {
                    throw new com.serban.notify.exception.InvalidRequestException(
                        "in-app channel requires subscriber recipient type "
                            + "(external recipients have no inbox account)"
                    );
                }
                boolean hasEmail = ref.email() != null && !ref.email().isBlank();
                boolean hasPhone = ref.phone() != null && !ref.phone().isBlank();
                if (!hasEmail && !hasPhone) {
                    throw new com.serban.notify.exception.InvalidRequestException(
                        "recipient.email or recipient.phone required when type=external"
                    );
                }
                // Channel-specific gate: SMS requires phone; email requires email.
                if (hasSmsChannel && !hasPhone) {
                    throw new com.serban.notify.exception.InvalidRequestException(
                        "external recipient requires phone (E.164) when channels include 'sms'"
                    );
                }
                if (hasEmailChannel && !hasEmail) {
                    throw new com.serban.notify.exception.InvalidRequestException(
                        "external recipient requires email when channels include 'email'"
                    );
                }
            }
        }
    }
}
