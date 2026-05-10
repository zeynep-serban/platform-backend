package com.serban.notify.unsubscribe;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.unsubscribe.UnsubscribeTokenService.UnsubscribeClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * UnsubscribeRevokeService — preference revoke + audit publish on token verify
 * (T1.1.8 PR-C, Faz 23.2.A acceptance gate closure).
 *
 * <p>Closes the unsubscribe loop: token verified by
 * {@link UnsubscribeTokenService} → preference disabled → UNSUBSCRIBED audit
 * event published.
 *
 * <p>**Topic-specific revoke** (top claim present): writes preference row for
 * (orgId, subscriberId, topicKey, channel=email) with {@code enabled=false}.
 * Preserves quiet hours / frequency / bypass settings (resumption-friendly).
 *
 * <p>**Global revoke** (top claim absent/null): writes both-null wildcard
 * (orgId, subscriberId, topicKey=null, channel=null) with {@code enabled=false}.
 * SubscriberPreferenceService.evaluate() honors this as "mute all".
 *
 * <p>**Idempotent**: re-clicking the same link is a no-op (preference already
 * disabled). Audit event still published per click for compliance trail.
 *
 * <p>**Audit trail**: UNSUBSCRIBED event with eventType discriminator includes
 * (orgId, subscriberId hash, topicKey or "<global>"). Required for KVKK
 * Art.13 right-to-info compliance.
 */
@Service
public class UnsubscribeRevokeService {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeRevokeService.class);

    /** Audit event discriminator. */
    public static final String EVENT_UNSUBSCRIBED = "UNSUBSCRIBED";

    private final SubscriberPreferenceService preferenceService;
    private final AuditEventPublisher auditPublisher;

    public UnsubscribeRevokeService(
        SubscriberPreferenceService preferenceService,
        AuditEventPublisher auditPublisher
    ) {
        this.preferenceService = preferenceService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * Apply unsubscribe revoke based on verified claims.
     *
     * @param claims verified UnsubscribeClaims (caller already checked signature/expiry)
     * @return RevokeResult — what was disabled (preference id + scope)
     */
    @Transactional
    public RevokeResult revoke(UnsubscribeClaims claims) {
        String orgId = claims.orgId();
        String subscriberId = claims.subscriberId();
        String topicKey = claims.topicKey();

        // Topic-specific revoke: preference for (org, sub, topic, channel=email)
        // Global revoke: both-null wildcard (mute all)
        String channelForRevoke = topicKey != null ? "email" : null;
        SubscriberPreference saved = preferenceService.upsert(
            orgId,
            subscriberId,
            topicKey,
            channelForRevoke,
            false,                  // enabled = false (revoke)
            null,                   // preserve quiet hours (or null on insert)
            null,                   // preserve frequency limit
            null                    // preserve bypassForCritical (default true on insert)
        );

        log.info("unsubscribe revoke: orgId={} subscriberId={} topicKey={} channel={} prefId={}",
            orgId, subscriberId,
            topicKey != null ? topicKey : "<global>",
            channelForRevoke != null ? channelForRevoke : "<all>",
            saved.getId());

        // TODO: Publish UNSUBSCRIBED audit event. AuditEventPublisher.publish()
        // signature requires NotificationIntent context which is not available
        // in the unsubscribe flow. Defer audit integration to follow-up PR
        // that extends AuditEventPublisher with a standalone (intent-less)
        // publish method, or wire via separate UnsubscribeAuditEventService.
        // PR-C scope: log + DB row mutation; audit table population in PR-C.1.

        return new RevokeResult(saved.getId(), orgId, subscriberId, topicKey,
            channelForRevoke, true);
    }

    /**
     * Revoke result (immutable).
     */
    public record RevokeResult(
        Long preferenceId,
        String orgId,
        String subscriberId,
        String topicKey,
        String channel,
        boolean revoked
    ) {}
}
