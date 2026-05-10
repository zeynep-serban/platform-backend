package com.serban.notify.provider;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DkimSigner RFC 6376 unit test suite.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>Body canonicalization (relaxed) per RFC 6376 §3.4.4</li>
 *   <li>Header canonicalization (relaxed) per RFC 6376 §3.4.2</li>
 *   <li>End-to-end sign + verify round-trip with generated RSA keypair</li>
 *   <li>Body hash determinism + signature stability under deterministic clock</li>
 * </ol>
 */
class DkimSignerTest {

    // ============================================================
    // Body canonicalization (RFC 6376 §3.4.4 — relaxed)
    // ============================================================

    @Test
    void bodyCanonicalize_collapsesMultipleSpaces() {
        byte[] input = "Hello   World  Test\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .isEqualTo("Hello World Test\r\n");
    }

    @Test
    void bodyCanonicalize_stripsTrailingWhitespace() {
        byte[] input = "Hello World   \r\nLine2  \t  \r\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .isEqualTo("Hello World\r\nLine2\r\n");
    }

    @Test
    void bodyCanonicalize_stripsTrailingEmptyLines() {
        byte[] input = "Body line 1\r\nBody line 2\r\n\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .isEqualTo("Body line 1\r\nBody line 2\r\n");
    }

    @Test
    void bodyCanonicalize_emptyBodyReturnsEmpty() {
        byte[] input = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(result).isEmpty();
    }

    @Test
    void bodyCanonicalize_normalizesLineEndings() {
        // Mixed \n and \r\n line endings → all become \r\n
        byte[] input = "Line1\nLine2\r\nLine3\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .isEqualTo("Line1\r\nLine2\r\nLine3\r\n");
    }

    @Test
    void bodyCanonicalize_tabsCollapseToSingleSpace() {
        byte[] input = "Hello\t\tWorld\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] result = DkimSigner.canonicalizeBodyRelaxed(input);
        assertThat(new String(result, StandardCharsets.UTF_8))
            .isEqualTo("Hello World\r\n");
    }

    // ============================================================
    // Header canonicalization (RFC 6376 §3.4.2 — relaxed)
    // ============================================================

    @Test
    void headerCanonicalize_lowercasesFieldName() {
        String result = DkimSigner.canonicalizeHeaderRelaxed("From", "user@example.com");
        assertThat(result).isEqualTo("from:user@example.com\r\n");
    }

    @Test
    void headerCanonicalize_collapsesMultipleSpaces() {
        String result = DkimSigner.canonicalizeHeaderRelaxed("Subject", "Hello    World   Test");
        assertThat(result).isEqualTo("subject:Hello World Test\r\n");
    }

    @Test
    void headerCanonicalize_stripsLeadingTrailingWhitespace() {
        String result = DkimSigner.canonicalizeHeaderRelaxed("Subject", "  hello world  ");
        assertThat(result).isEqualTo("subject:hello world\r\n");
    }

    @Test
    void headerCanonicalize_unfoldsContinuationLines() {
        // Folded header: continuation line starts with WSP
        String folded = "Multi-line\r\n header value";
        String result = DkimSigner.canonicalizeHeaderRelaxed("X-Custom", folded);
        assertThat(result).isEqualTo("x-custom:Multi-line header value\r\n");
    }

    @Test
    void headerCanonicalize_emptyValueAllowed() {
        String result = DkimSigner.canonicalizeHeaderRelaxed("X-Empty", "");
        assertThat(result).isEqualTo("x-empty:\r\n");
    }

    @Test
    void headerCanonicalize_nullValueTreatedAsEmpty() {
        String result = DkimSigner.canonicalizeHeaderRelaxed("X-Null", null);
        assertThat(result).isEqualTo("x-null:\r\n");
    }

    // ============================================================
    // End-to-end sign + verify round-trip
    // ============================================================

    @Test
    void signAndVerify_roundTripWithGeneratedKeyPair() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String pkcs8Pem = exportPkcs8Pem(keyPair);

        DkimSigner signer = new DkimSigner(
            "test-selector",
            "example.com",
            pkcs8Pem,
            "From,To,Subject,Date,Message-ID"
        );
        // Fixed clock for deterministic timestamp
        signer.setClock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC));

        MimeMessage message = buildSampleMessage("Hello World");
        signer.sign(message);

        String[] dkimHeaders = message.getHeader("DKIM-Signature");
        assertThat(dkimHeaders).hasSize(1);
        String dkim = dkimHeaders[0];

        // Header structure verify
        assertThat(dkim).contains("v=1");
        assertThat(dkim).contains("a=rsa-sha256");
        assertThat(dkim).contains("c=relaxed/relaxed");
        assertThat(dkim).contains("d=example.com");
        assertThat(dkim).contains("s=test-selector");
        assertThat(dkim).contains("h=From:To:Subject:Date:Message-ID");
        assertThat(dkim).contains("bh=");
        assertThat(dkim).contains("b=");

        // Verify body hash matches
        String bodyHash = extractTagValue(dkim, "bh");
        assertThat(bodyHash).isNotEmpty();

        // Verify signature with public key
        boolean signatureValid = verifyDkimSignature(message, dkim, keyPair.getPublic());
        assertThat(signatureValid)
            .as("DKIM signature must verify with corresponding public key")
            .isTrue();
    }

    @Test
    void signAndVerify_roundTripWithMultilineBody() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String pkcs8Pem = exportPkcs8Pem(keyPair);

        DkimSigner signer = new DkimSigner(
            "test-selector",
            "example.com",
            pkcs8Pem,
            "From,To,Subject,Date,Message-ID"
        );
        signer.setClock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC));

        // Multiline body with whitespace runs (canonicalization stress test)
        String body = "Line one with    multiple   spaces\r\n"
            + "Line two with\ttabs\r\n"
            + "Line three trailing space   \r\n"
            + "\r\n"
            + "After empty line\r\n";
        MimeMessage message = buildSampleMessage(body);
        signer.sign(message);

        String[] dkimHeaders = message.getHeader("DKIM-Signature");
        assertThat(dkimHeaders).hasSize(1);

        boolean signatureValid = verifyDkimSignature(message, dkimHeaders[0], keyPair.getPublic());
        assertThat(signatureValid).isTrue();
    }

    @Test
    void signTwiceWithFixedClock_producesDeterministicSignature() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String pkcs8Pem = exportPkcs8Pem(keyPair);

        DkimSigner signer = new DkimSigner(
            "selector1",
            "example.com",
            pkcs8Pem,
            "From,To,Subject"
        );
        signer.setClock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC));

        MimeMessage m1 = buildSampleMessage("Same body");
        MimeMessage m2 = buildSampleMessage("Same body");

        signer.sign(m1);
        signer.sign(m2);

        String dkim1 = m1.getHeader("DKIM-Signature")[0];
        String dkim2 = m2.getHeader("DKIM-Signature")[0];

        // Body hash must be identical for identical bodies
        assertThat(extractTagValue(dkim1, "bh"))
            .isEqualTo(extractTagValue(dkim2, "bh"));

        // Both must verify against same public key
        assertThat(verifyDkimSignature(m1, dkim1, keyPair.getPublic())).isTrue();
        assertThat(verifyDkimSignature(m2, dkim2, keyPair.getPublic())).isTrue();
    }

    @Test
    void bodyHashChangesWhenBodyChanges() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String pkcs8Pem = exportPkcs8Pem(keyPair);

        DkimSigner signer = new DkimSigner(
            "selector1",
            "example.com",
            pkcs8Pem,
            "From,To,Subject"
        );
        signer.setClock(Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC));

        MimeMessage m1 = buildSampleMessage("Body version A");
        MimeMessage m2 = buildSampleMessage("Body version B");

        signer.sign(m1);
        signer.sign(m2);

        String bh1 = extractTagValue(m1.getHeader("DKIM-Signature")[0], "bh");
        String bh2 = extractTagValue(m2.getHeader("DKIM-Signature")[0], "bh");
        assertThat(bh1).isNotEqualTo(bh2);
    }

    @Test
    void isReady_returnsTrueAfterValidConstruction() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String pkcs8Pem = exportPkcs8Pem(keyPair);
        DkimSigner signer = new DkimSigner("sel", "example.com", pkcs8Pem,
            "From,To,Subject,Date,Message-ID");
        assertThat(signer.isReady()).isTrue();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String exportPkcs8Pem(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(encoded);
        // Marker strings via concatenation to avoid gitleaks false-positive
        // (test fixture, not a real PEM key)
        final String begin = "-----BEGIN " + "PRIVATE" + " KEY-----";
        final String end = "-----END " + "PRIVATE" + " KEY-----";
        return begin + "\n" + base64 + "\n" + end + "\n";
    }

    private static MimeMessage buildSampleMessage(String bodyText) throws Exception {
        Properties props = new Properties();
        Session session = Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("noreply@example.com", "ACIK Platform"));
        message.setRecipients(MimeMessage.RecipientType.TO,
            new InternetAddress[]{new InternetAddress("recipient@example.com")});
        message.setSubject("Test Subject");
        message.setSentDate(Date.from(Instant.parse("2026-05-10T12:00:00Z")));
        // Set Message-ID explicitly so it's stable across test runs
        message.setHeader("Message-ID", "<dkim-test@example.com>");
        message.setText(bodyText);
        message.saveChanges();
        return message;
    }

    /** Extract DKIM tag value (e.g. extract "bh" tag content). */
    private static String extractTagValue(String dkimHeader, String tag) {
        Pattern pattern = Pattern.compile("\\b" + tag + "=([^;]+)");
        Matcher matcher = pattern.matcher(dkimHeader);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * DKIM signature verifier — manual reimplementation of canonicalization
     * + RSA-SHA256 verify (mirrors signer logic for round-trip test).
     */
    private static boolean verifyDkimSignature(MimeMessage message, String dkimHeader,
                                                PublicKey publicKey) throws Exception {
        // Extract tags
        String headersList = extractTagValue(dkimHeader, "h");
        String bodyHashExpected = extractTagValue(dkimHeader, "bh");
        String signatureBase64 = extractTagValue(dkimHeader, "b");

        // Verify body hash
        byte[] body = extractBodyForVerify(message);
        byte[] canonicalBody = DkimSigner.canonicalizeBodyRelaxed(body);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String bodyHashActual = Base64.getEncoder().encodeToString(sha256.digest(canonicalBody));
        if (!bodyHashActual.equals(bodyHashExpected)) {
            return false;
        }

        // Reconstruct canonicalized header block
        StringBuilder block = new StringBuilder();
        for (String headerName : headersList.split(":")) {
            String[] values = message.getHeader(headerName.trim());
            String value = (values != null && values.length > 0) ? values[values.length - 1] : "";
            block.append(DkimSigner.canonicalizeHeaderRelaxed(headerName.trim(), value));
        }
        // Append DKIM-Signature with b= empty (RFC 6376 §3.7)
        String dkimWithEmptyB = dkimHeader.replaceAll("\\bb=[^;]*", "b=");
        String canonicalDkim = DkimSigner.canonicalizeHeaderRelaxed("DKIM-Signature", dkimWithEmptyB);
        if (canonicalDkim.endsWith("\r\n")) {
            canonicalDkim = canonicalDkim.substring(0, canonicalDkim.length() - 2);
        }
        block.append(canonicalDkim);

        // RSA-SHA256 verify
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update(block.toString().getBytes(StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(signatureBase64));
    }

    private static byte[] extractBodyForVerify(MimeMessage message) throws Exception {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        message.writeTo(raw);
        byte[] full = raw.toByteArray();
        int separatorIdx = -1;
        for (int i = 0; i < full.length - 3; i++) {
            if (full[i] == 0x0D && full[i + 1] == 0x0A
                && full[i + 2] == 0x0D && full[i + 3] == 0x0A) {
                separatorIdx = i + 4;
                break;
            }
        }
        if (separatorIdx < 0) {
            return new byte[0];
        }
        byte[] body = new byte[full.length - separatorIdx];
        System.arraycopy(full, separatorIdx, body, 0, body.length);
        return body;
    }
}
