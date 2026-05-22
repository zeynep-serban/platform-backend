package com.serban.notify.fbl;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses an ARF (Abuse Reporting Format, RFC 5965) spam-complaint email
 * into an {@link ArfReport} (Faz 23.8 M7 T4.3.5 FBL — Codex 019e4fc6).
 *
 * <p>An ARF report is a {@code multipart/report; report-type="feedback-report"}
 * MIME message with (typically) three parts:
 * <ol>
 *   <li>{@code text/plain} — human-readable description (ignored here)</li>
 *   <li>{@code message/feedback-report} — machine-readable ARF fields
 *       (Feedback-Type, User-Agent, Original-Rcpt-To, Arrival-Date, ...)</li>
 *   <li>{@code message/rfc822} or {@code text/rfc822-headers} — the
 *       original complained-about message (or just its headers)</li>
 * </ol>
 *
 * <p><b>Fail-closed</b>: any structural problem (not multipart, no
 * feedback-report part, unreadable MIME) raises {@link ArfParseException};
 * {@code FblService} then counts {@code parse_error} and skips — an
 * unparseable report never produces a suppression.
 */
@Component
public class ArfReportParser {

    private static final Logger log = LoggerFactory.getLogger(ArfReportParser.class);

    private static final int SUMMARY_MAX = 256;

    /** Injectable for deterministic tests; never set in production. */
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Parse an ARF MIME message. The {@code message} is fully read; the
     * caller owns its lifecycle (mailbox worker).
     *
     * @throws ArfParseException structural / MIME failure (fail-closed)
     */
    public ArfReport parse(MimeMessage message) throws ArfParseException {
        if (message == null) {
            throw new ArfParseException("ARF message is null");
        }
        try {
            String reportMessageId = firstHeader(message, "Message-ID");

            Object content = message.getContent();
            if (!(content instanceof Multipart multipart)) {
                throw new ArfParseException(
                    "ARF message is not multipart (Content-Type: "
                        + safeContentType(message) + ")");
            }

            InternetHeaders feedbackFields = null;
            InternetHeaders originalHeaders = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("message/feedback-report")) {
                    feedbackFields = readHeaders(part);
                } else if (part.isMimeType("message/rfc822")
                           || part.isMimeType("text/rfc822-headers")) {
                    // Both forms expose the original message's headers as a
                    // leading RFC 822 header block; InternetHeaders stops at
                    // the first blank line, so it works for the full message
                    // form and the headers-only form alike.
                    originalHeaders = readHeaders(part);
                }
            }

            if (feedbackFields == null) {
                throw new ArfParseException(
                    "ARF message has no message/feedback-report part");
            }

            String feedbackType = firstValue(feedbackFields, "Feedback-Type");
            String reporter = firstNonBlank(
                firstValue(feedbackFields, "User-Agent"),
                firstValue(feedbackFields, "Reporting-MTA"),
                firstHeader(message, "From"));
            String originalRecipient = firstValue(feedbackFields, "Original-Rcpt-To");
            String arrivalDate = firstValue(feedbackFields, "Arrival-Date");

            String originalMessageId = originalHeaders != null
                ? firstValue(originalHeaders, "Message-ID") : null;
            String xNotifyMessageId = originalHeaders != null
                ? firstValue(originalHeaders, "X-Notify-Message-ID") : null;

            List<String> candidates = buildCandidates(xNotifyMessageId, originalMessageId);
            String summaryRedacted = buildSummary(feedbackType, reporter);

            return new ArfReport(
                reporter,
                feedbackType,
                reportMessageId,
                originalMessageId,
                xNotifyMessageId,
                candidates,
                originalRecipient,
                arrivalDate,
                OffsetDateTime.now(clock),
                summaryRedacted
            );
        } catch (MessagingException | IOException e) {
            throw new ArfParseException("ARF MIME parse failed: " + e.getMessage(), e);
        }
    }

    /** Read a body part's leading RFC 822 header block. */
    private static InternetHeaders readHeaders(BodyPart part)
            throws MessagingException, IOException {
        return new InternetHeaders(part.getInputStream());
    }

    private static String firstHeader(MimeMessage message, String name)
            throws MessagingException {
        String[] values = message.getHeader(name);
        return (values != null && values.length > 0) ? trimToNull(values[0]) : null;
    }

    private static String firstValue(InternetHeaders headers, String name) {
        String[] values = headers.getHeader(name);
        return (values != null && values.length > 0) ? trimToNull(values[0]) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            String t = trimToNull(v);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * Build the ordered provider_msg_id correlation candidate list:
     * X-Notify-Message-ID variants first (raw + bracket-stripped), then
     * Message-ID variants. De-duplicated, insertion-ordered.
     */
    static List<String> buildCandidates(String xNotifyMessageId, String originalMessageId) {
        Set<String> ordered = new LinkedHashSet<>();
        addCandidate(ordered, xNotifyMessageId);
        addCandidate(ordered, originalMessageId);
        return new ArrayList<>(ordered);
    }

    private static void addCandidate(Set<String> set, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return;
        }
        set.add(trimmed);
        String stripped = FblFingerprint.normalizeMessageId(trimmed);
        if (!stripped.isEmpty()) {
            // normalizeMessageId lowercases; also add the un-lowercased
            // bracket-stripped form because provider_msg_id is case-sensitive.
            String bareStripped = trimmed;
            if (bareStripped.length() >= 2
                && bareStripped.startsWith("<") && bareStripped.endsWith(">")) {
                bareStripped = bareStripped.substring(1, bareStripped.length() - 1).trim();
            }
            if (!bareStripped.isEmpty()) {
                set.add(bareStripped);
            }
        }
    }

    private static String buildSummary(String feedbackType, String reporter) {
        String summary = "feedback_type=" + (feedbackType != null ? feedbackType : "unknown")
            + "; reporter=" + (reporter != null ? reporter : "unknown");
        if (summary.length() > SUMMARY_MAX) {
            summary = summary.substring(0, SUMMARY_MAX);
        }
        return summary;
    }

    private static String safeContentType(MimeMessage message) {
        try {
            return message.getContentType();
        } catch (MessagingException e) {
            return "unknown";
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
