package com.serban.notify.fbl;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArfReportParser} — RFC 5965 ARF MIME parsing
 * (Faz 23.8 M7 T4.3.5 FBL).
 */
class ArfReportParserTest {

    private final ArfReportParser parser = new ArfReportParser();

    /** A well-formed Office 365 style ARF abuse report. */
    private static final String ARF_ABUSE = """
        From: postmaster@office365.com
        To: fbl-mailbox@notify.acik.com
        Subject: Spam Complaint
        Message-ID: <arf-report-001@office365.com>
        MIME-Version: 1.0
        Content-Type: multipart/report; report-type="feedback-report"; boundary="b_arf"

        --b_arf
        Content-Type: text/plain; charset="us-ascii"

        This is an email abuse report for a message received from IP 203.0.113.1.

        --b_arf
        Content-Type: message/feedback-report

        Feedback-Type: abuse
        User-Agent: Microsoft-Office365-FBL/1.0
        Version: 1
        Original-Mail-From: ai@acik.com
        Original-Rcpt-To: complainer@example.com
        Arrival-Date: Thu, 22 May 2026 10:00:00 +0000

        --b_arf
        Content-Type: message/rfc822

        From: ai@acik.com
        To: complainer@example.com
        Subject: Newsletter Mayis
        Message-ID: <orig-msg-456@acik.com>
        X-Notify-Message-ID: notify-corr-789

        Newsletter body content.

        --b_arf--
        """;

    private static MimeMessage mimeMessage(String raw) throws Exception {
        Session session = Session.getInstance(new Properties());
        byte[] bytes = raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        return new MimeMessage(session, new ByteArrayInputStream(bytes));
    }

    @Test
    void parsesAbuseReportFields() throws Exception {
        ArfReport report = parser.parse(mimeMessage(ARF_ABUSE));

        assertThat(report.feedbackType()).isEqualToIgnoringCase("abuse");
        assertThat(report.isAbuseComplaint()).isTrue();
        assertThat(report.reporter()).contains("Office365-FBL");
        assertThat(report.reportMessageId()).isEqualTo("<arf-report-001@office365.com>");
        assertThat(report.originalMessageId()).isEqualTo("<orig-msg-456@acik.com>");
        assertThat(report.xNotifyMessageId()).isEqualTo("notify-corr-789");
        assertThat(report.arrivalDate()).contains("22 May 2026");
        assertThat(report.summaryRedacted()).contains("feedback_type=abuse");
    }

    @Test
    void buildsOrderedProviderMsgIdCandidates() throws Exception {
        ArfReport report = parser.parse(mimeMessage(ARF_ABUSE));

        // X-Notify-Message-ID first, then Message-ID raw + bracket-stripped.
        assertThat(report.providerMsgIdCandidates())
            .containsExactly(
                "notify-corr-789",
                "<orig-msg-456@acik.com>",
                "orig-msg-456@acik.com");
    }

    @Test
    void summaryRedactedCarriesNoRawRecipient() throws Exception {
        ArfReport report = parser.parse(mimeMessage(ARF_ABUSE));
        // KVKK: the raw complained-about address must never leak into the
        // persisted/redacted summary.
        assertThat(report.summaryRedacted()).doesNotContain("complainer@example.com");
    }

    @Test
    void nonAbuseFeedbackTypeParsesButIsNotAbuseComplaint() throws Exception {
        String fraud = ARF_ABUSE.replace("Feedback-Type: abuse", "Feedback-Type: fraud");
        ArfReport report = parser.parse(mimeMessage(fraud));

        assertThat(report.feedbackType()).isEqualToIgnoringCase("fraud");
        assertThat(report.isAbuseComplaint()).isFalse();
    }

    @Test
    void nonMultipartMessageThrows() throws Exception {
        String plain = """
            From: postmaster@office365.com
            To: fbl-mailbox@notify.acik.com
            Subject: Not an ARF
            Message-ID: <plain-001@office365.com>
            Content-Type: text/plain

            Just text, no report structure.
            """;
        MimeMessage message = mimeMessage(plain);
        assertThatThrownBy(() -> parser.parse(message))
            .isInstanceOf(ArfParseException.class)
            .hasMessageContaining("not multipart");
    }

    @Test
    void missingFeedbackReportPartThrows() throws Exception {
        String noReport = """
            From: postmaster@office365.com
            To: fbl-mailbox@notify.acik.com
            Subject: Malformed ARF
            Message-ID: <arf-bad-001@office365.com>
            MIME-Version: 1.0
            Content-Type: multipart/report; report-type="feedback-report"; boundary="b_x"

            --b_x
            Content-Type: text/plain

            Human readable only, no feedback-report part.

            --b_x--
            """;
        MimeMessage message = mimeMessage(noReport);
        assertThatThrownBy(() -> parser.parse(message))
            .isInstanceOf(ArfParseException.class)
            .hasMessageContaining("no message/feedback-report part");
    }

    @Test
    void nullMessageThrows() {
        assertThatThrownBy(() -> parser.parse(null))
            .isInstanceOf(ArfParseException.class);
    }
}
