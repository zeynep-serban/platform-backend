package com.serban.notify.preference;

import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberContact;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.repository.SubscriberContactRepository;
import com.serban.notify.repository.SubscriberPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * SubscriberPreferenceService — preference + contact lookups (Faz 23.1 PR5).
 *
 * <p>Codex 019dfaaa PR5 absorb:
 * <ul>
 *   <li>Q2: contact + preference ayrı tablolar (read-model)</li>
 *   <li>Lock-in #4: contact lookup planning enrichment (guard değil) —
 *       subscriber recipient → email/phone enrichment plan-time'da</li>
 *   <li>Critical bypass: severity=critical intent için preference deny
 *       override edilir</li>
 * </ul>
 *
 * <p>Pipeline rolleri:
 * <ol>
 *   <li>Plan enrichment: subscriber recipient için contact lookup → email/phone</li>
 *   <li>Eligibility guard: preference allow/deny check (DeliveryEligibilityService)</li>
 * </ol>
 */
@Service
public class SubscriberPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(SubscriberPreferenceService.class);

    private final SubscriberContactRepository contactRepo;
    private final SubscriberPreferenceRepository preferenceRepo;

    public SubscriberPreferenceService(
        SubscriberContactRepository contactRepo,
        SubscriberPreferenceRepository preferenceRepo
    ) {
        this.contactRepo = contactRepo;
        this.preferenceRepo = preferenceRepo;
    }

    /**
     * Lookup contact for subscriber (planning enrichment).
     *
     * @param orgId org boundary
     * @param subscriberId subscriber id
     * @return contact if found, else Optional.empty
     */
    public Optional<SubscriberContact> findContact(String orgId, String subscriberId) {
        return contactRepo.findByOrgIdAndSubscriberId(orgId, subscriberId);
    }

    /**
     * Resolve preference allow/deny for a (subscriber, channel, topic) tuple.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Exact match (orgId, subscriberId, topic_key, channel) → use</li>
     *   <li>Channel-wildcard (orgId, subscriberId, topic_key, channel=NULL) → use</li>
     *   <li>Topic-wildcard (orgId, subscriberId, topic_key=NULL, channel) → use</li>
     *   <li>No match → ALLOW (default-allow per ADR-0013 D46 #8)</li>
     * </ol>
     *
     * <p>Critical bypass: intent.severity=critical AND preference.bypassForCritical=true
     * → ALLOW regardless of preference.enabled.
     *
     * @return PreferenceDecision with allowed + reason
     */
    public PreferenceDecision evaluate(
        NotificationIntent intent, String channel, String subscriberId
    ) {
        // 1) exact match
        Optional<SubscriberPreference> pref = preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            intent.getOrgId(), subscriberId, intent.getTopicKey(), channel
        );
        // 2) channel wildcard
        if (pref.isEmpty()) {
            pref = preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
                intent.getOrgId(), subscriberId, intent.getTopicKey()
            );
        }
        // 3) topic wildcard
        if (pref.isEmpty()) {
            pref = preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
                intent.getOrgId(), subscriberId, channel
            );
        }
        // 4) no preference set → default ALLOW
        if (pref.isEmpty()) {
            return PreferenceDecision.allow("no_preference_set");
        }

        SubscriberPreference p = pref.get();

        // Critical bypass: severity=critical + preference.bypass_for_critical=true → ALLOW
        if (!p.isEnabled() && intent.getSeverity() == NotificationIntent.Severity.critical
            && p.isBypassForCritical()) {
            log.debug("preference deny BYPASSED (critical severity): subscriberId={} channel={} topic={}",
                subscriberId, channel, intent.getTopicKey());
            return PreferenceDecision.allow("critical_bypass");
        }

        if (!p.isEnabled()) {
            return PreferenceDecision.deny("preference_disabled");
        }

        return PreferenceDecision.allow("preference_enabled");
    }

    /**
     * Preference decision (immutable).
     */
    public record PreferenceDecision(boolean allowed, String reason) {
        public static PreferenceDecision allow(String reason) { return new PreferenceDecision(true, reason); }
        public static PreferenceDecision deny(String reason) { return new PreferenceDecision(false, reason); }
    }
}
