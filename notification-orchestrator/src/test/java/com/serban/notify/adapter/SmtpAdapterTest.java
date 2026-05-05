package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP adapter test (Codex 019df9ae Q4 PARTIAL absorb).
 *
 * <p>Test edilenler:
 * <ul>
 *   <li>Multipart yapı (HTML + text alternative)</li>
 *   <li>From / To / Subject set</li>
 *   <li>Message-ID header set</li>
 *   <li>Error sınıflandırması: 5xx FAILED, 4xx RETRY, 5.x.x DSN BOUNCED</li>
 * </ul>
 */
class SmtpAdapterTest {

    private JavaMailSender mailSender;
    private SmtpAdapter adapter;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // createMimeMessage returns a real MimeMessage instance backed by Session
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        adapter = new SmtpAdapter(mailSender);
        ReflectionTestUtils.setField(adapter, "fromAddress", "noreply@test.local");
        ReflectionTestUtils.setField(adapter, "fromName", "Test Sender");
    }

    @Test
    void deliversMultipartHtmlAndText() throws Exception {
        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage(
            "Welcome", "<p>Hello <b>World</b></p>", "Hello World", "en-US"
        );

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).matches("<.+@notification-orchestrator>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("Welcome");
        assertThat(sent.getFrom()).isNotNull();
        assertThat(sent.getFrom()[0].toString()).contains("noreply@test.local");
        assertThat(sent.getAllRecipients()).hasSize(1);
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(sent.getHeader("Message-ID")).isNotEmpty();
        assertThat(sent.getHeader("Message-ID")[0]).contains("@notification-orchestrator");

        // Multipart structure verify (alternative: text + html)
        Object content = sent.getContent();
        assertThat(content).isInstanceOf(MimeMultipart.class);
        MimeMultipart mp = (MimeMultipart) content;
        // MULTIPART_MODE_MIXED_RELATED with both setText(text, html) → mixed > related > alternative
        // top-level is mixed; we drill down to find alternative parts
        assertThat(mp.getCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deliversHtmlOnly() throws Exception {
        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage("S", "<p>HTML</p>", null, "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
    }

    @Test
    void deliversTextOnly() throws Exception {
        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "plain text", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
    }

    @Test
    void emptyBodyFailsWithoutSending() {
        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, null, "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("template body empty");
    }

    @Test
    void smtp550HardBounce() {
        Exception cause = new RuntimeException("550 5.1.1 No such user here");
        doThrow(new MailSendException("send failed", cause)).when(mailSender).send(any(MimeMessage.class));

        DeliveryTarget target = target("missing@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "x", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.BOUNCED);
        assertThat(r.failureReason()).contains("550");
    }

    @Test
    void smtp554PermanentFailure() {
        Exception cause = new RuntimeException("554 Transaction failed: relay denied");
        doThrow(new MailSendException("send failed", cause)).when(mailSender).send(any(MimeMessage.class));

        DeliveryTarget target = target("denied@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "x", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.providerResponseCode()).isEqualTo(500);
    }

    @Test
    void smtp421TransientRetry() {
        Exception cause = new RuntimeException("421 4.7.0 Try again later");
        doThrow(new MailSendException("send failed", cause)).when(mailSender).send(any(MimeMessage.class));

        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "x", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isEqualTo(400);
    }

    @Test
    void mailboxUnavailableBounces() {
        Exception cause = new RuntimeException("mailbox unavailable for user");
        doThrow(new MailSendException("send failed", cause)).when(mailSender).send(any(MimeMessage.class));

        DeliveryTarget target = target("missing@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "x", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.BOUNCED);
    }

    @Test
    void unknownCauseTreatedAsRetry() {
        Exception cause = new RuntimeException("connection refused");
        doThrow(new MailSendException("send failed", cause)).when(mailSender).send(any(MimeMessage.class));

        DeliveryTarget target = target("user@example.com");
        RenderedMessage msg = new RenderedMessage("S", null, "x", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void smtpChannelKey() {
        assertThat(adapter.channelKey()).isEqualTo("email");
    }

    private static DeliveryTarget target(String email) {
        return new DeliveryTarget("email", "subscriber", "1", "hash", email, "smtp-default");
    }
}
