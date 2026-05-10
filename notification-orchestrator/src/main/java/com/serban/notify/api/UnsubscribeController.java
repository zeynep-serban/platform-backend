package com.serban.notify.api;

import com.serban.notify.unsubscribe.UnsubscribeRevokeService;
import com.serban.notify.unsubscribe.UnsubscribeRevokeService.RevokeResult;
import com.serban.notify.unsubscribe.UnsubscribeTokenService;
import com.serban.notify.unsubscribe.UnsubscribeTokenService.UnsubscribeClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * UnsubscribeController — public endpoint for email-link unsubscribe
 * (T1.1.8 Faz 23.2.A acceptance gate).
 *
 * <p>**Endpoint contract**:
 * <pre>
 * GET /api/v1/notify/unsubscribe?token=<signed-jwt-like>
 *   200 OK    → { subscriberId, topicKey, status: "verified" }  (PR-A scope)
 *   401       → { error: "invalid_token" }
 * </pre>
 *
 * <p>**PR-A scope**: token verify only (logging + audit). PR-C extends with
 * actual preference revoke (preference.enabled=false for (orgId, subscriberId,
 * topicKey, channel=email)).
 *
 * <p>**Public, unauthenticated**: link is delivered via email; HMAC signature
 * is the auth boundary. Spring Security must allow this path (config update
 * separate PR-A.1 if needed).
 */
@RestController
@RequestMapping("/api/v1/notify/unsubscribe")
public class UnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeController.class);

    private final UnsubscribeTokenService tokenService;
    private final UnsubscribeRevokeService revokeService;

    public UnsubscribeController(
        UnsubscribeTokenService tokenService,
        UnsubscribeRevokeService revokeService
    ) {
        this.tokenService = tokenService;
        this.revokeService = revokeService;
    }

    /**
     * Verify unsubscribe token + log click event.
     *
     * <p>PR-A: returns claims on success (no DB mutation yet); PR-C extends
     * with preference revoke.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> unsubscribe(
        @RequestParam("token") String token
    ) {
        Optional<UnsubscribeClaims> claims = tokenService.verify(token);
        if (claims.isEmpty()) {
            log.info("unsubscribe: invalid_token (token_prefix={})",
                token != null && token.length() > 12 ? token.substring(0, 12) + "..." : "<short>");
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "invalid_token");
            err.put("message", "Token signature mismatch, expired, or malformed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }

        UnsubscribeClaims c = claims.get();
        log.info("unsubscribe verify: orgId={} subscriberId={} topicKey={} expiresAt={}",
            c.orgId(), c.subscriberId(),
            c.topicKey() != null ? c.topicKey() : "<global>", c.expiresAt());

        // T1.1.8 PR-C: apply preference revoke (idempotent)
        RevokeResult result = revokeService.revoke(c);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgId", result.orgId());
        body.put("subscriberId", result.subscriberId());
        body.put("topicKey", result.topicKey());
        body.put("channel", result.channel());
        body.put("status", "unsubscribed");
        body.put("preferenceId", result.preferenceId());
        return ResponseEntity.ok(body);
    }
}
