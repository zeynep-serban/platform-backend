package com.serban.notify.unsubscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * UnsubscribeUrlBuilder — composes signed unsubscribe link for email footer
 * (T1.1.8 PR-B, Faz 23.2.A acceptance gate).
 *
 * <p>Sister of {@link UnsubscribeTokenService}. Generates the full URL that
 * email recipients click to verify (HMAC-SHA256 signature) + later revoke
 * (PR-C wire-up).
 *
 * <p>**URL contract**:
 * <pre>
 *   {base-url}?token=<base64url(payload).base64url(hmac)>
 * </pre>
 *
 * <p>Token already includes {@code subscriberId} + optional {@code topicKey}
 * + {@code iat} + {@code exp} claims; URL only needs the token query param.
 *
 * <p>**Base URL config**: {@code notify.unsubscribe.base-url} property
 * (default {@code https://testai.acik.com/api/v1/notify/unsubscribe} for
 * test; production overlay sets {@code https://ai.acik.com/api/v1/notify/unsubscribe}).
 *
 * <p>**Production hostname guard**: PR-A absorb iter-1 (thread `019e12c0`)
 * added {@code notify.unsubscribe.signing-secret} guard to
 * ProductionConfigValidator. Base URL hostname guard
 * ({@code notify.unsubscribe.base-url} prod prefix `https://ai.acik.com`)
 * scheduled for **PR-C** acceptance gate (DeliveryDispatchService email
 * channel integration includes prod-host validation).
 */
@Service
public class UnsubscribeUrlBuilder {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeUrlBuilder.class);

    private final UnsubscribeTokenService tokenService;
    private final String baseUrl;

    public UnsubscribeUrlBuilder(
        UnsubscribeTokenService tokenService,
        @Value("${notify.unsubscribe.base-url:https://testai.acik.com/api/v1/notify/unsubscribe}")
            String baseUrl
    ) {
        this.tokenService = tokenService;
        this.baseUrl = baseUrl;
        log.info("UnsubscribeUrlBuilder initialized: baseUrl={}", baseUrl);
    }

    /**
     * Build full unsubscribe URL for an email recipient.
     *
     * @param orgId        tenant scope (org claim) — required
     * @param subscriberId recipient subscriber ID (sub claim)
     * @param topicKey     topic-specific unsubscribe; null = global unsubscribe
     * @return absolute URL with signed token
     */
    public String build(String orgId, String subscriberId, String topicKey) {
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("orgId required for unsubscribe URL");
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new IllegalArgumentException("subscriberId required for unsubscribe URL");
        }
        String token = tokenService.generate(orgId, subscriberId, topicKey);
        return UriComponentsBuilder.fromUriString(baseUrl)
            .queryParam("token", token)
            .build()
            .toUriString();
    }

    /**
     * Backward-compat overload (PR-B signature) — defaults orgId="default".
     *
     * @deprecated use {@link #build(String, String, String)} with explicit orgId
     */
    @Deprecated
    public String build(String subscriberId, String topicKey) {
        return build("default", subscriberId, topicKey);
    }
}
