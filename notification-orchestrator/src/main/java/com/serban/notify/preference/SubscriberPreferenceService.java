package com.serban.notify.preference;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberContact;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.SubscriberContactRepository;
import com.serban.notify.repository.SubscriberPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
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
    private final AuditEventPublisher auditPublisher;
    private final PiiRedactor piiRedactor;
    /**
     * Quiet hours enforcement clock (T1.1.6 Faz 23.2.A acceptance gate).
     * Default {@link Clock#systemDefaultZone()}; tests override via
     * {@link #setClock(Clock)} to deterministic instants.
     */
    private Clock clock = Clock.systemDefaultZone();

    public SubscriberPreferenceService(
        SubscriberContactRepository contactRepo,
        SubscriberPreferenceRepository preferenceRepo,
        AuditEventPublisher auditPublisher,
        PiiRedactor piiRedactor
    ) {
        this.contactRepo = contactRepo;
        this.preferenceRepo = preferenceRepo;
        this.auditPublisher = auditPublisher;
        this.piiRedactor = piiRedactor;
    }

    /**
     * Test-only setter for deterministic quiet hours timing (T1.1.6).
     * Production runtime uses default systemDefaultZone clock.
     */
    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
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
     *   <li>Both-null wildcard (topic_key=NULL AND channel=NULL) → use
     *       (Faz 23.5 PR2 absorb: matches the subscriber-facing
     *       "mute all topics & channels" rule).</li>
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
        // 4) both-null wildcard ("mute all topics & channels") — Faz 23.5
        // PR2 absorb: the subscriber-facing upsert can write this row, so
        // the dispatch path must also honor it. Without this fallback a
        // UI "mute everything" toggle would silently leak through as
        // no_preference_set → ALLOW.
        if (pref.isEmpty()) {
            pref = preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannelIsNull(
                intent.getOrgId(), subscriberId
            );
        }
        // 5) no preference set → default ALLOW
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

        // T1.1.6 quiet hours enforcement (Faz 23.2.A acceptance gate).
        // JSON contract: {"start":"22:00","end":"07:00","tz":"Europe/Istanbul"}.
        // Critical bypass also applies for quiet hours (severity=critical +
        // bypassForCritical=true → ALLOW even within quiet window).
        if (p.getQuietHours() != null && !p.getQuietHours().isEmpty()
            && isInQuietHoursWindow(p.getQuietHours())) {
            if (intent.getSeverity() == NotificationIntent.Severity.critical
                && p.isBypassForCritical()) {
                log.debug("quiet_hours deny BYPASSED (critical severity): subscriberId={} channel={} topic={}",
                    subscriberId, channel, intent.getTopicKey());
                return PreferenceDecision.allow("critical_bypass_quiet_hours");
            }
            log.debug("quiet_hours suppression: subscriberId={} channel={} topic={} window={}",
                subscriberId, channel, intent.getTopicKey(), p.getQuietHours());
            return PreferenceDecision.deny("quiet_hours");
        }

        return PreferenceDecision.allow("preference_enabled");
    }

    /**
     * Quiet hours window check (T1.1.6).
     *
     * <p>JSON contract:
     * <pre>{@code
     * {
     *   "start": "22:00",         // LocalTime HH:mm
     *   "end":   "07:00",
     *   "tz":    "Europe/Istanbul" // optional, defaults to UTC
     * }
     * }</pre>
     *
     * <p>Cross-day windows (start > end, e.g., 22:00→07:00) span midnight.
     *
     * <p>Invalid config (parse error, missing fields) → fail-open (no
     * suppression) with WARN log, since a misconfigured preference shouldn't
     * silently block all delivery.
     *
     * @return true if current time is within quiet hours window (suppress)
     */
    private boolean isInQuietHoursWindow(Map<String, Object> quietHours) {
        Object startObj = quietHours.get("start");
        Object endObj = quietHours.get("end");
        Object tzObj = quietHours.getOrDefault("tz", "UTC");

        if (!(startObj instanceof String) || !(endObj instanceof String)) {
            log.warn("quiet_hours invalid config (missing start/end): {}", quietHours);
            return false;
        }
        String start = (String) startObj;
        String end = (String) endObj;
        String tz = tzObj instanceof String ? (String) tzObj : "UTC";

        try {
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            ZoneId zone = ZoneId.of(tz);
            LocalTime now = ZonedDateTime.now(clock.withZone(zone)).toLocalTime();

            if (startTime.equals(endTime)) {
                return false;  // empty window
            }
            if (startTime.isBefore(endTime)) {
                // Same-day window (e.g., 09:00-17:00)
                return !now.isBefore(startTime) && now.isBefore(endTime);
            } else {
                // Cross-day window (e.g., 22:00-07:00 spans midnight)
                return !now.isBefore(startTime) || now.isBefore(endTime);
            }
        } catch (Exception e) {
            log.warn("quiet_hours parse error (start={} end={} tz={}): {} — fail-open",
                start, end, tz, e.getMessage());
            return false;
        }
    }

    /**
     * Preference decision (immutable).
     */
    public record PreferenceDecision(boolean allowed, String reason) {
        public static PreferenceDecision allow(String reason) { return new PreferenceDecision(true, reason); }
        public static PreferenceDecision deny(String reason) { return new PreferenceDecision(false, reason); }
    }

    // ─── Faz 23.5 PR2: subscriber-facing CRUD ───────────────────────────

    /**
     * List every preference row owned by the (org, subscriber) pair.
     * Newest-first by {@code updated_at} so the UI shows the most
     * recently-touched rule at the top.
     */
    public java.util.List<SubscriberPreference> listForSubscriber(String orgId, String subscriberId) {
        java.util.List<SubscriberPreference> rows =
            preferenceRepo.findBySubscriberIdAndOrgId(subscriberId, orgId);
        rows.sort(java.util.Comparator
            .comparing(
                SubscriberPreference::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())
            )
            .reversed());
        return rows;
    }

    /**
     * Upsert a preference row keyed by (orgId, subscriberId, topicKey,
     * channel). If a row exists for the tuple it is updated in place;
     * otherwise a new row is inserted. Returns the post-mutation row.
     *
     * <p>Uses the V5 unique index {@code (org_id, subscriber_id,
     * topic_key, channel)} to prevent duplicates — the lookup uses the
     * same composite key. Wildcard rows ({@code topic_key=null} or
     * {@code channel=null}) are honored: a {@code null} input is
     * treated as "wildcard" and matches the corresponding NULL row.
     *
     * <p>Concurrent upserts: the V5 unique index
     * {@code (org_id, subscriber_id, topic_key, channel)} guarantees
     * at most one row per tuple. Two clients racing on the same
     * tuple: the loser's INSERT raises
     * {@link org.springframework.dao.DataIntegrityViolationException}
     * which propagates to the caller as a 500 — the next PUT for that
     * tuple will see the existing row via the UPDATE branch. Explicit
     * retry-by-update is intentionally NOT implemented in v1; in
     * practice the overlap window is sub-second and the caller can
     * retry the same payload safely. Tracked as a Faz 23.5 hardening
     * follow-up if observed in production traffic.
     */
    @org.springframework.transaction.annotation.Transactional
    public SubscriberPreference upsert(
        String orgId,
        String subscriberId,
        String topicKey,
        String channel,
        boolean enabled,
        java.util.Map<String, Object> quietHours,
        Integer frequencyLimitPerDay,
        Boolean bypassForCritical
    ) {
        if (orgId == null || orgId.isBlank()) {
            throw new com.serban.notify.exception.InvalidRequestException("orgId required");
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new com.serban.notify.exception.InvalidRequestException("subscriberId required");
        }

        Optional<SubscriberPreference> existing =
            findExistingForTuple(orgId, subscriberId, topicKey, channel);

        SubscriberPreference target = existing.orElseGet(SubscriberPreference::new);
        if (target.getId() == null) {
            target.setOrgId(orgId);
            target.setSubscriberId(subscriberId);
            target.setTopicKey(emptyToNull(topicKey));
            target.setChannel(emptyToNull(channel));
        }
        target.setEnabled(enabled);
        target.setQuietHours(quietHours);
        target.setFrequencyLimitPerDay(frequencyLimitPerDay);
        // Default true to match the entity default; explicit null means
        // "leave unchanged on update, default true on insert".
        target.setBypassForCritical(
            bypassForCritical == null
                ? existing.map(SubscriberPreference::isBypassForCritical).orElse(true)
                : bypassForCritical
        );

        SubscriberPreference saved = preferenceRepo.save(target);
        log.info("preference upsert: orgId={} subscriberId={} topic={} channel={} enabled={} id={}",
            orgId, subscriberId, target.getTopicKey(), target.getChannel(), enabled, saved.getId());
        return saved;
    }

    /**
     * Delete a preference row by id, scoped to the (org, subscriber)
     * pair. Returns {@code true} when the row was removed; {@code false}
     * when no matching row was found (id missing or cross-tenant) —
     * same 404-ish semantics as the inbox controller's archive path.
     */
    /**
     * Atomic restore-defaults: hard-delete every preference row owned by
     * the caller (Faz 23.6 PR-A1, Codex thread {@code 019e0376}).
     *
     * <p>Once the rows are gone the dispatch-time resolver falls through
     * to the default-allow behaviour (ADR-0013 D46 #8) — the caller is
     * effectively reset to the platform default.
     *
     * <p>An append-only {@code PREFERENCE_RESTORE_DEFAULTS} audit event is
     * published on every invocation, including {@code deleted_count=0},
     * so operator commands stay observable even when no rows changed.
     *
     * @return number of rows removed (0 when the caller had no rules)
     */
    @org.springframework.transaction.annotation.Transactional
    public int restoreDefaults(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank() || subscriberId == null || subscriberId.isBlank()) {
            return 0;
        }
        int deleted = preferenceRepo.deleteAllByOrgIdAndSubscriberId(orgId, subscriberId);
        String recipientHash = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        auditPublisher.publishStandalone(
            "PREFERENCE_RESTORE_DEFAULTS",
            orgId,
            recipientHash,
            Map.of(
                "subscriber_id", subscriberId,
                "deleted_count", deleted
            )
        );
        log.info("preference restore-defaults: orgId={} subscriberId={} deleted={}",
            orgId, subscriberId, deleted);
        return deleted;
    }

    /**
     * Atomic channel-mute (Faz 23.6 PR-A2 — Codex thread {@code 019e0387}
     * `N` decision).
     *
     * <p>Writes a channel-wildcard deny preference
     * ({@code topic_key IS NULL, channel=:channel, enabled=false,
     * bypassForCritical=true}) and atomically removes every same-channel
     * exact override so the wildcard actually wins the dispatch
     * resolver precedence (resolver order: exact &gt; channel-wildcard
     * &gt; topic-wildcard &gt; both-null).
     *
     * <p>Critical bypass stays ON by default — operators muting a
     * channel almost never want to also lose security/critical
     * notifications; an explicit per-row edit can flip
     * {@code bypassForCritical} later.
     *
     * <p>An append-only {@code PREFERENCE_MUTE_CHANNEL} audit event is
     * published with the deleted-override count so the operator action
     * is observable in the audit table.
     *
     * @return number of exact override rows removed (0 when the caller
     *         had no overrides for this channel)
     */
    /**
     * Result of a {@link #muteChannel(String, String, String)} call.
     *
     * <p>Codex thread {@code 019e0387} P1 absorb: the response contract
     * exposes both numbers because each side answers a different
     * operator question:
     * <ul>
     *   <li>{@code deletedOverrideCount} — how many same-channel exact
     *       rules the action removed (e.g. existing
     *       {@code (auth.password-reset, email, enabled=true)}).</li>
     *   <li>{@code shadowDenyCount} — how many topic-wide allow rules
     *       the action shadowed with a fresh channel-specific exact
     *       deny so the resolver actually mutes the channel.</li>
     * </ul>
     */
    public record MuteChannelResult(int deletedOverrideCount, int shadowDenyCount) {}

    @org.springframework.transaction.annotation.Transactional
    public MuteChannelResult muteChannel(String orgId, String subscriberId, String channel) {
        if (orgId == null || orgId.isBlank()
            || subscriberId == null || subscriberId.isBlank()
            || channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("orgId, subscriberId, and channel are required");
        }
        // 1. Upsert the channel-wildcard deny rule.
        SubscriberPreference wildcard = upsert(
            orgId, subscriberId, /* topicKey */ null, channel,
            /* enabled */ false, /* quietHours */ null,
            /* frequencyLimitPerDay */ null, /* bypassForCritical */ true
        );
        // 2. Drop same-channel exact overrides so the wildcard fires
        //    for topics that have no other rule.
        int deletedOverrides = preferenceRepo.deleteSameChannelExactOverrides(
            orgId, subscriberId, channel
        );
        // 3. Codex thread `019e0387` P1 absorb: shadow each topic-wide
        //    allow row with a channel-specific exact deny so the
        //    resolver (exact > topic-wildcard > channel-wildcard) sees
        //    deny first when dispatching this channel. The topic-wide
        //    allow itself is preserved so other channels keep working.
        java.util.List<SubscriberPreference> topicWideAllows =
            preferenceRepo.findTopicWideAllowRows(orgId, subscriberId);
        int shadowDenyCount = 0;
        for (SubscriberPreference row : topicWideAllows) {
            upsert(
                orgId, subscriberId, row.getTopicKey(), channel,
                /* enabled */ false, /* quietHours */ null,
                /* frequencyLimitPerDay */ null, /* bypassForCritical */ true
            );
            shadowDenyCount += 1;
        }
        // 4. Audit the operator command — both counts surface in
        //    audit_event.details so PREFERENCE_MUTE_CHANNEL replays the
        //    full mutation shape.
        String recipientHash = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        auditPublisher.publishStandalone(
            "PREFERENCE_MUTE_CHANNEL",
            orgId,
            recipientHash,
            Map.of(
                "subscriber_id", subscriberId,
                "channel", channel,
                "deleted_override_count", deletedOverrides,
                "shadow_deny_count", shadowDenyCount
            )
        );
        log.info("preference mute-channel: orgId={} subscriberId={} channel={} wildcardId={} deletedOverrides={} shadowDenies={}",
            orgId, subscriberId, channel, wildcard.getId(), deletedOverrides, shadowDenyCount);
        return new MuteChannelResult(deletedOverrides, shadowDenyCount);
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean delete(String orgId, String subscriberId, Long id) {
        if (orgId == null || orgId.isBlank() || subscriberId == null || subscriberId.isBlank()
            || id == null) {
            return false;
        }
        Optional<SubscriberPreference> existing = preferenceRepo.findById(id)
            .filter(p -> orgId.equals(p.getOrgId()))
            .filter(p -> subscriberId.equals(p.getSubscriberId()));
        if (existing.isEmpty()) return false;
        preferenceRepo.deleteById(id);
        log.info("preference delete: orgId={} subscriberId={} id={}", orgId, subscriberId, id);
        return true;
    }

    private Optional<SubscriberPreference> findExistingForTuple(
        String orgId, String subscriberId, String topicKey, String channel
    ) {
        String tk = emptyToNull(topicKey);
        String ch = emptyToNull(channel);
        // The repository's null-safe variants narrow the WHERE clause
        // for each combination of wildcard NULLs; we pick the right one.
        if (tk != null && ch != null) {
            return preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
                orgId, subscriberId, tk, ch);
        }
        if (tk != null && ch == null) {
            return preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
                orgId, subscriberId, tk);
        }
        if (tk == null && ch != null) {
            return preferenceRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
                orgId, subscriberId, ch);
        }
        // Both null → "all topics, all channels" wildcard row. The repo
        // doesn't expose a dedicated lookup for this case today; fall
        // back to the list query and filter — there can be at most one
        // such row per (org, subscriber) thanks to the V5 unique index.
        return preferenceRepo.findBySubscriberIdAndOrgId(subscriberId, orgId).stream()
            .filter(p -> p.getTopicKey() == null && p.getChannel() == null)
            .findFirst();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
