package com.serban.notify.fbl;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Parsed facts from an ARF (Abuse Reporting Format, RFC 5965)
 * spam-complaint report (Faz 23.8 M7 T4.3.5 FBL — Codex 019e4fc6).
 *
 * <p>This record carries ONLY parser output — it is not tenant-resolved.
 * The platform {@code org_id} + authoritative {@code recipient_hash} are
 * resolved separately by correlating {@link #providerMsgIdCandidates}
 * against {@code notification_delivery.provider_msg_id}
 * (see {@code FblService}).
 *
 * <p>KVKK: {@link #originalRecipient} is the raw complained-about address.
 * It is transient — used only as a fallback diagnostic, NEVER persisted,
 * NEVER logged. The persisted recipient identity always comes from the
 * resolved delivery row's {@code recipient_hash}.
 *
 * @param reporter               reporting entity (e.g. Office 365 postmaster
 *                               User-Agent / Reporting-MTA)
 * @param feedbackType           ARF Feedback-Type (abuse, fraud, virus, other...)
 * @param reportMessageId        the ARF report email's own Message-ID
 * @param originalMessageId      original complained-about message's Message-ID
 *                               (nullable)
 * @param xNotifyMessageId       original message's X-Notify-Message-ID custom
 *                               correlator header (nullable; forward-compat)
 * @param providerMsgIdCandidates ordered correlation candidates for delivery
 *                               resolution (X-Notify-Message-ID variants first,
 *                               then Message-ID variants)
 * @param originalRecipient      raw complained-about address — transient,
 *                               never persisted/logged
 * @param arrivalDate            ARF Arrival-Date field (nullable, raw string)
 * @param receivedAt             timestamp this report was processed
 * @param summaryRedacted        PII-free summary (feedback_type + reporter only)
 */
public record ArfReport(
    String reporter,
    String feedbackType,
    String reportMessageId,
    String originalMessageId,
    String xNotifyMessageId,
    List<String> providerMsgIdCandidates,
    String originalRecipient,
    String arrivalDate,
    OffsetDateTime receivedAt,
    String summaryRedacted
) {
    /** ARF Feedback-Type that maps to a SPAM_COMPLAINT suppression (MVP). */
    public static final String FEEDBACK_TYPE_ABUSE = "abuse";

    /** True when this report's feedback type triggers suppression. */
    public boolean isAbuseComplaint() {
        return feedbackType != null
            && FEEDBACK_TYPE_ABUSE.equalsIgnoreCase(feedbackType.trim());
    }
}
