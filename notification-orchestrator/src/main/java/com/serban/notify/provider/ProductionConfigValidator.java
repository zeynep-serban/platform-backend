package com.serban.notify.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ProductionConfigValidator — startup fail-closed gate (Faz 23.2 PR-A —
 * Codex 019dfae5 cumulative absorb + iter-1 P1 absorb).
 *
 * <p>Validates that production profile has critical security configuration set:
 * <ul>
 *   <li>SMTP TLS enforce + STARTTLS required + check-server-identity hard-true</li>
 *   <li>Webhook HMAC: registry enabled OR legacy secret rotated + entropy check</li>
 *   <li>PII redaction pepper: rotated + min length 32 + non-whitespace</li>
 *   <li>Authz: enabled + internal-api-key rotated + entropy check</li>
 *   <li>Preferences: enabled (production opt-out only with explicit flag)</li>
 * </ul>
 *
 * <p>Codex iter-1 absorb (DKIM scope honesty): DKIM signing path foundation
 * stub'tır (full RFC 6376 follow-up). Bu PR-A'da DKIM'i ProductionConfigValidator'dan
 * çıkardık — false-positive security gate yaratmıyor. DKIM gerçek imzalama
 * tamamlandığında (Faz 23.2 PR-A.iter-2 veya PR-B) validator'a yeniden eklenir.
 *
 * <p>If any check fails, application context fails to start with explicit
 * error message — production deploy operator sees the gap before pod ready.
 *
 * <p>Codex absorb: "23.2 = production-ready foundation, prod cutover
 * iddiası kapalı". Bu validator prod profile'da fail-closed; non-prod
 * (test/dev/k8s test) profile'da SKIP.
 */
@Component
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private final Environment springEnv;
    /** Codex iter-1 P2 absorb: minimum entropy length for production secrets. */
    private static final int MIN_SECRET_LENGTH = 32;

    private final String redactionPepper;
    private final String webhookLegacySecret;
    private final String authzInternalApiKey;
    private final boolean smtpTlsEnforce;
    private final boolean smtpTlsCheckServerIdentity;
    private final boolean preferencesEnabled;
    private final boolean authzEnabled;
    /**
     * T1.1.8 (Faz 23.2.A) — unsubscribe HMAC signing secret production guard.
     * Default dev value MUST NOT leak to prod.
     */
    private final String unsubscribeSigningSecret;
    /**
     * T1.1.8 (Faz 23.2.A) P0.4 — unsubscribe link base URL production-host guard.
     * Default dev value `https://testai.acik.com/...` MUST NOT leak to prod;
     * prod must use `https://ai.acik.com/...` prefix (Codex 019e12d4 absorb).
     */
    private final String unsubscribeBaseUrl;

    public ProductionConfigValidator(
        Environment springEnv,
        @Value("${notify.redaction.pepper:dev-only-pepper-not-for-production}")
            String redactionPepper,
        @Value("${notify.adapters.webhook.signing-secret:dev-only-secret-not-for-production}")
            String webhookLegacySecret,
        @Value("${notify.authz.internal-api-key:dev-only-key-not-for-production}")
            String authzInternalApiKey,
        @Value("${notify.unsubscribe.signing-secret:dev-only-unsubscribe-secret-not-for-production}")
            String unsubscribeSigningSecret,
        @Value("${notify.unsubscribe.base-url:https://testai.acik.com/api/v1/notify/unsubscribe}")
            String unsubscribeBaseUrl,
        @Value("${notify.smtp.tls.enforce:false}") boolean smtpTlsEnforce,
        @Value("${notify.smtp.tls.check-server-identity:true}") boolean smtpTlsCheckServerIdentity,
        @Value("${notify.preferences.enabled:true}") boolean preferencesEnabled,
        @Value("${notify.authz.enabled:true}") boolean authzEnabled
    ) {
        this.springEnv = springEnv;
        this.redactionPepper = redactionPepper;
        this.webhookLegacySecret = webhookLegacySecret;
        this.authzInternalApiKey = authzInternalApiKey;
        this.unsubscribeSigningSecret = unsubscribeSigningSecret;
        this.unsubscribeBaseUrl = unsubscribeBaseUrl;
        this.smtpTlsEnforce = smtpTlsEnforce;
        this.smtpTlsCheckServerIdentity = smtpTlsCheckServerIdentity;
        this.preferencesEnabled = preferencesEnabled;
        this.authzEnabled = authzEnabled;
    }

    @PostConstruct
    void validate() {
        if (!isProductionProfile()) {
            log.info("ProductionConfigValidator: non-prod profile, validation SKIPPED");
            return;
        }
        log.info("ProductionConfigValidator: prod profile — running fail-closed validation");

        List<String> errors = new ArrayList<>();

        // SMTP TLS (Codex Q2 + iter-1 P2: check-server-identity hard-true in prod)
        if (!smtpTlsEnforce) {
            errors.add("notify.smtp.tls.enforce=false — production must enable "
                + "STARTTLS required (Codex Q2: plaintext fallback YASAK)");
        }
        if (!smtpTlsCheckServerIdentity) {
            errors.add("notify.smtp.tls.check-server-identity=false — production "
                + "must validate server identity (MITM defense; private relay "
                + "pinning ayrı runbook + audit boundary ile alternatif)");
        }

        // Redaction pepper (Codex Q5 + iter-1 P2 entropy)
        validateSecret(errors, "notify.redaction.pepper",
            redactionPepper, "dev-only-pepper-not-for-production");

        // Webhook HMAC (Codex Q3 + iter-1 P2 entropy)
        validateSecret(errors, "notify.adapters.webhook.signing-secret",
            webhookLegacySecret, "dev-only-secret-not-for-production");

        // Authz (Codex Q3 PR5 + iter-1 P2 entropy)
        if (!authzEnabled) {
            errors.add("notify.authz.enabled=false — production must enforce authz "
                + "(security regression risk; explicit opt-out only via "
                + "audit-tracked break-glass procedure)");
        }
        validateSecret(errors, "notify.authz.internal-api-key",
            authzInternalApiKey, "dev-only-key-not-for-production");

        // T1.1.8 unsubscribe HMAC signing secret (Codex 019e12c0 iter-1 absorb).
        // Email link integrity boundary; default dev value MUST NOT leak to prod.
        validateSecret(errors, "notify.unsubscribe.signing-secret",
            unsubscribeSigningSecret, "dev-only-unsubscribe-secret-not-for-production");

        // T1.1.8 P0.4 — unsubscribe base URL prod-host guard (Codex 019e12d4 absorb).
        // Email link routing integrity boundary; test/dev URLs MUST NOT leak to prod
        // (subscribers click link → wrong host → 404 / wrong-cluster credential drift).
        validateUnsubscribeBaseUrl(errors, unsubscribeBaseUrl);

        // Preferences (Codex PR5 Q1)
        if (!preferencesEnabled) {
            errors.add("notify.preferences.enabled=false — production must enable "
                + "subscriber preferences (KVKK + opt-out compliance)");
        }

        if (!errors.isEmpty()) {
            String summary = "Production config validation FAILED ("
                + errors.size() + " error(s)):\n  - "
                + String.join("\n  - ", errors);
            log.error(summary);
            throw new IllegalStateException(summary);
        }

        log.info("ProductionConfigValidator: all production guards PASSED");
    }

    private boolean isProductionProfile() {
        for (String profile : springEnv.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate a production secret (Codex iter-1 P2 absorb).
     *
     * Rejects:
     * <ul>
     *   <li>Equal to known default value</li>
     *   <li>Length &lt; {@link #MIN_SECRET_LENGTH} (32) — too short to be HMAC pepper</li>
     *   <li>Whitespace-only or null/blank</li>
     * </ul>
     */
    private static void validateSecret(List<String> errors, String key, String value, String knownDefault) {
        if (value == null || value.isBlank()) {
            errors.add(key + "=blank — production must set non-blank value");
            return;
        }
        if (knownDefault.equals(value)) {
            errors.add(key + "=DEFAULT — production must rotate from dev-only value "
                + "(Vault inject; min " + MIN_SECRET_LENGTH + " char entropy required)");
            return;
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            errors.add(key + " length=" + value.length() + " < " + MIN_SECRET_LENGTH
                + " — production secret must be at least " + MIN_SECRET_LENGTH
                + " char (entropy guard; Codex iter-1 P2 absorb)");
        }
        if (value.trim().length() != value.length()) {
            errors.add(key + " contains leading/trailing whitespace — likely config "
                + "injection error (Vault value should be raw secret without surrounding spaces)");
        }
    }

    /**
     * Validate unsubscribe base URL for production (T1.1.8 P0.4 + Codex 019e12d4).
     *
     * Rejects:
     * <ul>
     *   <li>Null or blank value</li>
     *   <li>Test/dev hosts (testai.acik.com, localhost, 127.0.0.1, *.test, *.dev)</li>
     *   <li>HTTP scheme (HTTPS required for email-link integrity over public network)</li>
     * </ul>
     *
     * <p>**Why prod-host guard**: subscriber clicks unsubscribe link in email →
     * link routing must hit prod cluster. Test URL leak → wrong cluster reaches
     * (subscriber's `subscriberId` doesn't exist there → 401/404 → user can't
     * opt-out → KVKK Art.13 right-to-info compliance gap + GDPR-equivalent risk).
     *
     * <p>**Allowed prod prefixes**: `https://ai.acik.com/`, `https://*.acik.com/`
     * (custom subdomain pattern allowed for multi-tenant), HTTPS-only.
     */
    private static void validateUnsubscribeBaseUrl(List<String> errors, String url) {
        String key = "notify.unsubscribe.base-url";
        if (url == null || url.isBlank()) {
            errors.add(key + "=blank — production must set non-blank value");
            return;
        }
        // HTTPS required (cleartext HTTP fallback YASAK in prod)
        if (!url.startsWith("https://")) {
            errors.add(key + "=" + url + " — production must use HTTPS scheme "
                + "(email links over public network; HTTP fallback exposes token query param)");
            return;
        }
        // Reject known dev/test hosts
        String lower = url.toLowerCase();
        if (lower.contains("testai.acik.com")
            || lower.contains("localhost")
            || lower.contains("127.0.0.1")
            || lower.contains(".test/")
            || lower.contains(".dev/")
            || lower.contains("staging-sw")) {
            errors.add(key + "=" + url + " — production must NOT use test/dev host "
                + "(subscriber link routing → wrong cluster → 404/credential drift; "
                + "expected prod prefix `https://ai.acik.com/`)");
        }
    }
}
