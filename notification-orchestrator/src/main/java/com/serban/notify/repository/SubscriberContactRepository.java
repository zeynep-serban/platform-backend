package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * SubscriberContact repository — (org_id, subscriber_id) lookup.
 */
@Repository
public interface SubscriberContactRepository extends JpaRepository<SubscriberContact, Long> {

    Optional<SubscriberContact> findByOrgIdAndSubscriberId(String orgId, String subscriberId);
}
