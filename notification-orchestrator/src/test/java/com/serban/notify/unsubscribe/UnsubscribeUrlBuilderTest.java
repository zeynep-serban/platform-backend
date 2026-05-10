package com.serban.notify.unsubscribe;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UnsubscribeUrlBuilder unit tests (T1.1.8 PR-B Faz 23.2.A).
 */
class UnsubscribeUrlBuilderTest {

    private final UnsubscribeTokenService tokenService = new UnsubscribeTokenService(
        "test-signing-secret-fixed-for-deterministic-tests", 90
    );
    private final UnsubscribeUrlBuilder builder = new UnsubscribeUrlBuilder(
        tokenService, "https://testai.acik.com/api/v1/notify/unsubscribe"
    );

    @Test
    void buildIncludesTokenQueryParam() {
        String url = builder.build("subscriber-1204", "auth.password-reset");

        assertThat(url).startsWith("https://testai.acik.com/api/v1/notify/unsubscribe?token=");
        assertThat(url).contains(".");  // token has payload.signature dot

        // Round-trip: token query param should verify
        String token = url.substring(url.indexOf("?token=") + "?token=".length());
        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = tokenService.verify(token);
        assertThat(verified).isPresent();
        assertThat(verified.get().subscriberId()).isEqualTo("subscriber-1204");
        assertThat(verified.get().topicKey()).isEqualTo("auth.password-reset");
    }

    @Test
    void buildGlobalUnsubscribeNullTopic() {
        String url = builder.build("subscriber-1204", null);

        assertThat(url).startsWith("https://testai.acik.com/api/v1/notify/unsubscribe?token=");

        String token = url.substring(url.indexOf("?token=") + "?token=".length());
        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = tokenService.verify(token);
        assertThat(verified).isPresent();
        assertThat(verified.get().subscriberId()).isEqualTo("subscriber-1204");
        assertThat(verified.get().topicKey()).isNull();
    }

    @Test
    void buildRejectsNullOrBlankSubscriberId() {
        assertThatThrownBy(() -> builder.build(null, "topic"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subscriberId required");

        assertThatThrownBy(() -> builder.build("", "topic"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subscriberId required");

        assertThatThrownBy(() -> builder.build("   ", "topic"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subscriberId required");
    }

    @Test
    void buildProductionHostnameOverride() {
        UnsubscribeUrlBuilder prodBuilder = new UnsubscribeUrlBuilder(
            tokenService, "https://ai.acik.com/api/v1/notify/unsubscribe"
        );

        String url = prodBuilder.build("subscriber-1204", "billing.invoice");
        assertThat(url).startsWith("https://ai.acik.com/api/v1/notify/unsubscribe?token=");
    }
}
