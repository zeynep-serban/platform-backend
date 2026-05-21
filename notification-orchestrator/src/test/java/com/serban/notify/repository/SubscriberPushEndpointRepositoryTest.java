package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.SubscriberPushEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubscriberPushEndpoint repository IT — Faz 23.7 M7 T4.2 PR-W1.
 *
 * <p>V19 migration + JPA mapping + soft-delete + failure counter coverage.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@org.springframework.transaction.annotation.Transactional
class SubscriberPushEndpointRepositoryTest extends AbstractPostgresTest {

    @Autowired
    SubscriberPushEndpointRepository repo;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;

    @Test
    void savePersistsAllFieldsAndAutoGenerates() {
        SubscriberPushEndpoint entry = fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/AAA");
        SubscriberPushEndpoint saved = repo.save(entry);

        assertThat(saved.getEndpointId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getLastSeenAt()).isNotNull();
        assertThat(saved.getFailureCount()).isZero();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void uniqueConstraintOnSubscriberAndEndpointUrl() {
        repo.save(fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/UNIQ-1"));

        SubscriberPushEndpoint duplicate = fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/UNIQ-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            repo.save(duplicate);
            repo.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void findActiveBySubscriberExcludesSoftDeleted() {
        SubscriberPushEndpoint active = fixture("acme", "1204", "https://endpoint-a");
        repo.save(active);

        SubscriberPushEndpoint deleted = fixture("acme", "1204", "https://endpoint-b");
        deleted.setDeletedAt(OffsetDateTime.now().minusDays(1));
        repo.save(deleted);

        List<SubscriberPushEndpoint> activeOnly = repo.findActiveBySubscriber("acme", "1204");

        assertThat(activeOnly)
            .extracting(SubscriberPushEndpoint::getEndpointUrl)
            .contains("https://endpoint-a")
            .doesNotContain("https://endpoint-b");
    }

    @Test
    void softDeleteBySubscriberDeactivatesAllEndpoints() {
        repo.save(fixture("acme", "1204", "https://ep-1"));
        repo.save(fixture("acme", "1204", "https://ep-2"));
        repo.save(fixture("acme", "5678", "https://ep-3")); // farklı subscriber — etkilenmez

        int deleted = repo.softDeleteBySubscriber("acme", "1204", OffsetDateTime.now());
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(repo.findActiveBySubscriber("acme", "1204")).isEmpty();
        // Farklı subscriber etkilenmedi
        assertThat(repo.findActiveBySubscriber("acme", "5678")).hasSize(1);
    }

    @Test
    void incrementFailureUpdatesCountAndReason() {
        SubscriberPushEndpoint saved = repo.save(fixture("acme", "1204", "https://ep-fail"));

        int updated = repo.incrementFailure(saved.getEndpointId(), OffsetDateTime.now(), "410_GONE");
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        SubscriberPushEndpoint refetched = repo.findById(saved.getEndpointId()).orElseThrow();
        assertThat(refetched.getFailureCount()).isEqualTo(1);
        assertThat(refetched.getLastFailureReason()).isEqualTo("410_GONE");
        assertThat(refetched.getLastFailureAt()).isNotNull();
    }

    @Test
    void findByEndpointUrlReturnsExistingRow() {
        repo.save(fixture("acme", "1204", "https://lookup-endpoint"));

        Optional<SubscriberPushEndpoint> found = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://lookup-endpoint"
        );

        assertThat(found).isPresent();
        assertThat(found.get().getEndpointUrl()).isEqualTo("https://lookup-endpoint");
    }

    private SubscriberPushEndpoint fixture(String orgId, String subscriberId, String endpointUrl) {
        SubscriberPushEndpoint e = new SubscriberPushEndpoint();
        e.setOrgId(orgId);
        e.setSubscriberId(subscriberId);
        e.setEndpointUrl(endpointUrl);
        e.setP256dhKey("test-p256dh-key-base64url");
        e.setAuthSecret("test-auth-secret-16b");
        e.setUserAgent("Mozilla/5.0 (Test)");
        e.setPlatformHint("chrome");
        return e;
    }
}
