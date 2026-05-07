package com.serban.notify.inbox;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * InboxNotifyListener Testcontainers integration test (Faz 23.4 PR-E.4
 * Codex iter-1 P1.4 absorb).
 *
 * <p>Real PG LISTEN/NOTIFY round-trip:
 * <ul>
 *   <li>Cross-pod-enabled=true (production default; overrides test profile false)</li>
 *   <li>Publisher emits pg_notify via JdbcTemplate</li>
 *   <li>Listener background thread receives, parses, recomputes count, emits Spring event</li>
 *   <li>Test {@link RecordingEventListener} captures events for assertion</li>
 * </ul>
 *
 * <p>Awaitility used for async event arrival (PG LISTEN poll cadence ~1s).
 *
 * <p>Test-only TestConfig overrides cross-pod-enabled=true so the listener
 * actually starts (default test profile disables it).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "notify.inbox.cross-pod-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@Import(InboxNotifyListenerIntegrationTest.TestConfig.class)
class InboxNotifyListenerIntegrationTest extends AbstractPostgresTest {

    @Autowired InboxEventPublisher publisher;
    @Autowired NotificationInboxRepository inboxRepository;
    @Autowired RecordingEventListener recorder;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void cleanInbox() {
        inboxRepository.deleteAll();
        recorder.clear();
    }

    @AfterEach
    void clearRecorder() {
        recorder.clear();
    }

    @Test
    void publishViaPgNotifyDeliversToListenerAndRecomputesFreshCount() {
        // Codex iter-2 P1 absorb: NO @Transactional. PG NOTIFY only delivers
        // post-COMMIT; @Transactional with default rollback would suppress
        // delivery → listener never receives → await timeout.
        // Use programmatic TransactionTemplate for explicit COMMIT.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            for (int i = 0; i < 3; i++) {
                NotificationInbox row = new NotificationInbox();
                row.setOrgId("default");
                row.setIntentId("intent-" + i);
                row.setSubscriberId("sub-x");
                row.setLocale("tr-TR");
                row.setTopicKey("test.topic");
                row.setSeverity("info");
                row.setState(NotificationInbox.State.UNREAD);
                row.setCreatedAt(OffsetDateTime.now());
                inboxRepository.save(row);
            }
            // publish in same tx — NOTIFY queued until COMMIT
            publisher.publishInboxUpdated("default", "sub-x");
            return null;  // COMMIT
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(recorder.events).anyMatch(ev ->
                "default".equals(ev.orgId())
                    && "sub-x".equals(ev.subscriberId())
                    && ev.unreadCount() == 3L
            );
        });
    }

    @Test
    void zeroUnreadDeliveredEvenIfNoMatchingRows() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            publisher.publishInboxUpdated("default", "sub-y");
            return null;  // COMMIT
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(recorder.events).anyMatch(ev ->
                "default".equals(ev.orgId())
                    && "sub-y".equals(ev.subscriberId())
                    && ev.unreadCount() == 0L
            );
        });
    }

    @Test
    void rollbackSuppressesNotifyDelivery() {
        // Codex iter-2 önerisi: rollback semantik kanıtla.
        // publisher in-tx publish; rollback → NOTIFY iptal → listener event almaz.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                publisher.publishInboxUpdated("default", "sub-rollback");
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ignored) {
            // expected if rollback raises
        }

        // Wait briefly to ensure no event sneaks in (PG poll cadence ~1s; allow 3s)
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        assertThat(recorder.events)
            .noneMatch(ev -> "sub-rollback".equals(ev.subscriberId()));
    }

    /**
     * Test config — registers RecordingEventListener as a Spring bean.
     *
     * <p>Codex iter-3 absorb: Static inner class with {@code @Component}
     * is NOT picked up by component scan (Spring's nested-class scan is
     * disabled by default). {@code @TestConfiguration} + explicit
     * {@code @Bean} + {@code @Import} on the test class is the canonical
     * Spring Boot pattern for test-scope beans (see {@code AdminErasureControllerSecurityTest.SecurityTestConfig}).
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        RecordingEventListener recordingEventListener() {
            return new RecordingEventListener();
        }
    }

    /** Test bean — captures InboxUpdatedEvents emitted by the LISTEN worker. */
    static class RecordingEventListener {
        final java.util.List<InboxUpdatedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void capture(InboxUpdatedEvent event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }
    }
}
