package com.serban.notify.provider;

import com.serban.notify.domain.WebhookHmacKey;
import com.serban.notify.repository.WebhookHmacKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebhookHmacKeyRegistry — kid-aware HMAC key resolver (Faz 23.2 PR-A —
 * Codex 019dfae5 Q3 PARTIAL absorb).
 *
 * <p>Header scheme: {@code X-Notify-Signature: t=<ts>,kid=<active-kid>,v1=<sig>}
 *
 * <p>Codex Q3 absorb:
 * <ul>
 *   <li>kid-aware header — receiver verify ile uyumlu</li>
 *   <li>Rotation: active + next dual-sign window</li>
 *   <li>Secret resolve: secret_ref Vault path → ENV (env injection via ESO)</li>
 * </ul>
 *
 * <p>Production: secret_ref formatı {@code vault:kv/platform/notify/webhook/<kid>}
 * → ESO ExternalSecret bu Vault path'i çekip Kubernetes Secret'a populate eder;
 * pod restart sırasında env injection. Bu service env var'dan kid-specific secret
 * değerini okur: {@code NOTIFY_WEBHOOK_HMAC_KEY_<KID>} pattern.
 *
 * <p>Backward compat: legacy single-key config {@code notify.adapters.webhook.signing-secret}
 * — registry boş veya disabled durumunda fallback.
 */
@Service
public class WebhookHmacKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebhookHmacKeyRegistry.class);

    private final WebhookHmacKeyRepository repo;
    private final Environment springEnv;
    private final String legacySecret;
    private final boolean enabled;

    /** Per-kid secret cache (Vault → env injection sonrası in-memory). */
    private final ConcurrentHashMap<String, String> secretCache = new ConcurrentHashMap<>();

    public WebhookHmacKeyRegistry(
        WebhookHmacKeyRepository repo,
        Environment springEnv,
        @Value("${notify.adapters.webhook.signing-secret:dev-only-secret-not-for-production}")
            String legacySecret,
        @Value("${notify.adapters.webhook.hmac-registry.enabled:false}")
            boolean enabled
    ) {
        this.repo = repo;
        this.springEnv = springEnv;
        this.legacySecret = legacySecret;
        this.enabled = enabled;
        log.info("WebhookHmacKeyRegistry: enabled={}", enabled);
    }

    /**
     * Resolve active signing key.
     *
     * @return (kid, secret) tuple — registry disabled durumda kid="legacy"
     *         + legacySecret döner
     */
    public ActiveKey resolveActive() {
        if (!enabled) {
            return new ActiveKey("legacy", legacySecret);
        }
        Optional<WebhookHmacKey> active = repo.findByStatus(WebhookHmacKey.Status.ACTIVE);
        if (active.isEmpty()) {
            log.warn("WebhookHmacKeyRegistry: no ACTIVE key — falling back to legacy");
            return new ActiveKey("legacy", legacySecret);
        }
        WebhookHmacKey key = active.get();
        String secret = resolveSecret(key.getKid(), key.getSecretRef());
        if (secret == null || secret.isBlank()) {
            log.warn("WebhookHmacKeyRegistry: kid={} secret unresolved — falling back to legacy",
                key.getKid());
            return new ActiveKey("legacy", legacySecret);
        }
        return new ActiveKey(key.getKid(), secret);
    }

    /**
     * Resolve secret for given kid (used by receiver-side verifier sample +
     * future dual-sign rotation window).
     */
    public Optional<String> resolveSecretForKid(String kid) {
        Optional<WebhookHmacKey> key = repo.findByKid(kid);
        if (key.isEmpty()) return Optional.empty();
        if (key.get().getStatus() == WebhookHmacKey.Status.RETIRED) {
            log.debug("WebhookHmacKeyRegistry: kid={} retired", kid);
            // Retired keys still resolvable for verify-window (receivers can verify
            // older signatures); operational policy decides expiration.
        }
        String secret = resolveSecret(kid, key.get().getSecretRef());
        return Optional.ofNullable(secret);
    }

    /**
     * Secret resolution: ENV var pattern {@code NOTIFY_WEBHOOK_HMAC_KEY_<KID-UPPERCASE>}.
     * Production: ESO ExternalSecret Vault'tan çeker; ENV injection.
     * Test/dev: ENV manuel set.
     */
    private String resolveSecret(String kid, String secretRef) {
        return secretCache.computeIfAbsent(kid, k -> {
            String envVarName = "NOTIFY_WEBHOOK_HMAC_KEY_" + k.toUpperCase().replace('-', '_');
            String value = springEnv.getProperty(envVarName);
            if (value == null || value.isBlank()) {
                log.warn("kid={} secret ENV var {} not set (secret_ref={})",
                    k, envVarName, secretRef);
                return null;
            }
            return value;
        });
    }

    /** Cache invalidate on rotation (operator runbook). */
    public void invalidateCache() {
        secretCache.clear();
    }

    /** Active key tuple. */
    public record ActiveKey(String kid, String secret) {}
}
