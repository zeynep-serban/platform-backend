package com.serban.notify.audit;

import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationDelivery;
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

    /**
     * Delivery-scoped audit event publish (Faz 23.4 PR-F — Codex iter-1
     * absorb: AuditEvent.delivery_id field set so DLR audit row links to
     * the delivery being terminalized).
     *
     * <p>Same as {@link #publish} but additionally sets
     * {@code AuditEvent.delivery_id}. Used by DLR ingest path to
     * correlate audit row with specific delivery_id (queryable for
     * compliance: "show me all DLR events for delivery 12345").
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishWithDelivery(String eventType, NotificationIntent intent,
                                     NotificationDelivery delivery, String channel,
                                     Map<String, Object> additionalDetails) {
        Map<String, Object> rawDetails = new HashMap<>();
        rawDetails.put("template_id", intent.getTemplateId());
        rawDetails.put("template_version", intent.getTemplateVersion());
        rawDetails.put("recipient_hash", delivery.getRecipientHash());
        rawDetails.put("channel", channel);
        rawDetails.put("topic_key", intent.getTopicKey());
        rawDetails.put("org_id", intent.getOrgId());
        rawDetails.put("correlation_id", intent.getCorrelationId());
        rawDetails.put("delivery_id_long", delivery.getId());
        if (additionalDetails != null) {
            rawDetails.putAll(additionalDetails);
        }

        AuditEvent event = new AuditEvent();
        event.setIntentId(intent.getIntentId());
        event.setEventType(eventType);
        event.setOrgId(intent.getOrgId());
        event.setTopicKey(intent.getTopicKey());
        event.setRecipientHash(delivery.getRecipientHash());
        event.setChannel(channel);
        event.setTemplateId(intent.getTemplateId());
        event.setTemplateVersion(intent.getTemplateVersion());
        event.setCorrelationId(intent.getCorrelationId());
        event.setDeliveryId(delivery.getId());  // Faz 23.4 PR-F new linkage
        event.setDetails(piiRedactor.filterAuditDetails(rawDetails));
        repository.save(event);
    }

    /**
     * Standalone (org-scoped) audit event publish (Faz 23.3 PR-E.1 — Codex
     * iter-2 P1 absorb).
     *
     * <p>For audit events that have NO source intent — e.g.,
     * {@code SUBSCRIBER_INBOX_ERASURE} (KVKK Art 17 right-to-erasure
     * inbox-only path). The audit_event_v2 schema requires non-null
     * {@code intent_id} and {@code topic_key}: this method synthesizes
     * compliance-context placeholder values that are valid SQL but identify
     * the event as standalone (no intent linkage).
     *
     * <p>Synthesized fields:
     * <ul>
     *   <li>{@code intent_id}: {@code "standalone-{uuid}"} (47 chars; fits
     *       VARCHAR(64) limit). Event type identity already lives in
     *       {@code event_type} column — no need to embed in intent_id.</li>
     *   <li>{@code topic_key}: {@code "audit.standalone.{eventType-lowercased}"}
     *       — operator filter convention</li>
     *   <li>{@code template_id} / {@code template_version} / {@code channel} /
     *       {@code correlation_id}: NULL (no intent context)</li>
     * </ul>
     *
     * @param eventType audit event type (e.g. SUBSCRIBER_INBOX_ERASURE)
     * @param orgId tenant boundary (NOT NULL)
     * @param recipientHash optional recipient HMAC (NOT NULL — caller passes
     *                      hashed subscriber for compliance audit linkage)
     * @param additionalDetails arbitrary details (filtered via PiiRedactor whitelist)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStandalone(String eventType, String orgId, String recipientHash,
                                   Map<String, Object> additionalDetails) {
        publishStandaloneInternal(eventType, orgId, recipientHash, additionalDetails);
    }

    /**
     * Publish standalone audit event in a NEW independent transaction —
     * Faz 23.2.F T1.6 abuse guards (Codex `019e0c28` iter-2 P1 absorb 2026-05-09).
     *
     * <p>Use case: caller throws unchecked exception immediately after publish
     * (e.g., {@code AbuseGuardBlockedException} returns HTTP 429). Default
     * Spring rollback would lose the audit row; {@code Propagation.REQUIRES_NEW}
     * commits this audit INSERT in its own transaction so the abuse evidence
     * survives the outer rollback.
     *
     * <p>Safety: yalnız BLOCKED path'te kullan; happy path için
     * {@link #publishStandalone} (transaction-bound) tercih et.
     *
     * @see #publishStandalone for inherited transaction (MANDATORY) variant
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishStandaloneRequiresNew(String eventType, String orgId, String recipientHash,
                                              Map<String, Object> additionalDetails) {
        publishStandaloneInternal(eventType, orgId, recipientHash, additionalDetails);
    }

    private void publishStandaloneInternal(String eventType, String orgId, String recipientHash,
                                            Map<String, Object> additionalDetails) {
        Map<String, Object> rawDetails = new HashMap<>();
        rawDetails.put("recipient_hash", recipientHash);
        rawDetails.put("org_id", orgId);
        if (additionalDetails != null) {
            rawDetails.putAll(additionalDetails);
        }

        // Codex iter-3 P1 absorb: VARCHAR(64) limit. "standalone-" + UUID = 47 chars.
        // Event type identity lives in event_type column already; no need to embed.
        String synthesizedIntentId = "standalone-" + java.util.UUID.randomUUID();
        // Codex iter-4 nit: Locale.ROOT — Türkçe locale "I → ı" surprise engeli.
        String synthesizedTopicKey = "audit.standalone."
            + eventType.toLowerCase(java.util.Locale.ROOT);

        AuditEvent event = new AuditEvent();
        event.setIntentId(synthesizedIntentId);
        event.setEventType(eventType);
        event.setOrgId(orgId);
        event.setTopicKey(synthesizedTopicKey);
        event.setRecipientHash(recipientHash);
        event.setChannel(null);
        event.setTemplateId(null);
        event.setTemplateVersion(null);
        event.setCorrelationId(null);
        event.setDetails(piiRedactor.filterAuditDetails(rawDetails));
        repository.save(event);
    }
}
