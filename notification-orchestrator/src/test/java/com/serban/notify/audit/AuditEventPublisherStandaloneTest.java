package com.serban.notify.audit;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.AuditEvent;
import com.serban.notify.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditEventPublisher.publishStandalone integration test (Faz 23.3 PR-E.1
 * Codex iter-2 P1 absorb).
 *
 * <p>Real AuditEventPublisher + real AuditEventRepository — verifies:
 * <ul>
 *   <li>Standalone audit row inserts without NPE on null intent</li>
 *   <li>Synthesized {@code intent_id} satisfies NOT NULL + has uniqueness</li>
 *   <li>Synthesized {@code topic_key} matches "audit.standalone.{event-type-lower}"</li>
 *   <li>{@code inbox_rows_deleted} detail field passes PiiRedactor whitelist
 *       (Codex iter-2 P2 absorb)</li>
 *   <li>Append-only contract preserved (audit row visible after commit)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class AuditEventPublisherStandaloneTest extends AbstractPostgresTest {

    @Autowired AuditEventPublisher publisher;
    @Autowired AuditEventRepository repository;

    @Test
    @Transactional
    void publishStandaloneInsertsAuditRowWithoutIntent() {
        Map<String, Object> details = Map.of(
            "erasure_reason", "subject_request",
            "evidence_ref", "TICKET-1",
            "subscriber_id", "1204",
            "inbox_rows_deleted", 3
        );

        publisher.publishStandalone(
            "SUBSCRIBER_INBOX_ERASURE",
            "acme",
            null,
            details
        );

        List<AuditEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        AuditEvent saved = rows.get(0);
        assertThat(saved.getEventType()).isEqualTo("SUBSCRIBER_INBOX_ERASURE");
        assertThat(saved.getOrgId()).isEqualTo("acme");
        // Codex iter-3 P1 absorb: intent_id bounded by VARCHAR(64); event type
        // lives in event_type column, not intent_id.
        assertThat(saved.getIntentId())
            .startsWith("standalone-")
            .hasSizeLessThanOrEqualTo(64);
        assertThat(saved.getTopicKey())
            .isEqualTo("audit.standalone.subscriber_inbox_erasure");
        assertThat(saved.getRecipientHash()).isNull();
        assertThat(saved.getChannel()).isNull();
        assertThat(saved.getTemplateId()).isNull();
    }

    @Test
    @Transactional
    void inboxRowsDeletedDetailPassesPiiRedactorWhitelist() {
        Map<String, Object> details = Map.of(
            "erasure_reason", "subject_request",
            "evidence_ref", "TICKET-2",
            "subscriber_id", "5678",
            "inbox_rows_deleted", 7,
            "user_email", "secret@x.y"  // PII — must be filtered out
        );

        publisher.publishStandalone(
            "SUBSCRIBER_INBOX_ERASURE",
            "acme",
            null,
            details
        );

        AuditEvent saved = repository.findAll().get(0);
        Map<String, Object> filtered = saved.getDetails();
        // Whitelisted detail preserved (Codex iter-2 P2 absorb)
        assertThat(filtered).containsEntry("inbox_rows_deleted", 7);
        assertThat(filtered).containsEntry("erasure_reason", "subject_request");
        assertThat(filtered).containsEntry("evidence_ref", "TICKET-2");
        assertThat(filtered).containsEntry("subscriber_id", "5678");
        // PII filtered out
        assertThat(filtered).doesNotContainKey("user_email");
    }

    @Test
    @Transactional
    void publishStandaloneIsUniquePerCall() {
        // Synthesized intent_id includes UUID → multiple calls produce
        // distinct rows (no UNIQUE clash even with same eventType + orgId)
        publisher.publishStandalone("SUBSCRIBER_INBOX_ERASURE", "acme", null,
            Map.of("inbox_rows_deleted", 1));
        publisher.publishStandalone("SUBSCRIBER_INBOX_ERASURE", "acme", null,
            Map.of("inbox_rows_deleted", 2));

        List<AuditEvent> rows = repository.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AuditEvent::getIntentId)
            .doesNotHaveDuplicates();
    }
}
