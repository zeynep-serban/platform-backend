package com.serban.notify.unsubscribe;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.preference.SubscriberPreferenceService.MuteChannelResult;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.unsubscribe.UnsubscribeTokenService.UnsubscribeClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>**Global revoke** (top claim absent/null): calls
 * {@link SubscriberPreferenceService#muteChannel} with channel="email" —
 * Codex iter-1 (thread `019e12d4`) absorb: muteChannel pattern correctly
 * shadows existing topic-wide allows + deletes exact overrides so resolver
 * (exact > topic-wildcard > channel-wildcard > both-null) reaches a deny
 * first for the email channel. Other channels (push, slack, sms) preserved
 * unless their own unsubscribe link is clicked (per-channel scope).
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
    private final PiiRedactor piiRedactor;

    public UnsubscribeRevokeService(
        SubscriberPreferenceService preferenceService,
        AuditEventPublisher auditPublisher,
        PiiRedactor piiRedactor
    ) {
        this.preferenceService = preferenceService;
        this.auditPublisher = auditPublisher;
        this.piiRedactor = piiRedactor;
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

        Long preferenceId;
        String channelForRevoke;
        Map<String, Object> auditDetails = new LinkedHashMap<>();

        if (topicKey != null) {
            // Topic-specific revoke: exact (org, sub, topic, channel=email) deny.
            // Resolver hits exact match first → preference disabled.
            channelForRevoke = "email";
            SubscriberPreference saved = preferenceService.upsert(
                orgId,
                subscriberId,
                topicKey,
                channelForRevoke,
                false,                  // enabled = false (revoke)
                null,                   // preserve quiet hours
                null,                   // preserve frequency limit
                null                    // preserve bypassForCritical (default true)
            );
            preferenceId = saved.getId();
            // Whitelist-allowed keys only (PiiRedactor.filterAuditDetails)
            auditDetails.put("topic_key", topicKey);
            auditDetails.put("channel", channelForRevoke);
        } else {
            // Global revoke: muteChannel pattern (Codex iter-1 thread `019e12d4`
            // absorb). Delete exact overrides + shadow topic-wide allows with
            // channel-specific deny so resolver (exact > topic-wildcard >
            // channel-wildcard > both-null) reaches a deny first for this
            // channel. Email link → mute email channel; other channels (push,
            // slack) preserved unless user uses those-channel unsubscribe links.
            channelForRevoke = "email";
            MuteChannelResult muteResult = preferenceService.muteChannel(
                orgId, subscriberId, channelForRevoke
            );
            preferenceId = null;  // muteChannel writes multiple rows, no single id
            // Whitelist-allowed keys (deleted_override_count + shadow_deny_count
            // already in PiiRedactor whitelist for PREFERENCE_MUTE_CHANNEL pattern)
            auditDetails.put("channel", channelForRevoke);
            auditDetails.put("deleted_override_count", muteResult.deletedOverrideCount());
            auditDetails.put("shadow_deny_count", muteResult.shadowDenyCount());
        }

        log.info("unsubscribe revoke: orgId={} subscriberId={} topicKey={} channel={} details={}",
            orgId, subscriberId,
            topicKey != null ? topicKey : "<global>",
            channelForRevoke,
            auditDetails);

        // Codex iter-1 (019e12d4) absorb: publishStandalone audit event.
        // Codex 019e4950 P1 #5 absorb (PR-K5): subscriber_id ham YERINE
        // HMAC pseudonymize (subscriber_id_hash). KVKK Madde 12 uyumu.
        String recipientHash = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        auditDetails.put("subscriber_id_hash", recipientHash);
        auditPublisher.publishStandalone(
            EVENT_UNSUBSCRIBED,
            orgId,
            recipientHash,
            auditDetails
        );

        return new RevokeResult(preferenceId, orgId, subscriberId, topicKey,
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
