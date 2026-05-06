package com.serban.notify.repository;

import com.serban.notify.domain.WebhookHmacKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WebhookHmacKeyRepository extends JpaRepository<WebhookHmacKey, Long> {

    Optional<WebhookHmacKey> findByStatus(WebhookHmacKey.Status status);

    Optional<WebhookHmacKey> findByKid(String kid);
}
