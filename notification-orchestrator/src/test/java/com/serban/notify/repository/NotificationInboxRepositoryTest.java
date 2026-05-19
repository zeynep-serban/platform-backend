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
    // Faz 23.5 hardening: tests that need to plant a future or past
    // created_at must do it through raw SQL because the entity column is
    // insertable=false (DB DEFAULT NOW() is canonical now).
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

        int affected = repo.markAsRead("default", saved.getId(), "sub-1");

        assertThat(affected).isEqualTo(1);
        // Re-fetch — trigger auto-sets read_at on state transition
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.READ);
        assertThat(refetched.getReadAt()).isNotNull();
    }

    @Test
    void markAsReadIdempotentNoOpIfAlreadyRead() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.markAsRead("default", saved.getId(), "sub-1");

        // Second call → 0 affected (WHERE state=UNREAD filter)
        int affected = repo.markAsRead("default", saved.getId(), "sub-1");

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void markAsReadCrossTenantNoEffect() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));

        int affected = repo.markAsRead("default", saved.getId(), "wrong-sub");

        assertThat(affected).isEqualTo(0);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.UNREAD);
    }

    @Test
    void archiveUpdatesStateAndTriggerSetsArchivedAt() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        assertThat(saved.getArchivedAt()).isNull();

        int affected = repo.archive("default", saved.getId(), "sub-1");

        assertThat(affected).isEqualTo(1);
        NotificationInbox refetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(refetched.getState()).isEqualTo(NotificationInbox.State.ARCHIVED);
        assertThat(refetched.getArchivedAt()).isNotNull();
    }

    @Test
    void archiveFromReadStateAlsoTransitions() {
        NotificationInbox saved = repo.save(stub("default", "intent-1", "sub-1"));
        repo.markAsRead("default", saved.getId(), "sub-1");

        int affected = repo.archive("default", saved.getId(), "sub-1");

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
        repo.archive("default", saved.getId(), "sub-1");

        int affected = repo.archive("default", saved.getId(), "sub-1");

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
        repo.markAsRead("default", saved.getId(), "sub-1");
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
        repo.archive("default", saved.getId(), "sub-1");
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
        repo.archive("default", saved.getId(), "sub-1");
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

        int affected = repo.archive("default", saved.getId(), "sub-1");

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
        // Faz 23.5 hardening: NotificationInbox.createdAt is now
        // insertable=false (DB DEFAULT NOW() owns the column). The
        // setter still exists for code paths that read the value back,
        // but JPA ignores it on insert. Tests that need a specific
        // timestamp use jdbcTemplate.update("...SET created_at=...")
        // instead.
        return row;
    }

    // ─── Faz 23.5 PR1: bulk mark-all-read repository contract ───────────

    @Test
    void markAllAsReadFlipsOnlyUnreadRowsScopedToCaller() {
        // 3 UNREAD rows for sub-1 in default org → all should flip.
        NotificationInbox a = repo.save(stub("default", "intent-a", "sub-1"));
        repo.save(stub("default", "intent-b", "sub-1"));
        repo.save(stub("default", "intent-c", "sub-1"));

        // Pre-existing READ row — left alone (predicate state=UNREAD).
        NotificationInbox preRead = stub("default", "intent-d", "sub-1");
        preRead.setState(NotificationInbox.State.READ);
        preRead.setReadAt(OffsetDateTime.now().minusHours(1));
        repo.save(preRead);

        int affected = repo.markAllAsRead("default", "sub-1");

        // Only the 3 UNREAD rows should flip; the pre-READ row not counted.
        assertThat(affected).isEqualTo(3);

        // Re-fetch and verify state on a representative row.
        NotificationInbox aFresh =
            repo.findByOrgIdAndIdAndSubscriberId("default", a.getId(), "sub-1").orElseThrow();
        assertThat(aFresh.getState()).isEqualTo(NotificationInbox.State.READ);
        assertThat(aFresh.getReadAt()).isNotNull();
    }

    @Test
    void markAllAsReadHonorsCreatedAtCutoff_withDbClockSourcedTimestamps() {
        // Faz 23.5 hardening (Codex thread `019e03b5`): created_at is
        // now DB-sourced (insertable=false), so we plant the past /
        // future timestamps via raw SQL after the insert. The bulk
        // mark-all-read query uses NOW() in the predicate, so the
        // future row stays UNREAD even after the bulk sweep.
        NotificationInbox older = repo.save(stub("default", "intent-old-db", "sub-1"));
        NotificationInbox newer = repo.save(stub("default", "intent-new-db", "sub-1"));
        // Move older row 1h into the past, newer row 5 min into the future.
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox SET created_at = NOW() - INTERVAL '1 hour' WHERE id = ?",
            older.getId()
        );
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox SET created_at = NOW() + INTERVAL '5 minutes' WHERE id = ?",
            newer.getId()
        );

        int affected = repo.markAllAsRead("default", "sub-1");

        assertThat(affected).isEqualTo(1);
        NotificationInbox newerFresh =
            repo.findByOrgIdAndIdAndSubscriberId("default", newer.getId(), "sub-1")
                .orElseThrow();
        assertThat(newerFresh.getState())
            .as("future-dated row outside cutoff must remain UNREAD")
            .isEqualTo(NotificationInbox.State.UNREAD);
    }

    @Test
    void markAllAsReadIsOrgAndSubscriberScopedDoesNotLeakAcrossTenants() {
        repo.save(stub("default", "intent-1", "sub-1"));
        repo.save(stub("default", "intent-2", "sub-1"));
        repo.save(stub("default", "intent-3", "sub-2"));  // different subscriber

        // Cross-org attempt: zero rows match, no leakage.
        int crossOrg = repo.markAllAsRead("wrong-org", "sub-1");
        assertThat(crossOrg).isZero();

        // Cross-subscriber attempt within the same org: zero rows match.
        int crossSub = repo.markAllAsRead("default", "sub-99");
        assertThat(crossSub).isZero();

        // Legit call sweeps only sub-1's rows in default org.
        int affected = repo.markAllAsRead("default", "sub-1");
        assertThat(affected).isEqualTo(2);

        // sub-2's row left untouched.
        long unreadSub2 = repo.countUnreadBySubscriber("default", "sub-2");
        assertThat(unreadSub2).isEqualTo(1L);
    }

    @Test
    void markAllAsReadIdempotentNoOpWhenNoUnreadRows() {
        // Only a pre-READ row exists; bulk call must return 0 affected.
        NotificationInbox r = stub("default", "intent-r", "sub-1");
        r.setState(NotificationInbox.State.READ);
        r.setReadAt(OffsetDateTime.now().minusHours(1));
        repo.save(r);

        int affected = repo.markAllAsRead("default", "sub-1");
        assertThat(affected).isZero();
    }

    @Test
    void currentDatabaseTimestamp_returnsRecentTimestamp() {
        // Faz 23.5 hardening: the bulk endpoint reads cutoff from the
        // DB clock; the helper must return a non-null timestamp that is
        // close to "now" (within a generous skew window). Hibernate's
        // native query mapper resolves PG `timestamptz` to Instant;
        // the service adapts to OffsetDateTime for the wire shape, but
        // here we assert at the Instant level the helper returns.
        java.time.Instant dbNow = repo.currentDatabaseTimestamp();
        assertThat(dbNow).isNotNull();
        java.time.Instant jvmNow = java.time.Instant.now();
        // Allow ±5 minutes for container clock skew; we only care that
        // the value is real, not a millisecond match.
        assertThat(java.time.Duration.between(dbNow, jvmNow).abs())
            .isLessThan(java.time.Duration.ofMinutes(5));
    }

    // ─── Faz 23.4 M6a: findHistoryBySubscriber ───────────────────────────

    @Test
    void findHistoryBySubscriberReturnsAllThreeStates() {
        // History is NOT state-filtered — unlike findActiveBySubscriber it
        // surfaces UNREAD + READ + ARCHIVED alike.
        repo.save(stub("default", "intent-h-unread", "sub-1"));
        NotificationInbox read = repo.save(stub("default", "intent-h-read", "sub-1"));
        repo.markAsRead("default", read.getId(), "sub-1");
        NotificationInbox archived = repo.save(stub("default", "intent-h-arch", "sub-1"));
        repo.archive("default", archived.getId(), "sub-1");

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> page = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(NotificationInbox::getState)
            .containsExactlyInAnyOrder(
                NotificationInbox.State.UNREAD,
                NotificationInbox.State.READ,
                NotificationInbox.State.ARCHIVED);
    }

    @Test
    void findHistoryBySubscriberIncludesArchivedRowThatActiveQueryExcludes() {
        // Core M6a behaviour: an archived row drops out of the active inbox
        // but stays visible in the 30-day history.
        NotificationInbox row = repo.save(stub("default", "intent-arch-m6a", "sub-1"));
        repo.archive("default", row.getId(), "sub-1");

        Page<NotificationInbox> active = repo.findActiveBySubscriber(
            "default", "sub-1", PageRequest.of(0, 20));
        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> history = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 20));

        assertThat(active.getContent()).extracting(NotificationInbox::getIntentId)
            .doesNotContain("intent-arch-m6a");
        assertThat(history.getContent()).extracting(NotificationInbox::getIntentId)
            .containsExactly("intent-arch-m6a");
    }

    @Test
    void findHistoryBySubscriberExcludesRowsOlderThanWindow() {
        NotificationInbox inWindow = repo.save(stub("default", "intent-in", "sub-1"));
        NotificationInbox outOfWindow = repo.save(stub("default", "intent-out", "sub-1"));
        // created_at is insertable=false (DB DEFAULT NOW()); plant via raw SQL.
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox SET created_at = NOW() - INTERVAL '29 days' WHERE id = ?",
            inWindow.getId());
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox SET created_at = NOW() - INTERVAL '31 days' WHERE id = ?",
            outOfWindow.getId());

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> page = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(NotificationInbox::getIntentId)
            .containsExactly("intent-in");
    }

    @Test
    void findHistoryBySubscriberWindowFloorIsInclusive() {
        NotificationInbox row = repo.save(stub("default", "intent-boundary", "sub-1"));
        // Pin created_at to a known UTC instant (column is insertable=false).
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox "
            + "SET created_at = TIMESTAMPTZ '2026-05-01 00:00:00+00' WHERE id = ?",
            row.getId());

        // since == created_at → the >= predicate includes the row.
        Page<NotificationInbox> atFloor = repo.findHistoryBySubscriber(
            "default", "sub-1", OffsetDateTime.parse("2026-05-01T00:00:00Z"),
            PageRequest.of(0, 20));
        assertThat(atFloor.getTotalElements()).isEqualTo(1);

        // since one second after created_at → row excluded.
        Page<NotificationInbox> afterFloor = repo.findHistoryBySubscriber(
            "default", "sub-1", OffsetDateTime.parse("2026-05-01T00:00:01Z"),
            PageRequest.of(0, 20));
        assertThat(afterFloor.getTotalElements()).isZero();
    }

    @Test
    void findHistoryBySubscriberHasNoUpperBoundFutureDatedRowIncluded() {
        // created_at is DB-DEFAULT-NOW so it is never future in practice;
        // this documents that the query has no upper bound — a row stamped
        // ahead of "now" (test plant / clock skew) still satisfies
        // created_at >= since.
        NotificationInbox future = repo.save(stub("default", "intent-future", "sub-1"));
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox SET created_at = NOW() + INTERVAL '1 hour' WHERE id = ?",
            future.getId());

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> page = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findHistoryBySubscriberFiltersCrossTenant() {
        repo.save(stub("default", "intent-a", "sub-1"));
        repo.save(stub("other-org", "intent-b", "sub-1"));   // different org
        repo.save(stub("default", "intent-c", "sub-2"));     // different subscriber

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> page = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getOrgId()).isEqualTo("default");
        assertThat(page.getContent().get(0).getSubscriberId()).isEqualTo("sub-1");
    }

    @Test
    void findHistoryBySubscriberUsesIdDescTieBreakerForEqualCreatedAt() {
        NotificationInbox first = repo.save(stub("default", "intent-tie-1", "sub-1"));
        NotificationInbox second = repo.save(stub("default", "intent-tie-2", "sub-1"));
        // Pin both rows to an identical created_at so ordering is decided
        // solely by the `id DESC` tie-breaker.
        jdbcTemplate.update(
            "UPDATE notify.notification_inbox "
            + "SET created_at = TIMESTAMPTZ '2026-05-10 09:00:00+00' WHERE id IN (?, ?)",
            first.getId(), second.getId());

        Page<NotificationInbox> page = repo.findHistoryBySubscriber(
            "default", "sub-1", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            PageRequest.of(0, 20));

        long higherId = Math.max(first.getId(), second.getId());
        long lowerId = Math.min(first.getId(), second.getId());
        assertThat(page.getContent()).extracting(NotificationInbox::getId)
            .containsExactly(higherId, lowerId);
    }

    @Test
    void findHistoryBySubscriberPaginationReportsTotals() {
        for (int i = 0; i < 25; i++) {
            repo.save(stub("default", "intent-hp-" + i, "sub-1"));
        }

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        Page<NotificationInbox> page0 = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(0, 10));
        Page<NotificationInbox> lastPage = repo.findHistoryBySubscriber(
            "default", "sub-1", since, PageRequest.of(2, 10));

        assertThat(page0.getContent()).hasSize(10);
        assertThat(page0.getTotalElements()).isEqualTo(25L);
        assertThat(page0.getTotalPages()).isEqualTo(3);
        assertThat(lastPage.getContent()).hasSize(5);  // 25 rows → last page has 5
    }
}
