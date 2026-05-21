package com.serban.notify.adapter.webpush;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VapidKeyService unit test (Faz 23.7 M7 T4.2 PR-W2.1).
 *
 * <p>Config validation coverage — key format checks, fail-fast guards.
 */
class VapidKeyServiceTest {

    @Test
    void blankPublicKeyFailsValidation() {
        WebPushConfig config = buildConfig("", "AAAA", "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("public-key required");
    }

    @Test
    void blankPrivateKeyFailsValidation() {
        WebPushConfig config = buildConfig("AAAA", "", "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("private-key required");
    }

    @Test
    void wrongLengthPublicKeyFailsValidation() {
        // 4-byte garbage instead of 65-byte uncompressed P-256
        String shortKey = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{0x04, 0x01, 0x02, 0x03});
        String validPrivate = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        WebPushConfig config = buildConfig(shortKey, validPrivate, "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("65-byte uncompressed P-256");
    }

    @Test
    void wrongPrefixPublicKeyFailsValidation() {
        // 65 bytes but no 0x04 prefix (compressed format YASAK)
        byte[] wrongPrefix = new byte[65];
        wrongPrefix[0] = 0x02; // compressed format
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(wrongPrefix);
        String validPrivate = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        WebPushConfig config = buildConfig(key, validPrivate, "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("0x04 prefix");
    }

    @Test
    void wrongLengthPrivateKeyFailsValidation() {
        byte[] validPublic = new byte[65];
        validPublic[0] = 0x04;
        String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(validPublic);
        String shortPriv = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        WebPushConfig config = buildConfig(pub, shortPriv, "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32-byte P-256 private scalar");
    }

    @Test
    void invalidBase64UrlFailsGracefully() {
        WebPushConfig config = buildConfig("!!!not-base64!!!", "AAAA", "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        assertThatThrownBy(svc::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("base64url-encoded");
    }

    @Test
    void validKeysParseSuccessfully() {
        // 65-byte uncompressed P-256 + 32-byte scalar
        byte[] validPublic = new byte[65];
        validPublic[0] = 0x04;
        // Random-ish X + Y (not actual valid curve point — sanity check only)
        for (int i = 1; i < 65; i++) validPublic[i] = (byte) i;
        String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(validPublic);
        byte[] validPrivate = new byte[32];
        for (int i = 0; i < 32; i++) validPrivate[i] = (byte) (i + 100);
        String priv = Base64.getUrlEncoder().withoutPadding().encodeToString(validPrivate);

        WebPushConfig config = buildConfig(pub, priv, "mailto:admin@example.com");
        VapidKeyService svc = new VapidKeyService(config);

        // Should not throw — PR-W2.1 scope: format validation only.
        svc.init();
        assertThat(svc.getConfig().vapidSubject()).isEqualTo("mailto:admin@example.com");
    }

    private WebPushConfig buildConfig(String publicKey, String privateKey, String subject) {
        return new WebPushConfig(
            true,                  // enabled
            publicKey,
            privateKey,
            subject,
            10,                    // requestTimeoutSeconds
            3600,                  // defaultTtlSeconds
            "none",                // paddingMode
            3072                   // maxPlaintextBytes
        );
    }
}
