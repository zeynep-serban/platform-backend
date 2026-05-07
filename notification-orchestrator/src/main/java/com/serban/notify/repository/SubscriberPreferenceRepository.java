package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriberPreferenceRepository extends JpaRepository<SubscriberPreference, Long> {

    List<SubscriberPreference> findBySubscriberIdAndOrgId(String subscriberId, String orgId);

    /**
     * Org-aware preference lookup (Codex 019dfaaa PR5 Q2 absorb).
     *
     * <p>Mevcut V1 schema'da (subscriber_id, topic_key, channel) tek başına
     * unique değildi — aynı subscriber_id farklı org'larda aynı topic/channel
     * için farklı tercih kaydedebilirdi. PR5 V5 migration org-aware unique
     * index ekliyor; bu metot ona uyumlu.
     */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
        String orgId, String subscriberId, String topicKey, String channel
    );

    /** Wildcard channel preference (channel=NULL → all channels). */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
        String orgId, String subscriberId, String topicKey
    );

    /** Wildcard topic preference (topic_key=NULL → all topics). */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
        String orgId, String subscriberId, String channel
    );

    /**
     * Both-null wildcard preference (topic_key IS NULL AND channel IS
     * NULL → "all topics, all channels"). Faz 23.5 PR2 absorb: the
     * subscriber-facing upsert API can write this row, so the dispatch-
     * time evaluation must also read it; otherwise a UI "mute all" rule
     * has no effect on delivery (Codex iter P1).
     */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannelIsNull(
        String orgId, String subscriberId
    );

    /**
     * Atomic restore-defaults: deletes every preference row owned by the
     * caller in a single transaction (Faz 23.6 PR-A1, Codex thread
     * {@code 019e0376}).
     *
     * <p>Backed by the existing {@code (org_id, subscriber_id)} index in
     * {@code V1__init_notify_schema.sql}; the JPQL bulk delete bypasses
     * the persistence context for speed and correctness.
     *
     * @return number of rows removed (0 when the caller had no rules)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM SubscriberPreference p
         WHERE p.orgId = :orgId
           AND p.subscriberId = :subscriberId
        """)
    int deleteAllByOrgIdAndSubscriberId(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Atomic same-channel exact-override delete for the channel-mute
     * action (Faz 23.6 PR-A2 — Codex thread {@code 019e0387} `N`
     * decision).
     *
     * <p>Removes every {@code (org, subscriber, channel)} row whose
     * {@code topic_key} is non-NULL — i.e. exact overrides that would
     * otherwise win the dispatch-resolver precedence over the new
     * channel-wildcard deny rule. The wildcard deny row itself
     * ({@code topic_key IS NULL}) is preserved so the
     * `mute-all-channel` action survives this call.
     *
     * @return number of exact override rows removed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM SubscriberPreference p
         WHERE p.orgId = :orgId
           AND p.subscriberId = :subscriberId
           AND p.channel = :channel
           AND p.topicKey IS NOT NULL
        """)
    int deleteSameChannelExactOverrides(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId,
        @Param("channel") String channel
    );

    /**
     * Topic-wide allow rows that would otherwise shadow a channel-mute
     * action (Faz 23.6 PR-A2 — Codex thread {@code 019e0387} P1
     * absorb).
     *
     * <p>Resolver order is exact &gt; topic-wildcard (channel=NULL) &gt;
     * channel-wildcard (topic=NULL) &gt; both-null. So a row like
     * {@code (topic='auth.password-reset', channel=NULL,
     * enabled=true)} fires before our new
     * {@code (topic=NULL, channel='email', enabled=false)} wildcard
     * deny. We can't delete the topic-wide allow (it carries the
     * operator's intent for sms / slack / webhook), but we can write a
     * channel-specific exact deny per matching topic so the resolver
     * sees deny first when dispatching this channel.
     *
     * @return the topic-wide allow rows the caller currently has
     */
    @Query("""
        SELECT p FROM SubscriberPreference p
         WHERE p.orgId = :orgId
           AND p.subscriberId = :subscriberId
           AND p.topicKey IS NOT NULL
           AND p.channel IS NULL
           AND p.enabled = true
        """)
    List<SubscriberPreference> findTopicWideAllowRows(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );
}
