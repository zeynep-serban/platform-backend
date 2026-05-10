package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * SMTP channel adapter (Faz 23.1 PR3 — Codex 019df9ae Q4 PARTIAL absorb).
 *
 * <p>Codex Q4 mandate'leri:
 * <ul>
 *   <li>Mailpit "DKIM signer" varsayılMAZ — sadece SMTP sink/test mailbox</li>
 *   <li>Production DKIM Faz 23.2 (corporate relay DKIM yapıyorsa app-side
 *       gerek yok; yapmıyorsa app-side DKIM signer + Vault key)</li>
 *   <li>TLS-only production default; Mailpit test profile plaintext istisnası</li>
 *   <li>Multipart: HTML + plain text alternative parts</li>
 * </ul>
 *
 * <p>Status semantics:
 * <ul>
 *   <li>SMTP 2xx (250 OK) → DELIVERED</li>
 *   <li>SMTP 4xx (transient) → RETRY (PR4 worker)</li>
 *   <li>SMTP 5xx (permanent) → FAILED</li>
 *   <li>Hard bounce (5.x.x DSN) → BOUNCED</li>
 * </ul>
 */
@Component
public class SmtpAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmtpAdapter.class);

    private final JavaMailSender mailSender;
    /**
     * Optional DKIM signer (Codex 019e1307 P0 absorb 2026-05-10).
     * Bean only present when notify.dkim.enabled=true (ConditionalOnProperty
     * gate); Optional<> wrapper enables disabled-channel fallback.
     */
    private final java.util.Optional<com.serban.notify.provider.DkimSigner> dkimSigner;

    @Value("${notify.adapters.smtp.from-address:noreply@localhost}")
    private String fromAddress;

    @Value("${notify.adapters.smtp.from-name:Notification Orchestrator}")
    private String fromName;

    public SmtpAdapter(
        JavaMailSender mailSender,
        java.util.Optional<com.serban.notify.provider.DkimSigner> dkimSigner
    ) {
        this.mailSender = mailSender;
        this.dkimSigner = dkimSigner;
        log.info("SmtpAdapter activated: dkimEnabled={}", dkimSigner.isPresent());
    }

    @Override
    public String channelKey() {
        return "email";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name()
            );
            helper.setFrom(fromAddress, fromName);
            helper.setTo(target.targetRef());
            if (message.subject() != null) {
                helper.setSubject(message.subject());
            }
            // Multipart: HTML + plain text alternative
            if (message.bodyHtml() != null && message.bodyText() != null) {
                helper.setText(message.bodyText(), message.bodyHtml());
            } else if (message.bodyHtml() != null) {
                helper.setText(message.bodyHtml(), true);
            } else if (message.bodyText() != null) {
                helper.setText(message.bodyText(), false);
            } else {
                return DeliveryAttemptResult.failed("template body empty (no html or text)", null);
            }

            String messageId = "<" + UUID.randomUUID() + "@notification-orchestrator>";

            // Codex 019e1307 P0 + P1 absorb 2026-05-10:
            // saveChanges() ordering MUST precede DKIM sign so Date header is
            // populated by Jakarta Mail. setHeader("Message-ID", ...) AFTER
            // saveChanges() because saveChanges() updates Message-ID to default
            // <id>@<host> form. Order: saveChanges → setHeader Message-ID →
            // DKIM sign → send. Manuel Message-ID koruma + DKIM signature
            // canonical Date dahil.
            mime.saveChanges();
            mime.setHeader("Message-ID", messageId);
            if (dkimSigner.isPresent()) {
                dkimSigner.get().sign(mime);
                log.debug("smtp DKIM-Signature applied: messageId={}", messageId);
            }

            mailSender.send(mime);
            log.info("smtp delivered: to=<redacted-hash:{}> subject=<{}> message_id={}",
                target.recipientHash(), abbrev(message.subject()), messageId);
            return DeliveryAttemptResult.delivered(messageId);

        } catch (MailSendException e) {
            return classifyMailSendException(e);
        } catch (MailException | MessagingException | java.io.UnsupportedEncodingException e) {
            log.warn("smtp send error (treating as RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("transient: " + e.getClass().getSimpleName(), null);
        }
    }

    private DeliveryAttemptResult classifyMailSendException(MailSendException e) {
        // Spring MailSendException contains failed messages; inspect underlying cause
        Throwable cause = e.getMostSpecificCause();
        String msg = cause != null ? cause.getMessage() : e.getMessage();
        // Classify: 5xx permanent vs 4xx transient (best-effort string match)
        if (msg != null) {
            // Hard bounce / mailbox unavailable / no such user
            if (msg.contains("550") || msg.contains("551") || msg.contains("553")
                || msg.contains("No such user") || msg.contains("mailbox unavailable")) {
                return DeliveryAttemptResult.bounced(msg);
            }
            // Permanent 5xx
            if (msg.matches(".*\\b5\\d\\d\\b.*")) {
                return DeliveryAttemptResult.failed(msg, 500);
            }
            // Transient 4xx
            if (msg.matches(".*\\b4\\d\\d\\b.*")) {
                return DeliveryAttemptResult.retry(msg, 400);
            }
        }
        log.warn("smtp send classified RETRY (unknown cause): {}", msg);
        return DeliveryAttemptResult.retry(msg != null ? msg : "MailSendException", null);
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
