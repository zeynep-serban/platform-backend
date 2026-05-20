package com.serban.notify.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
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
    /**
     * A4 DKIM (Faz 23.2 R3 mitigation) — Codex 019e1307 P0/P1 absorb 2026-05-10.
     *
     * <p>PR-B1 2026-05-20 (Codex 019e4514 plan-time consult): strategy enum
     * (app|relay|disabled) chooses validator branch:
     * <ul>
     *   <li>{@code app}: app-side DkimSigner — require enabled=true + selector
     *       + domain + private-key-pem (PR-A1 original contract; R3 mitigation)</li>
     *   <li>{@code relay}: provider-managed DKIM (Office 365 Native, AWS SES,
     *       etc.) — require enabled=false + relay.provider + relay.domain +
     *       SMTP TLS enforce + From domain alignment</li>
     *   <li>{@code disabled}: prod REJECT (R3 contract bozulur; break-glass
     *       timeboxed flag varsa istisna — bu PR scope dışı)</li>
     * </ul>
     */
    private final String dkimStrategy;
    private final boolean dkimEnabled;
    private final String dkimSelector;
    private final String dkimDomain;
    private final String dkimPrivateKeyPem;
    private final String dkimRelayProvider;
    private final String dkimRelayDomain;
    /**
     * From: address (notify.adapters.smtp.from-address) — relay strategy
     * domain alignment guard. Must end with .{relay.domain} or equal relay.domain.
     */
    private final String smtpFromAddress;

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
        @Value("${notify.dkim.strategy:app}") String dkimStrategy,
        @Value("${notify.dkim.enabled:false}") boolean dkimEnabled,
        @Value("${notify.dkim.selector:}") String dkimSelector,
        @Value("${notify.dkim.domain:}") String dkimDomain,
        @Value("${notify.dkim.private-key-pem:}") String dkimPrivateKeyPem,
        @Value("${notify.dkim.relay.provider:}") String dkimRelayProvider,
        @Value("${notify.dkim.relay.domain:}") String dkimRelayDomain,
        @Value("${notify.adapters.smtp.from-address:}") String smtpFromAddress,
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
        this.dkimStrategy = dkimStrategy;
        this.dkimEnabled = dkimEnabled;
        this.dkimSelector = dkimSelector;
        this.dkimDomain = dkimDomain;
        this.dkimPrivateKeyPem = dkimPrivateKeyPem;
        this.dkimRelayProvider = dkimRelayProvider;
        this.dkimRelayDomain = dkimRelayDomain;
        this.smtpFromAddress = smtpFromAddress;
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

        // A4 DKIM mail security boundary (Codex 019e1307 P0/P1 + PR-B1 strategy
        // expansion 019e4514). Prod profile: strategy enum branches validator.
        validateDkimStrategy(errors, dkimStrategy, dkimEnabled, dkimSelector,
            dkimDomain, dkimPrivateKeyPem, dkimRelayProvider, dkimRelayDomain,
            smtpFromAddress, smtpTlsEnforce);

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
     * Validate unsubscribe base URL for production (T1.1.8 P0.4 + Codex iter-2
     * 019e1307 absorb — allowlist host pattern, not deny-list substring).
     *
     * <p>**Implementation contract**:
     * <ol>
     *   <li>Parse URL with {@link URI} — invalid URL → fail-closed</li>
     *   <li>Scheme MUST equal {@code https} (case-insensitive)</li>
     *   <li>Host MUST equal {@code ai.acik.com} OR end with {@code .acik.com}
     *       suffix (canonical multi-tenant pattern)</li>
     * </ol>
     *
     * <p>**Why allowlist not deny-list (Codex iter-2 finding)**: substring
     * deny-list lets {@code https://evil.example/...} pass. Worse, a maliciously
     * crafted host like {@code ai.acik.com.evil.example} passes string-prefix
     * checks. Allowlist via {@code URI.getHost()} canonical match is the only
     * safe approach for security-boundary URL validation.
     *
     * <p>**Why prod-host guard**: subscriber clicks unsubscribe link in email →
     * link routing must hit prod cluster. Test URL leak → wrong cluster reaches
     * (subscriber's `subscriberId` doesn't exist there → 401/404 → user can't
     * opt-out → KVKK Art.13 right-to-info compliance gap + GDPR-equivalent risk).
     *
     * <p>**Rejected hosts** (allowlist-implicit): all non-{@code .acik.com} hosts,
     * IPv4 loopback ({@code 127.0.0.1}), IPv6 loopback ({@code ::1}), localhost,
     * test/dev TLDs, IP literals — none match {@code ai.acik.com} canonical or
     * {@code .acik.com} suffix.
     */
    private static void validateUnsubscribeBaseUrl(List<String> errors, String url) {
        String key = "notify.unsubscribe.base-url";
        if (url == null || url.isBlank()) {
            errors.add(key + "=blank — production must set non-blank value");
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            errors.add(key + "=" + url + " — malformed URL ("
                + e.getMessage() + "); production must use valid URI syntax");
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            errors.add(key + "=" + url + " — production must use HTTPS scheme "
                + "(email links over public network; HTTP fallback exposes token query param). "
                + "Found scheme: " + scheme);
            return;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            errors.add(key + "=" + url + " — URL missing host component "
                + "(IPv6 literal/userinfo without authority not allowed in prod; "
                + "URI.getHost() returned " + host + ")");
            return;
        }
        // Canonical host allowlist: exact ai.acik.com OR .acik.com suffix
        // (multi-tenant subdomain pattern). Case-insensitive comparison.
        // Codex iter-2 absorb: also blocklist known test/dev subdomains within
        // .acik.com (testai.*, staging-sw.* etc) — they share the parent suffix
        // but are NOT prod cluster routes.
        String hostLower = host.toLowerCase();
        boolean exactMatch = "ai.acik.com".equals(hostLower);
        boolean suffixMatch = hostLower.endsWith(".acik.com")
            && !hostLower.equals(".acik.com");  // defense: bare suffix invalid
        if (!exactMatch && !suffixMatch) {
            errors.add(key + "=" + url + " — production host '" + host
                + "' not in allowlist. Allowed: 'ai.acik.com' (exact) or '*.acik.com' "
                + "suffix (multi-tenant). Subscriber link routing → wrong cluster → "
                + "404/credential drift; KVKK Art.13 right-to-info compliance gap.");
            return;
        }
        // Test/dev subdomain blocklist — even within .acik.com suffix
        if ("testai.acik.com".equals(hostLower)
            || hostLower.startsWith("test.")
            || hostLower.startsWith("dev.")
            || hostLower.startsWith("staging.")
            || hostLower.startsWith("staging-")
            || hostLower.contains(".staging-sw.")) {
            errors.add(key + "=" + url + " — production host '" + host
                + "' matches test/dev/staging subdomain blocklist. "
                + "Production must use 'ai.acik.com' (exact) or non-test "
                + "tenant subdomain. Subscriber link routing → wrong cluster → "
                + "404/credential drift; KVKK Art.13 right-to-info compliance gap.");
        }
    }

    /**
     * Validate DKIM strategy (PR-B1 2026-05-20, Codex 019e4514 plan-time consult).
     *
     * <p>Strategy enum branches validator contract:
     *
     * <h3>app — App-Side DKIM (PR-A1 original contract; R3 mitigation)</h3>
     * <ul>
     *   <li>{@code enabled=true} required</li>
     *   <li>{@code selector} non-blank (DNS TXT record name)</li>
     *   <li>{@code domain} non-blank (signing domain)</li>
     *   <li>{@code private-key-pem} populated + PEM marker sanity (PKCS#8)</li>
     * </ul>
     *
     * <h3>relay — Provider-Managed DKIM (Office 365 Native, AWS SES, etc.)</h3>
     * <ul>
     *   <li>{@code enabled=false} required (app-side signer must NOT inject)</li>
     *   <li>{@code relay.provider} non-blank (e.g. {@code office365})</li>
     *   <li>{@code relay.domain} non-blank (DKIM-signed domain — must align From:)</li>
     *   <li>{@code smtp.tls.enforce=true} (STARTTLS to provider relay)</li>
     *   <li>{@code adapters.smtp.from-address} domain MUST align with relay.domain
     *       (e.g. {@code ai@acik.com} aligns with {@code acik.com})</li>
     * </ul>
     *
     * <h3>disabled — Unsigned mail (R3 contract violation)</h3>
     * <p>Prod REJECT. Outbound mail unsigned → SPF/DMARC only → spam folder risk.
     * Break-glass timeboxed flag scope outside this PR.
     *
     * <p><b>Why provider-managed (relay) is long-term stable</b> (Codex
     * 019e4514 plan-time): key rotation Microsoft-managed (no Vault re-seed),
     * one-time DNS CNAME setup (selector1 + selector2 → onmicrosoft.com),
     * private key not in Vault (less attack surface), industry-standard SaaS
     * mail pattern.
     */
    static void validateDkimStrategy(List<String> errors, String strategy,
                                      boolean enabled, String selector,
                                      String domain, String privateKeyPem,
                                      String relayProvider, String relayDomain,
                                      String smtpFromAddress, boolean smtpTlsEnforce) {
        String strategyLower = strategy == null ? "" : strategy.trim().toLowerCase();
        switch (strategyLower) {
            case "app":
                validateDkimApp(errors, enabled, selector, domain, privateKeyPem);
                break;
            case "relay":
                validateDkimRelay(errors, enabled, relayProvider, relayDomain,
                    smtpFromAddress, smtpTlsEnforce);
                break;
            case "disabled":
                errors.add("notify.dkim.strategy=disabled — production REJECT "
                    + "(R3 mitigation contract: outbound mail unsigned → spam folder + "
                    + "reputation drop). Break-glass timeboxed flag scope outside this PR.");
                break;
            case "":
                // Default fallback (yml default `app`) — handled by `app` branch above.
                // Defensive: if Spring injects null/empty despite yaml default, treat as app.
                validateDkimApp(errors, enabled, selector, domain, privateKeyPem);
                break;
            default:
                errors.add("notify.dkim.strategy=" + strategy + " — invalid enum value. "
                    + "Valid: app | relay | disabled");
        }
    }

    /**
     * Validate app-side DKIM strategy (PR-A1 original contract; R3 mitigation).
     */
    static void validateDkimApp(List<String> errors, boolean enabled,
                                 String selector, String domain, String privateKeyPem) {
        if (!enabled) {
            errors.add("notify.dkim.strategy=app + notify.dkim.enabled=false — "
                + "app strategy requires enabled=true (R3 mitigation: outbound mail "
                + "integrity + spam folder defense). Set strategy=relay for "
                + "provider-managed DKIM (Office 365 Native), or strategy=disabled "
                + "for break-glass unsigned (prod REJECT).");
            return;
        }
        if (selector == null || selector.isBlank()) {
            errors.add("notify.dkim.selector=blank — app strategy DKIM signer requires "
                + "selector (DNS TXT record name; e.g. 's1' for s1._domainkey.<domain>)");
        }
        if (domain == null || domain.isBlank()) {
            errors.add("notify.dkim.domain=blank — app strategy DKIM signer requires "
                + "signing domain (must align with From: header domain for valid signatures)");
        }
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            errors.add("notify.dkim.private-key-pem=blank — app strategy DKIM signer requires "
                + "RSA PKCS#8 private key (Vault inject; openssl pkcs8 -topk8 -nocrypt format)");
        } else if (!privateKeyPem.contains("BEGIN") || !privateKeyPem.contains("END")) {
            errors.add("notify.dkim.private-key-pem malformed — must be PEM-encoded PKCS#8 "
                + "(BEGIN/END markers required; Vault inject from openssl pkcs8 output)");
        }
    }

    /**
     * Validate relay DKIM strategy (PR-B1 — provider-managed DKIM).
     *
     * <p>App-side signer MUST NOT inject (enabled=false). DKIM signing handled by
     * SMTP relay provider (Office 365 Native, AWS SES, SendGrid, etc.).
     * Validator enforces: provider/domain config + SMTP TLS + From: alignment.
     */
    static void validateDkimRelay(List<String> errors, boolean enabled,
                                   String relayProvider, String relayDomain,
                                   String smtpFromAddress, boolean smtpTlsEnforce) {
        if (enabled) {
            errors.add("notify.dkim.strategy=relay + notify.dkim.enabled=true — "
                + "relay strategy requires enabled=false (app-side signer MUST NOT "
                + "double-sign provider-managed DKIM). Disable app-side signer to "
                + "use provider-managed DKIM (Office 365 Native, AWS SES, etc.).");
        }
        if (relayProvider == null || relayProvider.isBlank()) {
            errors.add("notify.dkim.relay.provider=blank — relay strategy requires "
                + "provider identifier (e.g. 'office365', 'aws-ses', 'sendgrid'). "
                + "Provider owns DKIM signing keys + DNS CNAME records.");
        }
        if (relayDomain == null || relayDomain.isBlank()) {
            errors.add("notify.dkim.relay.domain=blank — relay strategy requires "
                + "signing domain (DKIM `d=` value; must align From: header domain). "
                + "E.g. 'acik.com' for ai@acik.com From: address.");
            return;
        }
        // From: address domain alignment guard (DKIM alignment requires d= matches
        // From: header domain for SPF/DMARC pass).
        if (smtpFromAddress == null || smtpFromAddress.isBlank()) {
            errors.add("notify.adapters.smtp.from-address=blank — relay strategy "
                + "requires From: address to align with relay.domain DKIM signature.");
        } else {
            String fromLower = smtpFromAddress.trim().toLowerCase();
            String relayLower = relayDomain.trim().toLowerCase();
            int atIdx = fromLower.indexOf('@');
            if (atIdx < 0) {
                errors.add("notify.adapters.smtp.from-address=" + smtpFromAddress
                    + " — invalid format (missing @). Must be valid email address.");
            } else {
                String fromDomain = fromLower.substring(atIdx + 1);
                // From: domain must equal relay.domain OR be subdomain (.relay.domain).
                // Strict alignment: SPF/DMARC adkim=s requires exact match; adkim=r
                // (default) allows subdomain. This validator uses relaxed (r) since
                // DMARC policy default is relaxed; tighter aligment via DMARC config.
                if (!fromDomain.equals(relayLower)
                    && !fromDomain.endsWith("." + relayLower)) {
                    errors.add("notify.adapters.smtp.from-address=" + smtpFromAddress
                        + " — From: domain '" + fromDomain + "' does not align with "
                        + "notify.dkim.relay.domain='" + relayDomain + "'. DKIM "
                        + "signature `d=" + relayDomain + "` requires From: domain "
                        + "to equal or be subdomain of relay.domain (DMARC adkim=r "
                        + "relaxed alignment).");
                }
            }
        }
        // STARTTLS enforce already validated by main flow; restate as relay-specific
        // dependency for clearer error message in audit.
        if (!smtpTlsEnforce) {
            errors.add("notify.dkim.strategy=relay + notify.smtp.tls.enforce=false — "
                + "relay strategy requires STARTTLS to provider relay (Office 365/SES "
                + "demand TLS; plaintext = mail bounce + reputation drop).");
        }
    }
}
