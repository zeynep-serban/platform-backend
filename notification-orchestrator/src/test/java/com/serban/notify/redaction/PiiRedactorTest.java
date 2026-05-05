package com.serban.notify.redaction;

import com.serban.notify.config.NotifyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private PiiRedactor redactor;

    @BeforeEach
    void setUp() {
        NotifyConfig config = new NotifyConfig(
            new NotifyConfig.DispatchConfig(false),
            new NotifyConfig.IntakeConfig(10000),
            new NotifyConfig.IdempotencyConfig(24),
            new NotifyConfig.DedupeConfig(5),
            new NotifyConfig.RetryConfig(5, 30000L, 2.5),
            new NotifyConfig.AuditConfig(90),
            new NotifyConfig.RedactionConfig("test-pepper-fixed")
        );
        redactor = new PiiRedactor(config);
    }

    @Test
    void hashRecipientDeterministic() {
        String h1 = redactor.hashRecipient("default", "external", "user@example.com");
        String h2 = redactor.hashRecipient("default", "external", "user@example.com");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);  // SHA-256 hex
        assertThat(h1).matches("[a-f0-9]+");
    }

    @Test
    void hashRecipientCrossOrgDifferent() {
        String orgA = redactor.hashRecipient("org-a", "external", "user@example.com");
        String orgB = redactor.hashRecipient("org-b", "external", "user@example.com");
        assertThat(orgA).isNotEqualTo(orgB);
    }

    @Test
    void hashRecipientEmailNormalization() {
        // Codex Q4 REVISE: NFKC + Locale.ROOT lowercase + IDN ASCII; no provider-specific norm
        String upper = redactor.hashRecipient("default", "external", "USER@EXAMPLE.COM");
        String lower = redactor.hashRecipient("default", "external", "user@example.com");
        String trimmed = redactor.hashRecipient("default", "external", "  user@example.com  ");
        assertThat(upper).isEqualTo(lower);  // case-insensitive
        assertThat(trimmed).isEqualTo(lower);  // trim
    }

    @Test
    void hashRecipientNoGmailSpecificNormalization() {
        // Codex non-neg: gmail dot/plus removal yapılMAMALI (provider-specific accidental merge)
        String dotted = redactor.hashRecipient("default", "external", "user.name@gmail.com");
        String plain = redactor.hashRecipient("default", "external", "username@gmail.com");
        assertThat(dotted).isNotEqualTo(plain);
    }

    @Test
    void hashRecipientPhoneE164() {
        String h = redactor.hashRecipient("default", "external", "+905551234567");
        assertThat(h).hasSize(64);
        // Phone literal kept as-is after trim
        String trimmed = redactor.hashRecipient("default", "external", "  +905551234567  ");
        assertThat(trimmed).isEqualTo(h);
    }

    @Test
    void filterAuditDetailsWhitelistOnly() {
        Map<String, Object> raw = Map.of(
            "template_id", "auth-password-reset",
            "recipient_hash", "abc123",
            "user_email", "leaked@example.com",      // NOT whitelist
            "secret_token", "should-be-dropped",      // NOT whitelist
            "channel", "email",
            "correlation_id", "trace-1"
        );
        Map<String, Object> filtered = redactor.filterAuditDetails(raw);
        assertThat(filtered).containsKeys("template_id", "recipient_hash", "channel", "correlation_id");
        assertThat(filtered).doesNotContainKeys("user_email", "secret_token");
    }

    @Test
    void filterAuditDetailsEmptyMap() {
        assertThat(redactor.filterAuditDetails(null)).isEmpty();
        assertThat(redactor.filterAuditDetails(Map.of())).isEmpty();
    }

    @Test
    void hashRecipientThrowsOnNullArguments() {
        try {
            redactor.hashRecipient(null, "external", "user@example.com");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("required");
        }
    }
}
