package com.serban.notify.provider;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProductionConfigValidator unit test (Faz 23.2 PR-A — Codex 019dfae5 + iter-1 absorb).
 *
 * <p>Constructor: (env, redactionPepper, webhookSecret, authzKey,
 *   smtpTlsEnforce, smtpTlsCheckServerIdentity, preferencesEnabled, authzEnabled).
 *
 * <p>Codex iter-1 absorb: DKIM removed from validator (scope honesty until full
 * RFC 6376 wiring). Min secret length 32 char + non-whitespace check added.
 */
class ProductionConfigValidatorTest {

    private static final String OK_PEPPER = "rotated-pepper-from-vault-32-char-xxx";
    private static final String OK_WEBHOOK = "rotated-webhook-secret-32-char-vault-x";
    private static final String OK_AUTHZ = "rotated-authz-key-32-char-from-vault-xx";
    private static final String OK_UNSUB = "rotated-unsubscribe-secret-32-char-vault";

    @Test
    void nonProdProfileSkipsValidation() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "test" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "dev-only-pepper-not-for-production",
            "dev-only-secret-not-for-production",
            "dev-only-key-not-for-production",
            "dev-only-unsubscribe-secret-not-for-production",
            false, false, false, false  // all production guards off
        );

        // No throw — non-prod skips
        v.validate();
    }

    @Test
    void prodProfileWithDefaultPepperFails() {
        ProductionConfigValidator v = prodValidator(
            "dev-only-pepper-not-for-production", OK_WEBHOOK, OK_AUTHZ,
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.redaction.pepper=DEFAULT");
    }

    @Test
    void prodProfileWithShortPepperFails() {
        ProductionConfigValidator v = prodValidator(
            "short-pepper", OK_WEBHOOK, OK_AUTHZ, true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.redaction.pepper length=12 < 32");
    }

    @Test
    void prodProfileWithWhitespacePepperFails() {
        ProductionConfigValidator v = prodValidator(
            "   pepper-with-whitespace-32-char-xxxx   ", OK_WEBHOOK, OK_AUTHZ,
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contains leading/trailing whitespace");
    }

    @Test
    void prodProfileWithSmtpTlsDisabledFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ,
            false,  // ← TLS enforce off
            true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.smtp.tls.enforce=false");
    }

    @Test
    void prodProfileWithCheckServerIdentityFalseFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ,
            true,
            false,  // ← check-server-identity off
            true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.smtp.tls.check-server-identity=false");
    }

    @Test
    void prodProfileWithDefaultAuthzKeyFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, "dev-only-key-not-for-production",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.authz.internal-api-key=DEFAULT");
    }

    @Test
    void prodProfileWithAuthzDisabledFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ,
            true, true, true,
            false);  // ← authz off

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.authz.enabled=false");
    }

    @Test
    void prodProfileWithPreferencesDisabledFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ,
            true, true,
            false,  // ← preferences off
            true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.preferences.enabled=false");
    }

    @Test
    void prodProfileWithDefaultWebhookSecretFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, "dev-only-secret-not-for-production", OK_AUTHZ,
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.adapters.webhook.signing-secret=DEFAULT");
    }

    @Test
    void prodProfileWithAllRotatedPasses() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, true, true, true, true);

        // No throw — all guards passed
        v.validate();
    }

    @Test
    void prodProfileMultipleErrorsAggregated() {
        ProductionConfigValidator v = prodValidator(
            "dev-only-pepper-not-for-production",
            "dev-only-secret-not-for-production",
            "dev-only-key-not-for-production",
            "dev-only-unsubscribe-secret-not-for-production",
            false, false, false, false);

        // Errors: tls.enforce + check-server-identity + pepper DEFAULT
        // + webhook DEFAULT + authz disabled + authz key DEFAULT + preferences disabled
        // + unsubscribe secret DEFAULT (T1.1.8 Codex iter-1 absorb)
        // = 8 errors
        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("(8 error(s))");
    }

    private ProductionConfigValidator prodValidator(
        String pepper, String webhookSecret, String authzKey,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        return prodValidator(pepper, webhookSecret, authzKey, OK_UNSUB,
            tlsEnforce, tlsCheckIdentity, preferences, authz);
    }

    private ProductionConfigValidator prodValidator(
        String pepper, String webhookSecret, String authzKey, String unsubSecret,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });
        return new ProductionConfigValidator(
            env, pepper, webhookSecret, authzKey, unsubSecret,
            tlsEnforce, tlsCheckIdentity, preferences, authz
        );
    }

    /**
     * T1.1.8 (Faz 23.2.A) Codex iter-1 (019e12c0) absorb — unsubscribe signing
     * secret production guard.
     */
    @Test
    void prodProfileWithDefaultUnsubscribeSecretFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ,
            "dev-only-unsubscribe-secret-not-for-production",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.signing-secret=DEFAULT");
    }
}
