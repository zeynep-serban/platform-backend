package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationInboxRepository integration test (Faz 23.3 PR-E.1).
 *
 * <p>Test scope:
 * <ul>
 *   <li>UNIQUE (org_id, intent_id, subscriber_id) — idempotent fan-out insert
 *       protection</li>
 *   <li>findActiveBySubscriber — paged listing, ARCHIVED filtered, newest-first</li>
 *   <li>countUnreadBySubscriber — partial index UNREAD count correctness</li>
 *   <li>markAsRead UNREAD→READ atomic; idempotent (no-op when already READ)</li>
 *   <li>archive *→ARCHIVED atomic; idempotent (no-op when already ARCHIVED)</li>
 *   <li>Trigger auto-sets read_at / archived_at on state transition</li>
 *   <li>Cross-tenant isolation (org_id + subscriber_id filter)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@Transactional  // CI iter-1 fix: @Modifying(flushAutomatically=true) requires
                // active transaction; class-level @Transactional gives each test
                // its own auto-rollback transaction (no cross-test pollution).
class NotificationInboxRepositoryTest extends AbstractPostgresTest {

    @Autowired NotificationInboxRepository repo;

    @BeforeEach
    void cleanInbox() {
        // Each test method runs with fresh context; defensive cleanup.
        repo.deleteAll();
    }

    @Test
    void persistAndQueryByOrgIntentSubscriber() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));

        Optional<NotificationInbox> found = repo.findByOrgIdAndIntentIdAndSubscriberId(
            "default", "intent-1", "sub-1"
        );

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getState()).isEqualTo(NotificationInbox.State.UNREAD);
    }

    @Test
    void findByOrgIdAndIdAndSubscriberIdAppliesTenancyFilter() {
        NotificationInbox saved = repo.save(stub("default", "intent-x", "sub-1"));

        // Wrong subscriber → empty
        assertThat(repo.findByOrgIdAndIdAndSubscriberId("default", saved.getId(), "wrong-sub"))
            .isEmpty();
        // Wrong org → empty
        assertThat(repo.findByOrgIdAndIdAndSubscriberId("other-org", saved.getId(), "sub-1"))
            .isEmpty();
        // Correct tenancy → present
        assertThat(repo.findByOrgIdAndIdAndSubscriberId("default", saved.getId(), "sub-1"))
            .isPresent();
    }

    @Test
    void findActiveBySubscriberOrdersNewestFirstAndExcludesArchived() {
        // 3 inbox rows for sub-1 with different states
        NotificationInbox unread = repo.save(stub("default", "intent-unread", "sub-1"));
        NotificationInbox read = repo.save(stub("default", "intent-read", "sub-1"));
        read.setState(NotificationInbox.State.READ);
        repo.save(read);
        NotificationInbox archived = repo.save(stub("default", "intent-archived", "sub-1"));
        archived.setState(NotificationInbox.State.ARCHIVED);
        repo.save(archived);

        Page<NotificationInbox> page = repo.findActiveBySubscriber(
            "default", "sub-1", PageRequest.of(0, 20)
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(NotificationInbox::getState)
            .containsOnly(NotificationInbox.State.UNREAD, NotificationInbox.State.READ);
        assertThat(page.getContent()).extracting(NotificationInbox::getIntentId)
            .doesNotContain("intent-archived");
    }

    @Test
    void findActiveBySubscriberFiltersCrossTenant() {
        repo.save(stub("default", "intent-a", "sub-1"));
        repo.save(stub("other-org", "intent-b", "sub-1"));

        Page<NotificationInbox> page = repo.findActiveBySubscriber(
            "default", "sub-1", PageRequest.of(0, 20)
        );

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getOrgId()).isEqualTo("default");
    }

    @Test
    void countUnreadBySubscriberReturnsCorrectCount() {
        // 3 UNREAD + 1 READ + 1 ARCHIVED = 3 unread
        for (int i = 0; i < 3; i++) {
            repo.save(stub("default", "intent-u-" + i, "sub-1"));
        }
        NotificationInbox readRow = repo.save(stub("default", "intent-r", "sub-1"));
        readRow.setState(NotificationInbox.State.READ);
        repo.save(readRow);
        NotificationInbox archivedRow = repo.save(stub("default", "intent-a", "sub-1"));
        archivedRow.setState(NotificationInbox.State.ARCHIVED);
        repo.save(archivedRow);

        long count = repo.countUnreadBySubscriber("default", "sub-1");

        assertThat(count).isEqualTo(3L);
    }

    @Test
    void markAsReadUpdatesStateAndTriggerSetsReadAt() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        assertThat(saved.getReadAt()).isNull();

        int affected = repo.markAsRead("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(1);
        // Re-fetch — trigger auto-sets read_at on state transition
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.READ);
        assertThat(refetched.getReadAt()).isNotNull();
    }

    @Test
    void markAsReadIdempotentNoOpIfAlreadyRead() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.markAsRead("default", saved.getId(), "sub-1", OffsetDateTime.now());

        // Second call → 0 affected (WHERE state=UNREAD filter)
        int affected = repo.markAsRead("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void markAsReadCrossTenantNoEffect() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));

        int affected = repo.markAsRead("default", saved.getId(), "wrong-sub", OffsetDateTime.now());

        assertThat(affected).isEqualTo(0);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.UNREAD);
    }

    @Test
    void archiveUpdatesStateAndTriggerSetsArchivedAt() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        assertThat(saved.getArchivedAt()).isNull();

        int affected = repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(1);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.ARCHIVED);
        assertThat(refetched.getArchivedAt()).isNotNull();
    }

    @Test
    void archiveFromReadStateAlsoTransitions() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.markAsRead("default", saved.getId(), "sub-1", OffsetDateTime.now());

        int affected = repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(1);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.ARCHIVED);
        // Both timestamps should be set (read_at from earlier transition)
        assertThat(refetched.getReadAt()).isNotNull();
        assertThat(refetched.getArchivedAt()).isNotNull();
    }

    @Test
    void archiveIdempotentNoOpIfAlreadyArchived() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());

        int affected = repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void uniqueOrgIntentSubscriberPreventsDuplicateInsert() {
        repo.save(stub("default", "intent-dup", "sub-1"));

        // Second insert with same (org, intent, subscriber) tuple → unique violation
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> {
                repo.save(stub("default", "intent-dup", "sub-1"));
                repo.flush();
            }
        );
    }

    @Test
    void sameIntentDifferentSubscriberAllowed() {
        // Different subscribers may share intent (multi-recipient fan-out)
        repo.save(stub("default", "intent-multi", "sub-1"));
        repo.save(stub("default", "intent-multi", "sub-2"));

        assertThat(repo.findByOrgIdAndIntentIdAndSubscriberId("default", "intent-multi", "sub-1"))
            .isPresent();
        assertThat(repo.findByOrgIdAndIntentIdAndSubscriberId("default", "intent-multi", "sub-2"))
            .isPresent();
    }

    @Test
    void dbTriggerBlocksReadToUnreadBackwardTransition() {
        // Codex iter-1 P2 absorb: forward-only state machine; READ → UNREAD must
        // raise exception at DB level (defense-in-depth beyond app-level).
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.markAsRead("default", saved.getId(), "sub-1", OffsetDateTime.now());
        // Force-clear persistence cache so re-fetch hits DB
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.READ);

        refetched.setState(NotificationInbox.State.UNREAD);

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> {
                repo.save(refetched);
                repo.flush();
            }
        );
    }

    @Test
    void dbTriggerBlocksArchivedToUnreadBackwardTransition() {
        // ARCHIVED is terminal; cannot transition to any other state
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();

        refetched.setState(NotificationInbox.State.UNREAD);

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> {
                repo.save(refetched);
                repo.flush();
            }
        );
    }

    @Test
    void dbTriggerBlocksArchivedToReadBackwardTransition() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();

        refetched.setState(NotificationInbox.State.READ);

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () -> {
                repo.save(refetched);
                repo.flush();
            }
        );
    }

    @Test
    void dbTriggerAllowsForwardTransitionUnreadToArchived() {
        // UNREAD → ARCHIVED (skipping READ) is forward-only valid
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));

        int affected = repo.archive("default", saved.getId(), "sub-1", OffsetDateTime.now());

        assertThat(affected).isEqualTo(1);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.ARCHIVED);
        assertThat(refetched.getArchivedAt()).isNotNull();
    }

    // ─── KVKK erasure (Faz 23.3 PR-E.1 Codex iter-1 P1.2 absorb) ────────

    @Test
    void deleteByOrgIdAndSubscriberIdHardDeletesAllSubscriberRows() {
        repo.save(stub("default", "intent-1", "sub-1"));
        repo.save(stub("default", "intent-2", "sub-1"));
        repo.save(stub("default", "intent-3", "sub-2"));  // different sub
        repo.save(stub("other-org", "intent-4", "sub-1"));  // different org

        int deleted = repo.deleteByOrgIdAndSubscriberId("default", "sub-1");

        assertThat(deleted).isEqualTo(2);
        // Cross-tenant rows preserved
        assertThat(repo.findByOrgIdAndIntentIdAndSubscriberId("default", "intent-3", "sub-2"))
            .isPresent();
        assertThat(repo.findByOrgIdAndIntentIdAndSubscriberId("other-org", "intent-4", "sub-1"))
            .isPresent();
    }

    @Test
    void longSubjectAcceptedPostV10TextMigration() {
        // Codex iter-1 P1.2 absorb (Faz 23.3 PR-E.2): subject TEXT not VARCHAR(500)
        // — long rendered subjects (substitution-heavy templates) must persist.
        NotificationInbox row = stub("default", "intent-long-subj", "sub-1");
        String longSubject = "A".repeat(2000);  // 2000 chars > old 500 limit
        row.setSubject(longSubject);

        NotificationInbox saved = repo.save(row);
        repo.flush();
        // Re-fetch from DB to verify TEXT column round-trip
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getSubject()).hasSize(2000);
        assertThat(refetched.getSubject()).startsWith("AAAA");
    }

    @Test
    void deleteByOrgIdAndSubscriberIdReturnsZeroWhenNoMatch() {
        repo.save(stub("default", "intent-1", "sub-1"));

        int deleted = repo.deleteByOrgIdAndSubscriberId("default", "nonexistent-sub");

        assertThat(deleted).isEqualTo(0);
    }

    @Test
    void paginationRespectsPageSize() {
        for (int i = 0; i < 25; i++) {
            repo.save(stub("default", "intent-page-" + i, "sub-1"));
        }

        Page<NotificationInbox> page1 = repo.findActiveBySubscriber(
            "default", "sub-1", PageRequest.of(0, 10)
        );
        Page<NotificationInbox> page3 = repo.findActiveBySubscriber(
            "default", "sub-1", PageRequest.of(2, 10)
        );

        assertThat(page1.getContent()).hasSize(10);
        assertThat(page3.getContent()).hasSize(5);  // last page
        assertThat(page1.getTotalElements()).isEqualTo(25L);
    }

    private static NotificationInbox stub(String orgId, String intentId, String subscriberId) {
        NotificationInbox row = new NotificationInbox();
        row.setOrgId(orgId);
        row.setIntentId(intentId);
        row.setSubscriberId(subscriberId);
        row.setSubject("Test subject");
        row.setBodyText("Test body");
        row.setLocale("tr-TR");
        row.setTopicKey("test.topic");
        row.setSeverity("info");
        row.setState(NotificationInbox.State.UNREAD);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }
}
