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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *
 * <h2>Test isolation (Codex 019e6a89 absorb)</h2>
 *
 * <p>{@code AbstractPostgresTest} uses a static {@code .withReuse(true)}
 * Postgres container shared by every notify integration test (BE-020 PR-A CI
 * surfaced this as a {@code +6} row leak from
 * {@code IntentSubmissionAbuseGuardIntegrationTest.stormExceedsRateLimit...},
 * which commits 5 allowed-path {@code INTENT_CREATED} rows + 1 blocked-path
 * audit row via {@code publishStandaloneRequiresNew}). The audit table is
 * append-only (a trigger rejects DELETE/UPDATE), so this class cannot wipe
 * rows between methods.
 *
 * <p>Each test instead synthesizes a unique {@code evidence_ref} scope key
 * and filters {@code repository.findAll()} down to its own rows before
 * asserting — mirroring
 * {@code IntentSubmissionAbuseGuardIntegrationTest}'s {@code event_type +
 * correlation_id} scoping. No raw {@code findAll().get(0)} or {@code hasSize}
 * against the whole table.
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
        String scopeKey = newScopeKey();
        Map<String, Object> details = scopedDetails(scopeKey, Map.of(
            "erasure_reason", "subject_request",
            "subscriber_id", "1204",
            "inbox_rows_deleted", 3
        ));

        publisher.publishStandalone(
            "SUBSCRIBER_INBOX_ERASURE",
            "acme",
            null,
            details
        );

        List<AuditEvent> scoped = findAllByEvidenceRef(scopeKey);
        assertThat(scoped).hasSize(1);
        AuditEvent saved = scoped.get(0);
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
        String scopeKey = newScopeKey();
        Map<String, Object> details = scopedDetails(scopeKey, Map.of(
            "erasure_reason", "subject_request",
            "subscriber_id", "5678",
            "inbox_rows_deleted", 7,
            "user_email", "secret@x.y"  // PII — must be filtered out
        ));

        publisher.publishStandalone(
            "SUBSCRIBER_INBOX_ERASURE",
            "acme",
            null,
            details
        );

        AuditEvent saved = findOneByEvidenceRef(scopeKey);
        Map<String, Object> filtered = saved.getDetails();
        // Whitelisted detail preserved (Codex iter-2 P2 absorb)
        assertThat(filtered).containsEntry("inbox_rows_deleted", 7);
        assertThat(filtered).containsEntry("erasure_reason", "subject_request");
        assertThat(filtered).containsEntry("evidence_ref", scopeKey);
        assertThat(filtered).containsEntry("subscriber_id", "5678");
        // PII filtered out
        assertThat(filtered).doesNotContainKey("user_email");
    }

    @Test
    @Transactional
    void publishStandaloneIsUniquePerCall() {
        // Synthesized intent_id includes UUID → multiple calls produce
        // distinct rows (no UNIQUE clash even with same eventType + orgId).
        // Both calls share the same scope key so the two test-owned rows
        // can be retrieved without picking up rows from sibling tests.
        String scopeKey = newScopeKey();
        publisher.publishStandalone("SUBSCRIBER_INBOX_ERASURE", "acme", null,
            scopedDetails(scopeKey, Map.of("inbox_rows_deleted", 1)));
        publisher.publishStandalone("SUBSCRIBER_INBOX_ERASURE", "acme", null,
            scopedDetails(scopeKey, Map.of("inbox_rows_deleted", 2)));

        List<AuditEvent> scoped = findAllByEvidenceRef(scopeKey);
        assertThat(scoped).hasSize(2);
        assertThat(scoped).extracting(AuditEvent::getIntentId)
            .doesNotHaveDuplicates();
    }

    @Test
    @Transactional
    void actualProviderDetailPassesPiiRedactorWhitelist() {
        // Faz 23.3 multi-provider (Codex `019e3fc5` PR-1 review P1 absorb):
        // SMS failover sonrası `actual_provider` audit detail PiiRedactor
        // whitelist'ten geçmeli — aksi halde AuditEventPublisher whitelist
        // filtresi alanı sessizce düşürür ve "gerçek provider audit'te
        // görünür" P1 fix'i fiilen sağlanmaz.
        String scopeKey = newScopeKey();
        Map<String, Object> details = scopedDetails(scopeKey, Map.of(
            "delivery_status", "ACCEPTED",
            "provider_msg_id", "netgsm-fb-1",
            "actual_provider", "netgsm",
            "user_phone", "+905321234567"  // PII — filtrelenmelidir
        ));

        publisher.publishStandalone("DELIVERY_ACCEPTED", "acme", null, details);

        AuditEvent saved = findOneByEvidenceRef(scopeKey);
        Map<String, Object> filtered = saved.getDetails();
        // Whitelisted detail korundu
        assertThat(filtered).containsEntry("actual_provider", "netgsm");
        assertThat(filtered).containsEntry("delivery_status", "ACCEPTED");
        assertThat(filtered).containsEntry("provider_msg_id", "netgsm-fb-1");
        // PII filtrelendi
        assertThat(filtered).doesNotContainKey("user_phone");
    }

    // ----------------------------------------------------------------
    // Test isolation helpers (Codex 019e6a89 absorb)

    /**
     * Generates a unique {@code evidence_ref} value scoped to a single test
     * method. The audit table is shared (and append-only) across the notify
     * test suite via {@link AbstractPostgresTest}'s static reusable
     * container; per-test scope keys keep this class's row-level assertions
     * stable regardless of execution order or sibling-class polluters such
     * as {@code IntentSubmissionAbuseGuardIntegrationTest}.
     */
    private static String newScopeKey() {
        return "isolation-" + UUID.randomUUID();
    }

    /**
     * Merges the caller's detail entries with a stable {@code evidence_ref}
     * scope key. {@code evidence_ref} is on the {@code PiiRedactor} whitelist,
     * so it survives the publisher and is queryable on the persisted row.
     */
    private static Map<String, Object> scopedDetails(
            String scopeKey,
            Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put("evidence_ref", scopeKey);
        return merged;
    }

    private List<AuditEvent> findAllByEvidenceRef(String scopeKey) {
        return repository.findAll().stream()
                .filter(e -> e.getDetails() != null
                        && scopeKey.equals(e.getDetails().get("evidence_ref")))
                .toList();
    }

    private AuditEvent findOneByEvidenceRef(String scopeKey) {
        return findAllByEvidenceRef(scopeKey).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No audit row with evidence_ref=" + scopeKey));
    }
}
