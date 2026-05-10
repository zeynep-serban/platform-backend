package com.serban.notify.unsubscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * UnsubscribeTokenService — HMAC-SHA256 signed token for email unsubscribe links
 * (T1.1.8, Faz 23.2.A acceptance gate).
 *
 * <p>**Token format** (compact, JWT-like but no external library):
 * <pre>
 *   base64url(payload).base64url(hmac_sha256(payload, secret))
 * </pre>
 *
 * <p>Payload JSON:
 * <pre>{@code
 * {
 *   "sub": "subscriber_id",
 *   "top": "topic_key",
 *   "iat": 1715347200,
 *   "exp": 1722777600
 * }
 * }</pre>
 *
 * <p>**Why HMAC instead of full JWT**: notification-orchestrator does not yet
 * depend on jjwt/Nimbus libraries (Spring Security only); HMAC-SHA256 is
 * sufficient for unsubscribe link integrity (one-way claim verification, no
 * downstream RFC 7519 dependency).
 *
 * <p>**Secret rotation**: signing secret loaded from
 * {@code notify.unsubscribe.signing-secret} property (Vault path
 * {@code kv/platform/notification-orchestrator/unsubscribe_signing_secret}).
 * On rotation, in-flight tokens become invalid (subscribers must re-receive
 * email with new link).
 *
 * <p>**Default validity**: 90 days (configurable via
 * {@code notify.unsubscribe.token-ttl-days}).
 */
@Service
public class UnsubscribeTokenService {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeTokenService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] signingKey;
    private final long ttlSeconds;
    private Clock clock = Clock.systemUTC();

    public UnsubscribeTokenService(
        @Value("${notify.unsubscribe.signing-secret:dev-only-unsubscribe-secret-not-for-production}")
            String signingSecret,
        @Value("${notify.unsubscribe.token-ttl-days:90}") long ttlDays
    ) {
        this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlDays * 86400L;
        log.info("UnsubscribeTokenService initialized: ttl={}d", ttlDays);
    }

    /**
     * Test-only Clock setter for deterministic expiry verification.
     */
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    /**
     * Generate signed unsubscribe token.
     *
     * @param subscriberId subscriber subject (sub claim)
     * @param topicKey     topic key (top claim) or null for global unsubscribe
     * @return compact `base64url(payload).base64url(hmac)` token
     */
    public String generate(String subscriberId, String topicKey) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subscriberId);
        if (topicKey != null) {
            claims.put("top", topicKey);
        }
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        try {
            byte[] payload = MAPPER.writeValueAsBytes(claims);
            String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload);
            String signature = sign(payloadEncoded);
            return payloadEncoded + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Token generation failed", e);
        }
    }

    /**
     * Verify token integrity + expiry.
     *
     * @return decoded claims if valid; empty if signature mismatch, expired, or malformed
     */
    public Optional<UnsubscribeClaims> verify(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            log.debug("unsubscribe verify: malformed structure (parts={})", parts.length);
            return Optional.empty();
        }
        String payloadEncoded = parts[0];
        String signature = parts[1];

        // Constant-time signature comparison
        String expected = sign(payloadEncoded);
        if (!constantTimeEquals(expected, signature)) {
            log.debug("unsubscribe verify: signature mismatch");
            return Optional.empty();
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(payloadEncoded);
            Map<String, Object> claims = MAPPER.readValue(payload, Map.class);
            long exp = ((Number) claims.get("exp")).longValue();
            long now = clock.instant().getEpochSecond();
            if (now >= exp) {
                log.debug("unsubscribe verify: expired (now={} exp={})", now, exp);
                return Optional.empty();
            }
            return Optional.of(new UnsubscribeClaims(
                (String) claims.get("sub"),
                (String) claims.get("top"),
                ((Number) claims.get("iat")).longValue(),
                exp
            ));
        } catch (Exception e) {
            log.debug("unsubscribe verify: payload decode error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * HMAC-SHA256 signature (base64url no-padding).
     */
    private String sign(String payloadEncoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] hmac = mac.doFinal(payloadEncoded.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC sign failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Decoded unsubscribe claims (immutable).
     */
    public record UnsubscribeClaims(
        String subscriberId,
        String topicKey,
        long issuedAt,
        long expiresAt
    ) {}
}
