package com.serban.notify.adapter.webpush;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * WebPush adapter config (Faz 23.7 M7 T4.2 PR-W2.1 — Codex {@code 019e49e7}
 * plan-time AGREE absorb).
 *
 * <p>Browser push (RFC 8030 + RFC 8291 + VAPID RFC 8292) — FCM/APNS mobile
 * Faz 22.2 dep, agent scope dışı.
 *
 * <p>Default <strong>disabled</strong>: bean registration
 * {@code @ConditionalOnProperty(name="notify.adapters.webpush.enabled",
 * havingValue="true")}. Disabled iken key parse edilmez, local/test
 * context kırılmaz.
 *
 * <p>Production safety toggle pattern (Codex iter-3 P8):
 * <ul>
 *   <li>{@code enabled=false} default → adapter bean YOK; submit allow-list
 *       push channel reject</li>
 *   <li>{@code enabled=true} + VAPID key invalid → startup fail-fast
 *       (ProductionConfigValidator)</li>
 *   <li>Test cluster flip true ile aktif edilir; prod cutover M7 T4.2
 *       acceptance sonrası</li>
 * </ul>
 */
@ConfigurationProperties("notify.adapters.webpush")
@Validated
public record WebPushConfig(
    @DefaultValue("false") boolean enabled,

    /**
     * VAPID public key — base64url-encoded uncompressed P-256 (65 bytes:
     * 0x04 prefix + 32-byte X + 32-byte Y). RFC 8292 Section 3.2.
     * Production: Vault {@code kv/platform/notify/vapid_public_key}.
     * Enabled iken non-blank zorunlu (ProductionConfigValidator gate).
     */
    @DefaultValue("") String publicKey,

    /**
     * VAPID private key — base64url-encoded 32-byte P-256 private scalar.
     * Production: Vault {@code kv/platform/notify/vapid_private_key}.
     * Enabled iken non-blank zorunlu.
     */
    @DefaultValue("") String privateKey,

    /**
     * VAPID JWT {@code sub} claim — mailto: URI; push service operator
     * contact. RFC 8292 Section 2.1. Production: operasyonel email.
     */
    @DefaultValue("mailto:notify-admin@example.com")
    @Pattern(regexp = "^mailto:.+@.+\\..+$",
             message = "vapid-subject must be a mailto: URI")
    String vapidSubject,

    /**
     * Push service request timeout (RFC 8030 endpoint POST). FCM/Mozilla
     * default 5-10 seconds.
     */
    @DefaultValue("10") int requestTimeoutSeconds,

    /**
     * Default TTL header value (RFC 8030 Section 5.2). Push service
     * mesajı bu kadar saniye saklar; subscriber online değilse drop.
     */
    @DefaultValue("3600") int defaultTtlSeconds,

    /**
     * Payload encryption padding stratejisi — Codex iter-3 P5 absorb:
     * minimal default; explicit bucket mode için future config.
     */
    @DefaultValue("none") @Pattern(regexp = "^(none|bucket)$")
    String paddingMode,

    /**
     * Max plaintext payload byte cap (FCM limit 4KB; bizim default 3KB
     * safety margin). Codex iter-3 P5 absorb.
     */
    @DefaultValue("3072") int maxPlaintextBytes
) {}
