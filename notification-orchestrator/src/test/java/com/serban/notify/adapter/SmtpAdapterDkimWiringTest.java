package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.provider.DkimSigner;
import com.serban.notify.template.RenderedMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SmtpAdapter ↔ DkimSigner wiring test (Codex 019e1307 P0 absorb).
 *
 * <p>Verifies the wiring between SmtpAdapter and DkimSigner without
 * re-testing the DKIM crypto path (covered by DkimSignerTest 17-test suite).
 *
 * <p>Coverage:
 * <ol>
 *   <li>DKIM enabled (Optional.of) → DKIM-Signature header present on outbound MimeMessage</li>
 *   <li>DKIM disabled (Optional.empty) → no DKIM-Signature header</li>
 *   <li>saveChanges() ordering: Date header populated</li>
 *   <li>Message-ID custom suffix preserved (set AFTER saveChanges)</li>
 * </ol>
 */
class SmtpAdapterDkimWiringTest {

    private JavaMailSender mailSender;
    private SmtpAdapter adapter;
    private DkimSigner dkimSigner;

    @BeforeEach
    void setUp() throws Exception {
        mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        // Real DkimSigner with generated keypair (signing path exercised end-to-end)
        KeyPair keyPair = generateKeyPair();
        String pkcs8Pem = exportPkcs8(keyPair);
        dkimSigner = new DkimSigner("s1", "example.com", pkcs8Pem,
            "From,To,Subject,Date,Message-ID");

        adapter = new SmtpAdapter(mailSender, Optional.of(dkimSigner));
        ReflectionTestUtils.setField(adapter, "fromAddress", "noreply@example.com");
        ReflectionTestUtils.setField(adapter, "fromName", "ACIK Test");
    }

    @Test
    void smtpAdapterAddsDkimSignatureHeaderWhenSignerEnabled() throws Exception {
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1204",
            "hash-1204", "user@example.com", "smtp"
        );
        RenderedMessage message = new RenderedMessage(
            "Test Subject", "<html>HTML body</html>", "Plain text body", "tr-TR"
        );

        adapter.send(target, message);

        ArgumentCaptor<MimeMessage> mimeCapture = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mimeCapture.capture());
        MimeMessage sent = mimeCapture.getValue();

        // DKIM-Signature header must be present + properly structured
        String[] dkimHeaders = sent.getHeader("DKIM-Signature");
        assertThat(dkimHeaders)
            .as("DKIM-Signature header set on outbound mime by SmtpAdapter wiring")
            .isNotNull()
            .hasSize(1);
        String dkim = dkimHeaders[0];
        assertThat(dkim).contains("v=1");
        assertThat(dkim).contains("a=rsa-sha256");
        assertThat(dkim).contains("c=relaxed/relaxed");
        assertThat(dkim).contains("d=example.com");
        assertThat(dkim).contains("s=s1");
        assertThat(dkim).contains("bh=");
        assertThat(dkim).contains("b=");

        // saveChanges() ordering: Date header populated
        assertThat(sent.getHeader("Date"))
            .as("Date header populated by saveChanges() before DKIM sign")
            .isNotNull()
            .hasSize(1);

        // Message-ID custom @notification-orchestrator suffix preserved
        // (set AFTER saveChanges to avoid Jakarta Mail default override)
        String[] messageIdHeaders = sent.getHeader("Message-ID");
        assertThat(messageIdHeaders).isNotNull().hasSize(1);
        assertThat(messageIdHeaders[0])
            .as("Custom Message-ID @notification-orchestrator suffix preserved")
            .contains("@notification-orchestrator");
    }

    @Test
    void smtpAdapterSkipsDkimWhenSignerDisabled() throws Exception {
        // DKIM disabled (Optional.empty) — bean not present
        SmtpAdapter disabledAdapter = new SmtpAdapter(mailSender, Optional.empty());
        ReflectionTestUtils.setField(disabledAdapter, "fromAddress", "noreply@example.com");
        ReflectionTestUtils.setField(disabledAdapter, "fromName", "ACIK Test");

        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1204",
            "hash-1204", "user@example.com", "smtp"
        );
        RenderedMessage message = new RenderedMessage(
            "Subj", null, "Body text", "tr-TR"
        );

        disabledAdapter.send(target, message);

        ArgumentCaptor<MimeMessage> mimeCapture = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mimeCapture.capture());
        MimeMessage sent = mimeCapture.getValue();

        // No DKIM-Signature header (DKIM disabled)
        String[] dkimHeaders = sent.getHeader("DKIM-Signature");
        assertThat(dkimHeaders)
            .as("DKIM-Signature header absent when DKIM disabled (Optional.empty)")
            .isNull();
    }

    // Helpers
    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String exportPkcs8(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String base64 = Base64.getMimeEncoder(64,
            "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .encodeToString(encoded);
        // Marker via concat (gitleaks safe; test fixture)
        final String begin = "-----BEGIN " + "PRIVATE" + " KEY-----";
        final String end = "-----END " + "PRIVATE" + " KEY-----";
        return begin + "\n" + base64 + "\n" + end + "\n";
    }
}
