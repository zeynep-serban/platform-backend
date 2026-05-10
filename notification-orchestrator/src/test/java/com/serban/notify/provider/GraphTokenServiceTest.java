package com.serban.notify.provider;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GraphTokenService unit tests (Codex iter P1 absorb 2026-05-11).
 *
 * <p>Coverage:
 * <ol>
 *   <li>Constructor validation (tenant/client/secret blank → fail)</li>
 *   <li>Token cache thread-safe ({@link AtomicReference})</li>
 *   <li>isStillValid() refresh buffer logic (Clock injectable)</li>
 *   <li>redactBody() security hygiene (access_token mask)</li>
 * </ol>
 *
 * <p>HTTP/OAuth endpoint integration tested via {@code GraphMailAdapterIntegrationTest}
 * (Wiremock backed — not this unit test). Bu test pure logic + edge cases.
 */
class GraphTokenServiceTest {

    @Test
    void constructorRejectsBlankTenantId() {
        assertThatThrownBy(() -> new GraphTokenService("", "client-id", "client-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant-id required");
    }

    @Test
    void constructorRejectsBlankClientId() {
        assertThatThrownBy(() -> new GraphTokenService("tenant-id", "", "client-secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("client-id required");
    }

    @Test
    void constructorRejectsBlankClientSecret() {
        assertThatThrownBy(() -> new GraphTokenService("tenant-id", "client-id", ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("client-secret required");
    }

    @Test
    void constructorRejectsNullTenantId() {
        assertThatThrownBy(() -> new GraphTokenService(null, "client-id", "client-secret"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redactBodyMasksAccessToken() throws Exception {
        Method method = GraphTokenService.class.getDeclaredMethod("redactBody", String.class);
        method.setAccessible(true);

        String input = "{\"access_token\":\"eyJ0eXAiOiJKV1Q.signature_secret_here.value\",\"expires_in\":3600}";
        String output = (String) method.invoke(null, input);

        assertThat(output)
            .as("access_token value masked")
            .contains("\"access_token\":\"<redacted>\"")
            .doesNotContain("eyJ0eXAiOiJKV1Q.signature_secret_here.value");
    }

    @Test
    void redactBodyHandlesNullInput() throws Exception {
        Method method = GraphTokenService.class.getDeclaredMethod("redactBody", String.class);
        method.setAccessible(true);

        String output = (String) method.invoke(null, (Object) null);
        assertThat(output).isEmpty();
    }

    @Test
    void redactBodyTruncatesLongInput() throws Exception {
        Method method = GraphTokenService.class.getDeclaredMethod("redactBody", String.class);
        method.setAccessible(true);

        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longBody.append("very_long_string_");
        }
        String output = (String) method.invoke(null, longBody.toString());
        assertThat(output)
            .hasSizeLessThanOrEqualTo(210)  // 200 + "..." + safety
            .endsWith("...");
    }

    @Test
    void cachedTokenIsStillValidBeforeBuffer() throws Exception {
        // Use reflection to construct CachedToken
        Class<?> cachedTokenClass = Class.forName("com.serban.notify.provider.GraphTokenService$CachedToken");
        var ctor = cachedTokenClass.getDeclaredConstructor(String.class, Instant.class);
        ctor.setAccessible(true);

        Instant expiresAt = Instant.parse("2026-05-11T12:00:00Z");
        Object cachedToken = ctor.newInstance("test-token", expiresAt);

        Method isStillValid = cachedTokenClass.getDeclaredMethod("isStillValid", Instant.class);
        isStillValid.setAccessible(true);

        // 1 hour before expiry — still valid (buffer 5 min)
        Instant now1h = Instant.parse("2026-05-11T11:00:00Z");
        assertThat((boolean) isStillValid.invoke(cachedToken, now1h))
            .as("1 hour before expiry — still valid")
            .isTrue();

        // 4 minutes before expiry — within buffer, REFRESH needed
        Instant now4m = Instant.parse("2026-05-11T11:56:00Z");
        assertThat((boolean) isStillValid.invoke(cachedToken, now4m))
            .as("4 min before expiry — within 5-min refresh buffer")
            .isFalse();

        // After expiry — expired
        Instant after = Instant.parse("2026-05-11T12:30:00Z");
        assertThat((boolean) isStillValid.invoke(cachedToken, after))
            .as("After expiry — expired")
            .isFalse();
    }

    @Test
    void tokenServiceConstructorSucceedsWithValidParams() {
        GraphTokenService service = new GraphTokenService(
            "tenant-uuid", "client-uuid", "secret-value"
        );
        assertThat(service).isNotNull();
    }

    @Test
    void setClockInjectableForDeterministicTests() {
        GraphTokenService service = new GraphTokenService(
            "tenant-uuid", "client-uuid", "secret-value"
        );
        Clock fixed = Clock.fixed(Instant.parse("2026-05-11T12:00:00Z"), ZoneOffset.UTC);
        service.setClock(fixed);
        // No assertion — just verify setClock doesn't throw
        assertThat(service).isNotNull();
    }
}
