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
    /** P0.4 — production-host base URL (HTTPS, ai.acik.com prefix). */
    private static final String OK_BASE_URL = "https://ai.acik.com/api/v1/notify/unsubscribe";
    /**
     * A4 DKIM (Codex 019e1307 P0/P1 absorb) — fixture PEM PKCS#8 marker;
     * not a real key (test-only PEM marker check; signature/parse exercise
     * covered by DkimSignerTest with generated keypair).
     */
    private static final String OK_DKIM_PEM =
        "-----" + "BEGIN " + "PRIVATE" + " KEY-----\nMIIE...\n" + "-----" + "END " + "PRIVATE" + " KEY-----\n";

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
            "https://testai.acik.com/api/v1/notify/unsubscribe",
            false, "", "", "",  // DKIM disabled (test profile skip-path)
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
            "https://testai.acik.com/api/v1/notify/unsubscribe",
            false, false, false, false);

        // Errors: tls.enforce + check-server-identity + pepper DEFAULT
        // + webhook DEFAULT + authz disabled + authz key DEFAULT + preferences disabled
        // + unsubscribe secret DEFAULT (T1.1.8 Codex iter-1 absorb)
        // + unsubscribe base-url test-host (T1.1.8 P0.4 Codex 019e12d4 absorb)
        // = 9 errors
        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("(9 error(s))");
    }

    private ProductionConfigValidator prodValidator(
        String pepper, String webhookSecret, String authzKey,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        return prodValidator(pepper, webhookSecret, authzKey, OK_UNSUB, OK_BASE_URL,
            tlsEnforce, tlsCheckIdentity, preferences, authz);
    }

    private ProductionConfigValidator prodValidator(
        String pepper, String webhookSecret, String authzKey, String unsubSecret,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        return prodValidator(pepper, webhookSecret, authzKey, unsubSecret, OK_BASE_URL,
            tlsEnforce, tlsCheckIdentity, preferences, authz);
    }

    private ProductionConfigValidator prodValidator(
        String pepper, String webhookSecret, String authzKey, String unsubSecret,
        String baseUrl,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });
        // Codex 019e1307 P0/P1 absorb: helper passes DKIM "OK" defaults so existing
        // tests don't need rewrite. DKIM-specific tests use 5-arg overload below.
        return new ProductionConfigValidator(
            env, pepper, webhookSecret, authzKey, unsubSecret, baseUrl,
            true, "s1", "ai.acik.com", OK_DKIM_PEM,  // DKIM enabled+populated for happy-path
            tlsEnforce, tlsCheckIdentity, preferences, authz
        );
    }

    /**
     * 11-arg helper for DKIM-specific tests (A4 Codex 019e1307 P0/P1 absorb).
     */
    private ProductionConfigValidator prodValidatorWithDkim(
        String pepper, String webhookSecret, String authzKey, String unsubSecret,
        String baseUrl,
        boolean dkimEnabled, String dkimSelector, String dkimDomain, String dkimPem,
        boolean tlsEnforce, boolean tlsCheckIdentity,
        boolean preferences, boolean authz
    ) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });
        return new ProductionConfigValidator(
            env, pepper, webhookSecret, authzKey, unsubSecret, baseUrl,
            dkimEnabled, dkimSelector, dkimDomain, dkimPem,
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

    // ====================================================================
    // T1.1.8 P0.4 — unsubscribe base URL prod-host guard (Codex 019e12d4)
    // ====================================================================

    @Test
    void prodProfileWithTestaiHostBaseUrlFails() {
        // Default test-cluster URL leaked to prod profile
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://testai.acik.com/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.base-url")
            .hasMessageContaining("testai.acik.com")
            .hasMessageContaining("test/dev/staging subdomain blocklist");
    }

    @Test
    void prodProfileWithLocalhostBaseUrlFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://localhost:8080/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("localhost");
    }

    @Test
    void prodProfileWithHttpSchemeBaseUrlFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "http://ai.acik.com/api/v1/notify/unsubscribe",  // HTTP not HTTPS
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.base-url")
            .hasMessageContaining("HTTPS scheme");
    }

    @Test
    void prodProfileWithBlankBaseUrlFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "   ",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.base-url=blank");
    }

    @Test
    void prodProfileWith127LoopbackBaseUrlFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://127.0.0.1:8443/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("127.0.0.1");
    }

    @Test
    void prodProfileWithProdHostBaseUrlPasses() {
        // Production hostname (https://ai.acik.com/...) — all guards pass
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://ai.acik.com/api/v1/notify/unsubscribe",
            true, true, true, true);

        v.validate();  // No throw
    }

    @Test
    void prodProfileWithCustomSubdomainProdHostPasses() {
        // Custom subdomain pattern (multi-tenant prefix) — must pass
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://tenant1.acik.com/api/v1/notify/unsubscribe",
            true, true, true, true);

        v.validate();  // No throw
    }

    // ====================================================================
    // T1.1.8 P0.4 Codex iter-2 (019e1307) absorb — allowlist host pattern
    // ====================================================================

    @Test
    void prodProfileWithArbitraryHttpsHostFails() {
        // Codex iter-2 P1: substring deny-list let arbitrary HTTPS host pass.
        // URI parser allowlist must reject any host not matching ai.acik.com
        // canonical or .acik.com suffix.
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://evil.example/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.base-url")
            .hasMessageContaining("not in allowlist");
    }

    @Test
    void prodProfileWithSuffixAttackHostFails() {
        // Codex iter-2 P1: prefix deny-list let `https://ai.acik.com.evil.example`
        // through. URI.getHost() returns canonical `ai.acik.com.evil.example`
        // — does NOT match ai.acik.com exact and does NOT end with .acik.com
        // (ends with .example) → rejected.
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://ai.acik.com.evil.example/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not in allowlist")
            .hasMessageContaining("ai.acik.com.evil.example");
    }

    @Test
    void prodProfileWithIpv6LoopbackBaseUrlFails() {
        // Codex iter-2 P1: IPv6 loopback [::1] not caught by substring 127.0.0.1.
        // URI.getHost() returns "[::1]" → not in allowlist.
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://[::1]:8443/api/v1/notify/unsubscribe",
            true, true, true, true);

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not in allowlist");
    }

    @Test
    void prodProfileWithMalformedUrlFails() {
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "not-a-valid-url ::: bad",
            true, true, true, true);

        // Either malformed URL OR HTTPS scheme OR host check fires;
        // the validator must reject this in some form.
        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.unsubscribe.base-url");
    }

    @Test
    void prodProfileWithUppercaseHttpsSchemeIsAccepted() {
        // Scheme comparison must be case-insensitive (RFC 3986 §3.1)
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "HTTPS://ai.acik.com/api/v1/notify/unsubscribe",
            true, true, true, true);

        v.validate();  // No throw — case-insensitive scheme match
    }

    @Test
    void prodProfileWithProdHostQueryStringContainingTestaiPasses() {
        // Codex iter-2 finding: substring deny-list false-positive risk —
        // a safe prod URL with "testai.acik.com" in path/query was rejected.
        // Allowlist via URI.getHost() canonical comparison eliminates this.
        ProductionConfigValidator v = prodValidator(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB,
            "https://ai.acik.com/api/v1/notify/unsubscribe?ref=testai.acik.com",
            true, true, true, true);

        v.validate();  // No throw — host is ai.acik.com, query/path doesn't matter
    }

    // ====================================================================
    // A4 DKIM (Codex 019e1307 P0/P1 absorb) — DKIM mail security boundary
    // ====================================================================

    @Test
    void prodProfileWithDkimDisabledFails() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            false, "s1", "ai.acik.com", OK_DKIM_PEM,
            true, true, true, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.enabled=false")
            .hasMessageContaining("R3 mitigation");
    }

    @Test
    void prodProfileWithDkimSelectorBlankFails() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            true, "", "ai.acik.com", OK_DKIM_PEM,
            true, true, true, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.selector=blank");
    }

    @Test
    void prodProfileWithDkimDomainBlankFails() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            true, "s1", "", OK_DKIM_PEM,
            true, true, true, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.domain=blank");
    }

    @Test
    void prodProfileWithDkimPrivateKeyBlankFails() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            true, "s1", "ai.acik.com", "",
            true, true, true, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.private-key-pem=blank");
    }

    @Test
    void prodProfileWithDkimPrivateKeyMalformedFails() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            true, "s1", "ai.acik.com", "not a pem key garbage",
            true, true, true, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.private-key-pem malformed")
            .hasMessageContaining("BEGIN/END markers required");
    }

    @Test
    void prodProfileWithFullDkimConfigPasses() {
        ProductionConfigValidator v = prodValidatorWithDkim(
            OK_PEPPER, OK_WEBHOOK, OK_AUTHZ, OK_UNSUB, OK_BASE_URL,
            true, "s1", "ai.acik.com", OK_DKIM_PEM,
            true, true, true, true);

        v.validate();  // No throw — DKIM enabled + selector + domain + PEM marker present
    }
}
