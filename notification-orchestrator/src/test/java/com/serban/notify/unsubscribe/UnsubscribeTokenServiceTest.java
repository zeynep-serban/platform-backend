package com.serban.notify.unsubscribe;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UnsubscribeTokenService unit tests (T1.1.8 Faz 23.2.A acceptance gate).
 */
class UnsubscribeTokenServiceTest {

    private final UnsubscribeTokenService service = new UnsubscribeTokenService(
        "test-signing-secret-fixed-for-deterministic-tests", 90
    );

    @Test
    void generateAndVerifyHappyPath() {
        String token = service.generate("subscriber-1204", "auth.password-reset");

        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = service.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().subscriberId()).isEqualTo("subscriber-1204");
        assertThat(verified.get().topicKey()).isEqualTo("auth.password-reset");
        assertThat(verified.get().expiresAt()).isGreaterThan(verified.get().issuedAt());
    }

    @Test
    void generateGlobalUnsubscribeNullTopic() {
        String token = service.generate("subscriber-1204", null);

        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = service.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().subscriberId()).isEqualTo("subscriber-1204");
        assertThat(verified.get().topicKey()).isNull();
    }

    @Test
    void verifyExpiredTokenRejects() {
        // Generate token at t0 with 90 day TTL
        UnsubscribeTokenService svcAtT0 = new UnsubscribeTokenService(
            "test-signing-secret-fixed-for-deterministic-tests", 90
        );
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        svcAtT0.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        String token = svcAtT0.generate("subscriber-1204", "auth.password-reset");

        // Roll forward 91 days — should be expired
        UnsubscribeTokenService svcAtT1 = new UnsubscribeTokenService(
            "test-signing-secret-fixed-for-deterministic-tests", 90
        );
        Instant t1 = t0.plusSeconds(91 * 86400L);
        svcAtT1.setClock(Clock.fixed(t1, ZoneOffset.UTC));

        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = svcAtT1.verify(token);
        assertThat(verified).as("expired token rejected").isEmpty();
    }

    @Test
    void verifyTamperedTokenRejects() {
        String token = service.generate("subscriber-1204", "auth.password-reset");

        // Tamper signature (last char flip)
        String tampered = token.substring(0, token.length() - 1)
            + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = service.verify(tampered);
        assertThat(verified).as("tampered signature rejected").isEmpty();
    }

    @Test
    void verifyMalformedTokenRejects() {
        assertThat(service.verify(null)).as("null").isEmpty();
        assertThat(service.verify("")).as("empty").isEmpty();
        assertThat(service.verify("no-dot-separator")).as("no dot").isEmpty();
        assertThat(service.verify("a.b.c")).as("3-part not 2").isEmpty();
        assertThat(service.verify("invalid-base64.invalid-base64")).as("invalid base64").isEmpty();
    }

    @Test
    void verifyDifferentSecretRejects() {
        String token = service.generate("subscriber-1204", "auth.password-reset");

        // Different secret → signature mismatch
        UnsubscribeTokenService altSvc = new UnsubscribeTokenService(
            "different-secret-totally-different", 90
        );
        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = altSvc.verify(token);
        assertThat(verified).as("token signed with different secret rejected").isEmpty();
    }

    @Test
    void claimsIncludeIatAndExp() {
        Instant t0 = Instant.parse("2026-05-10T12:00:00Z");
        service.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        String token = service.generate("subscriber-1204", "billing.invoice");
        Optional<UnsubscribeTokenService.UnsubscribeClaims> verified = service.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().issuedAt()).isEqualTo(t0.getEpochSecond());
        assertThat(verified.get().expiresAt()).isEqualTo(t0.getEpochSecond() + 90 * 86400L);
    }
}
