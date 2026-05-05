package com.serban.notify.audit;

import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * AuditEventPublisher — PII-redacted audit row INSERT (Codex 019df9ae Q4 REVISE absorb).
 *
 * <p>Contract: details whitelist filter via {@link PiiRedactor#filterAuditDetails(Map)}.
 * Payload values NEVER enter audit_event.details — only template metadata,
 * recipient_hash, correlation_id, status fields.
 */
@Component
public class AuditEventPublisher {

    private final AuditEventRepository repository;
    private final PiiRedactor piiRedactor;

    public AuditEventPublisher(AuditEventRepository repository, PiiRedactor piiRedactor) {
        this.repository = repository;
        this.piiRedactor = piiRedactor;
    }

    /**
     * Publish INTENT_CREATED audit event (PR2 submit pipeline).
     *
     * <p>Codex post-impl bulgu #1 absorb: intent-level event channel column
     * NULL kalır (gerçek delivery channel adapter PR3'te); recipient_type ayrı
     * detail field; channels (string list) intent contract snapshot olarak
     * details'e girer.
     *
     * <p>MUST be called inside parent {@code @Transactional}. Audit row INSERT
     * appends; audit_event_no_update + no_delete DB rules prevent mutation.
     *
     * @param recipientType "subscriber" or "external" (NOT delivery channel)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishIntentCreated(NotificationIntent intent, String recipientHash, String recipientType) {
        Map<String, Object> rawDetails = new HashMap<>();
        rawDetails.put("template_id", intent.getTemplateId());
        rawDetails.put("template_version", intent.getTemplateVersion());
        rawDetails.put("template_locale", intent.getLocale());
        rawDetails.put("recipient_hash", recipientHash);
        rawDetails.put("recipient_type", recipientType);
        rawDetails.put("channels", intent.getChannels() == null ? null
            : java.util.Arrays.asList(intent.getChannels()));
        rawDetails.put("severity", intent.getSeverity().name());
        rawDetails.put("data_classification", intent.getDataClassification().name());
        rawDetails.put("topic_key", intent.getTopicKey());
        rawDetails.put("org_id", intent.getOrgId());
        rawDetails.put("correlation_id", intent.getCorrelationId());

        Map<String, Object> filtered = piiRedactor.filterAuditDetails(rawDetails);

        AuditEvent event = new AuditEvent();
        event.setIntentId(intent.getIntentId());
        event.setEventType("INTENT_CREATED");
        event.setOrgId(intent.getOrgId());
        event.setTopicKey(intent.getTopicKey());
        event.setRecipientHash(recipientHash);
        // channel column NULL — intent-level event, gerçek delivery channel
        // PR3 adapter call'da DELIVERY_ATTEMPTED event'le set edilir
        event.setChannel(null);
        event.setTemplateId(intent.getTemplateId());
        event.setTemplateVersion(intent.getTemplateVersion());
        event.setCorrelationId(intent.getCorrelationId());
        event.setDetails(filtered);
        repository.save(event);
    }

    /**
     * Generic audit event publish (used by ileri sub-PR'lar — DELIVERY_ATTEMPTED,
     * DELIVERY_SUCCEEDED, DELIVERY_FAILED, BLOCKED_BY_*, etc).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, NotificationIntent intent, String recipientHash,
                         String channel, Map<String, Object> additionalDetails) {
        Map<String, Object> rawDetails = new HashMap<>();
        rawDetails.put("template_id", intent.getTemplateId());
        rawDetails.put("template_version", intent.getTemplateVersion());
        rawDetails.put("recipient_hash", recipientHash);
        rawDetails.put("channel", channel);
        rawDetails.put("topic_key", intent.getTopicKey());
        rawDetails.put("org_id", intent.getOrgId());
        rawDetails.put("correlation_id", intent.getCorrelationId());
        if (additionalDetails != null) {
            rawDetails.putAll(additionalDetails);
        }

        AuditEvent event = new AuditEvent();
        event.setIntentId(intent.getIntentId());
        event.setEventType(eventType);
        event.setOrgId(intent.getOrgId());
        event.setTopicKey(intent.getTopicKey());
        event.setRecipientHash(recipientHash);
        event.setChannel(channel);
        event.setTemplateId(intent.getTemplateId());
        event.setTemplateVersion(intent.getTemplateVersion());
        event.setCorrelationId(intent.getCorrelationId());
        event.setDetails(piiRedactor.filterAuditDetails(rawDetails));
        repository.save(event);
    }
}
