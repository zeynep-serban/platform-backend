package com.serban.notify.adapter.webpush;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * VAPID server key pair loader + parser (Faz 23.7 M7 T4.2 PR-W2.1 —
 * Codex {@code 019e49e7} plan-time AGREE absorb).
 *
 * <p>Key seed pattern (Codex P2 absorb):
 * <ul>
 *   <li>Vault path {@code kv/platform/notify/vapid_*} — operator manual
 *       seed; backend startup'ta auto-generate YASAK (immutable + audit
 *       integrity)</li>
 *   <li>Format: raw base64url uncompressed P-256 public (65 bytes; 0x04
 *       prefix + X + Y) + 32-byte private scalar (NOT PKCS#8 ASN.1)</li>
 *   <li>Service-specific, environment-specific key pair (test/prod ayrı,
 *       notification-orchestrator-only)</li>
 * </ul>
 *
 * <p><strong>Conditional registration</strong>: {@code
 * @ConditionalOnProperty(notify.adapters.webpush.enabled=true)} — disabled
 * iken bean parse edilmez, local/test context kırılmaz.
 *
 * <p><strong>Fail-fast</strong>: enabled iken key invalid (blank, wrong
 * curve, wrong length) → {@link IllegalStateException} startup'ta;
 * ProductionConfigValidator de aynı kontrolü prod profile için yapacak.
 *
 * <p>Rotation policy (Codex P2 ek concern): mevcut
 * {@code subscriber_push_endpoint} row'larında {@code vapid_key_id} yok →
 * key rotation tüm browser subscription'ları invalidate eder. Production
 * rotation runbook follow-up (PR-W2.4+); şu an long-lived single key.
 */
@Service
@ConditionalOnProperty(
    name = "notify.adapters.webpush.enabled",
    havingValue = "true"
)
public class VapidKeyService {

    private static final Logger log = LoggerFactory.getLogger(VapidKeyService.class);

    private final WebPushConfig config;
    private ECPublicKey publicKey;
    private ECPrivateKey privateKey;

    public VapidKeyService(WebPushConfig config) {
        this.config = config;
    }

    /**
     * @PostConstruct key parse + validation. Codex P2 ek concern absorb:
     * key formatı raw base64url uncompressed P-256, NOT PKCS#8 ASN.1.
     * Parsing logic stub burada; gerçek implementation PR-W2.2'de
     * web-push library kullanılarak refine edilecek.
     */
    @PostConstruct
    public void init() {
        validateConfigOrThrow();
        // PR-W2.2 implementation: web-push library Utils.loadPublicKey +
        // Utils.loadPrivateKey ile base64url decode + ECPublicKey /
        // ECPrivateKey instantiate.
        log.info("VapidKeyService activated: subject={} (key parsing PR-W2.2 scope)",
            config.vapidSubject());
    }

    private void validateConfigOrThrow() {
        if (config.publicKey() == null || config.publicKey().isBlank()) {
            throw new IllegalStateException(
                "notify.adapters.webpush.public-key required when enabled=true"
            );
        }
        if (config.privateKey() == null || config.privateKey().isBlank()) {
            throw new IllegalStateException(
                "notify.adapters.webpush.private-key required when enabled=true"
            );
        }
        // Sanity check: base64url decode mümkün olmalı.
        try {
            byte[] pubDecoded = Base64.getUrlDecoder().decode(config.publicKey());
            if (pubDecoded.length != 65 || pubDecoded[0] != 0x04) {
                throw new IllegalStateException(
                    "vapid public-key must be base64url 65-byte uncompressed P-256 "
                        + "(0x04 prefix + 32-byte X + 32-byte Y); got " + pubDecoded.length + " bytes"
                );
            }
            byte[] privDecoded = Base64.getUrlDecoder().decode(config.privateKey());
            if (privDecoded.length != 32) {
                throw new IllegalStateException(
                    "vapid private-key must be base64url 32-byte P-256 private scalar; got "
                        + privDecoded.length + " bytes"
                );
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "vapid keys must be base64url-encoded (RFC 4648 Section 5)", e
            );
        }
    }

    public WebPushConfig getConfig() {
        return config;
    }
}
