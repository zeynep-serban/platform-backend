package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPreference;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
