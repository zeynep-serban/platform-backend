package com.serban.notify.fbl;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.EmailBounceEventRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.projection.FblDeliveryResolution;
import com.serban.notify.suppression.EmailSuppressionService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Spam-complaint Feedback Loop (FBL) ingestion service
 * (Faz 23.8 M7 T4.3.5 — Codex 019e4edd plan-time + 019e4fc6 impl design).
 *
 * <p>Ingests an Office 365 Postmaster ARF (Abuse Reporting Format,
 * RFC 5965) spam-complaint report and, when it correlates to a known
 * delivery, adds the recipient to {@code email_suppression} with reason
 * {@code SPAM_COMPLAINT} so the address is never emailed again
 * (provider IP-reputation protection).
 *
 * <p>Pipeline ({@link #ingest}), all within one {@code REQUIRED}
 * transaction so the bounce-event ledger insert, the suppression upsert
 * and the audit row commit together:
 * <ol>
 *   <li>Parse ARF MIME → {@link ArfReport} (fail-closed on parse error)</li>
 *   <li>Non-abuse feedback type → counted ignored, no suppression</li>
 *   <li>Resolve tenant + recipient by correlating provider_msg_id
 *       candidates against {@code notification_delivery}</li>
 *   <li>Unresolved → counted, skipped (cannot suppress without org_id)</li>
 *   <li>Idempotent {@code email_bounce_event} insert by event fingerprint;
 *       duplicate → metric-only no-op</li>
 *   <li>Fresh → {@code EmailSuppressionService.upsert} SPAM_COMPLAINT +
 *       audit + metrics</li>
 * </ol>
 *
 * <p>KVKK: the resolved {@code recipient_hash} is taken straight from the
 * delivery row — it is never recomputed from the ARF address (subscriber
 * dispatch hashes subscriberId, external dispatch hashes the email; see
 * {@link FblDeliveryResolution}).
 */
@Service
public class FblService {

    private static final Logger log = LoggerFactory.getLogger(FblService.class);

    private static final String SOURCE_ARF_MAILBOX = "ARF_MAILBOX";
    private static final String CLASSIFICATION_SPAM = "SPAM_COMPLAINT";
    private static final String ACTOR = "fbl-mailbox";
    private static final String AUDIT_EVENT_TYPE = "EMAIL_SPAM_COMPLAINT";
    private static final int PROVIDER_MAX = 64;

    private final ArfReportParser parser;
    private final NotificationDeliveryRepository deliveryRepository;
    private final EmailBounceEventRepository bounceEventRepository;
    private final EmailSuppressionService suppressionService;
    private final AuditEventPublisher audit;
    private final FblMetrics metrics;

    public FblService(
        ArfReportParser parser,
        NotificationDeliveryRepository deliveryRepository,
        EmailBounceEventRepository bounceEventRepository,
        EmailSuppressionService suppressionService,
        AuditEventPublisher audit,
        FblMetrics metrics
    ) {
        this.parser = parser;
        this.deliveryRepository = deliveryRepository;
        this.bounceEventRepository = bounceEventRepository;
        this.suppressionService = suppressionService;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Terminal outcome of an ARF ingestion attempt. */
    public enum FblOutcome {
        SUPPRESSED,
        DUPLICATE,
        IGNORED_UNSUPPORTED,
        UNRESOLVED,
        PARSE_ERROR
    }

    /**
     * Ingest one ARF spam-complaint report. Single transaction; never
     * throws on a bad report — every failure mode is a counted outcome.
     *
     * @param rawArf the raw ARF MIME message (mailbox worker owns lifecycle)
     * @return terminal {@link FblOutcome}
     */
    @Transactional
    public FblOutcome ingest(MimeMessage rawArf) {
        ArfReport report;
        try {
            report = parser.parse(rawArf);
        } catch (ArfParseException e) {
            log.warn("FBL parse_error: {}", e.getMessage());
            metrics.received(FblMetrics.OUTCOME_PARSE_ERROR);
            return FblOutcome.PARSE_ERROR;
        }

        if (!report.isAbuseComplaint()) {
            log.info("FBL ignored_unsupported: feedback_type={}", report.feedbackType());
            metrics.received(FblMetrics.OUTCOME_IGNORED_UNSUPPORTED);
            return FblOutcome.IGNORED_UNSUPPORTED;
        }

        // Resolve tenant + recipient by provider_msg_id correlation.
        FblDeliveryResolution resolution = null;
        String matchedCandidate = null;
        for (String candidate : report.providerMsgIdCandidates()) {
            Optional<FblDeliveryResolution> resolved =
                deliveryRepository.resolveFblByProviderMsgId(candidate);
            if (resolved.isPresent()) {
                resolution = resolved.get();
                matchedCandidate = candidate;
                break;
            }
        }
        if (resolution == null) {
            log.info("FBL unresolved: no delivery for {} candidate(s)",
                report.providerMsgIdCandidates().size());
            metrics.received(FblMetrics.OUTCOME_UNRESOLVED);
            return FblOutcome.UNRESOLVED;
        }

        // Defense-in-depth (Codex 019e4fc6 iter-2 HIGH #1): resolveFbl...
        // already filters channel='email'; this guard ensures a future query
        // change can never let a non-email delivery drive an email-channel
        // suppression (subscriber SMS + email share recipient_hash).
        if (!"email".equalsIgnoreCase(resolution.getChannel())) {
            log.warn("FBL unresolved: correlated delivery channel={} not email",
                resolution.getChannel());
            metrics.received(FblMetrics.OUTCOME_UNRESOLVED);
            return FblOutcome.UNRESOLVED;
        }

        String orgId = resolution.getOrgId();
        String recipientHash = resolution.getRecipientHash();
        String provider = normalizeProvider(report.reporter());
        // Codex 019e4fc6 iter-2 MEDIUM #3: fingerprint correlator falls back
        // originalMessageId -> xNotifyMessageId -> matchedCandidate. An ARF
        // missing an original Message-ID (but with a resolved correlator)
        // still gets a stable, complaint-specific fingerprint instead of
        // collapsing every complaint from one reporter onto a single hash.
        String fingerprintCorrelator = firstNonBlank(
            report.originalMessageId(), report.xNotifyMessageId(), matchedCandidate);
        String fingerprint = FblFingerprint.compute(
            report.reporter(), report.reportMessageId(), fingerprintCorrelator);

        // Idempotent ledger insert — rowcount 0 means already processed.
        int inserted = bounceEventRepository.insertIfAbsent(
            fingerprint,
            orgId,
            recipientHash,
            provider,
            matchedCandidate,
            SOURCE_ARF_MAILBOX,
            CLASSIFICATION_SPAM,
            report.receivedAt(),
            report.summaryRedacted()
        );
        if (inserted == 0) {
            log.info("FBL duplicate (idempotent no-op): org={} fingerprint={}",
                orgId, fingerprint);
            metrics.received(FblMetrics.OUTCOME_DUPLICATE);
            return FblOutcome.DUPLICATE;
        }

        // Fresh complaint → permanent SPAM_COMPLAINT suppression.
        suppressionService.upsert(new EmailSuppressionService.UpsertInput(
            orgId,
            recipientHash,
            mapRecipientType(resolution.getRecipientType()),
            EmailSuppression.Reason.SPAM_COMPLAINT,
            EmailSuppression.Source.ARF_MAILBOX,
            provider,
            matchedCandidate,
            report.summaryRedacted(),
            fingerprint,
            ACTOR
        ));

        Map<String, Object> details = new HashMap<>();
        details.put("reason", CLASSIFICATION_SPAM);
        details.put("feedback_type", report.feedbackType());
        details.put("event_fingerprint", fingerprint);
        details.put("provider", provider);
        audit.publishStandalone(AUDIT_EVENT_TYPE, orgId, recipientHash, details);

        log.info("FBL suppressed: org={} intent={} fingerprint={}",
            orgId, resolution.getIntentId(), fingerprint);
        metrics.received(FblMetrics.OUTCOME_SUPPRESSED);
        metrics.suppressed(orgId);
        return FblOutcome.SUPPRESSED;
    }

    /**
     * Map the delivery recipient type to the suppression recipient type.
     * {@code CHANNEL} (Slack/Teams/webhook) is not an email recipient and
     * should not appear on an email FBL path; defensively treated as
     * EXTERNAL so a malformed correlation never NPEs.
     */
    private static EmailSuppression.RecipientType mapRecipientType(
            NotificationDelivery.RecipientType deliveryType) {
        if (deliveryType == NotificationDelivery.RecipientType.SUBSCRIBER) {
            return EmailSuppression.RecipientType.SUBSCRIBER;
        }
        return EmailSuppression.RecipientType.EXTERNAL;
    }

    /** First non-blank value, or null when all are blank. */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Provider audit label from the ARF reporter — trimmed, truncated to
     * the {@code provider} column width. Blank reporter → "office365-fbl"
     * (ARF mailbox-pull is the Office 365 Postmaster path in PR-1).
     */
    private static String normalizeProvider(String reporter) {
        if (reporter == null || reporter.isBlank()) {
            return "office365-fbl";
        }
        String trimmed = reporter.trim();
        return trimmed.length() > PROVIDER_MAX
            ? trimmed.substring(0, PROVIDER_MAX)
            : trimmed;
    }
}
