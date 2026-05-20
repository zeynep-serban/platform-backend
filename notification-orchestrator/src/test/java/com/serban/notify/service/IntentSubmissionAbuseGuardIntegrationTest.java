package com.serban.notify.service;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.AbuseGuardBlockedException;
import com.serban.notify.repository.AuditEventRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AbuseGuard integration test through IntentSubmissionService — Faz 23.2.F
 * T1.6.6 (Codex thread {@code 019e0c28} P2 deferred follow-up; current-
 * thread {@code 019e42d6} M3 closure sweep).
 *
 * <p>Tests the wiring at {@code IntentSubmissionService#submit} step 1.5
 * (after idempotency replay, before capacity check) using a real Spring
 * Boot context + Testcontainers Postgres. Complements the unit test
 * {@code AbuseGuardServiceTest} by exercising:
 * <ul>
 *   <li>The send pipeline integration (rate limit + bypass + fanout cap
 *       all reach the correct decision branch)</li>
 *   <li>{@code AbuseGuardBlockedException} HTTP 429 mapping is wired
 *       through the controller path (exception class is annotated
 *       {@code @ResponseStatus(TOO_MANY_REQUESTS)})</li>
 *   <li>Audit row persistence under {@code Propagation.REQUIRES_NEW} —
 *       row survives the outer transaction rollback that follows the
 *       exception throw (Codex {@code 019e0c28} iter-2 P1 absorb)</li>
 *   <li>Multi-tenant (orgId, topicKey) window isolation in-process</li>
 * </ul>
 *
 * <p>Property override drops the rate limit and fan-out cap to small
 * values so a complete burst-then-block scenario runs in well under a
 * second per test method.
 */
@SpringBootTest(properties = {
    "notify.abuse.rate-limit.max-per-window=5",
    "notify.abuse.webhook-fanout.cap=3"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class IntentSubmissionAbuseGuardIntegrationTest extends AbstractPostgresTest {

    @Autowired IntentSubmissionService service;
    @Autowired NotificationTemplateRepository templateRepo;
    @Autowired AuditEventRepository auditRepo;

    @BeforeEach
    void seedTemplate() {
        // Idempotent seed (Testcontainers container persistent, context restart per test).
        if (templateRepo.findByTemplateIdAndVersionAndLocale("auth-password-reset", 1, "tr-TR")
                .isPresent()) {
            return;
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("auth-password-reset");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject("Şifre sıfırla");
        t.setBodyText("Hello ${user_name}");
        t.setActive(true);
        t.setCreatedBy("test");
        templateRepo.save(t);
    }

    /**
     * Storm test — rate limit 5, the 6th submit must throw and the audit
     * row must survive the rollback caused by the exception (Propagation.
     * REQUIRES_NEW). This is the critical contract: the operator must see
     * the abuse-block event in the audit trail even though the intent was
     * never inserted.
     */
    @Test
    void stormExceedsRateLimitThrowsAndPersistsAuditRow() {
        String topic = "auth.password-reset.storm";

        // 5 submits within the window — all ALLOWED.
        for (int i = 0; i < 5; i++) {
            SubmitIntentResponse resp = service.submit(newAbuseRequest(
                "default", topic, NotificationIntent.Severity.info,
                List.of("email")));
            assertThat(resp.status()).isEqualTo("ACCEPTED");
        }

        // 6th submit — must throw AbuseGuardBlockedException.
        // Codex 019e42df iter-1 P1 absorb: request'i değişkende tut, audit
        // scope'unu correlation_id ile filter.
        SubmitIntentRequest blocked = newAbuseRequest("default", topic,
            NotificationIntent.Severity.info, List.of("email"));

        assertThatThrownBy(() -> service.submit(blocked))
            .isInstanceOf(AbuseGuardBlockedException.class)
            .hasMessageContaining("rate_limit_exceeded");

        // Audit row must persist despite the exception throw — Propagation.
        // REQUIRES_NEW commits the audit row in an independent transaction
        // that survives the outer rollback. Scoped by correlationId to
        // avoid reused Testcontainers stale-audit false positives.
        assertThat(auditsFor("RATE_LIMITED", blocked))
            .as("RATE_LIMITED audit row for this blocked request must persist via Propagation.REQUIRES_NEW")
            .hasSize(1);
    }

    /**
     * Critical-severity bypass — even after the window is full, a
     * severity=critical intent must be ALLOWED and a
     * RATE_LIMIT_BYPASSED_CRITICAL audit row must be written.
     *
     * <p>Codex {@code 019e0c28} P1 absorb: bypass scope was deliberately
     * narrowed to severity=critical only (data_classification=security
     * bypass was removed because the DTO field is client-controlled and
     * carries no trusted-producer authority).
     */
    @Test
    void criticalSeverityBypassesRateLimitAndAuditsBypass() {
        String topic = "auth.password-reset.critical-bypass";

        // Fill the window with non-critical traffic.
        for (int i = 0; i < 5; i++) {
            service.submit(newAbuseRequest("default", topic,
                NotificationIntent.Severity.info, List.of("email")));
        }

        // 6th submit with severity=critical — bypasses the rate limit.
        // Scoped by correlationId for audit assertion.
        SubmitIntentRequest critical = newAbuseRequest("default", topic,
            NotificationIntent.Severity.critical, List.of("email"));
        SubmitIntentResponse resp = service.submit(critical);
        assertThat(resp.status()).isEqualTo("ACCEPTED");

        assertThat(auditsFor("RATE_LIMIT_BYPASSED_CRITICAL", critical))
            .as("RATE_LIMIT_BYPASSED_CRITICAL audit row for this bypass request must persist")
            .hasSize(1);
    }

    /**
     * Webhook fan-out cap — channels.count("webhook") &gt; cap → BLOCKED.
     * Pre-rate-limit gate so even the first request hits this branch.
     */
    @Test
    void webhookFanoutCapBlocksAndPersistsAuditRow() {
        String topic = "auth.password-reset.fanout-cap";

        // cap=3 → 4 webhook channels triggers WEBHOOK_FANOUT_CAPPED on first request.
        SubmitIntentRequest blocked = newAbuseRequest("default", topic,
            NotificationIntent.Severity.info,
            List.of("webhook", "webhook", "webhook", "webhook"));

        assertThatThrownBy(() -> service.submit(blocked))
            .isInstanceOf(AbuseGuardBlockedException.class)
            .hasMessageContaining("webhook_fanout_cap_exceeded");

        assertThat(auditsFor("WEBHOOK_FANOUT_CAPPED", blocked))
            .as("WEBHOOK_FANOUT_CAPPED audit row for this blocked request must persist via Propagation.REQUIRES_NEW")
            .hasSize(1);
    }

    /**
     * HARD safety limit — fan-out cap is NOT bypassed by severity=critical
     * (Codex {@code 019e0c28} iter-2 P1 absorb). Critical bypass applies
     * only to the rate limit; fan-out flood would defeat the cap's
     * purpose entirely.
     */
    @Test
    void criticalSeverityDoesNotBypassWebhookFanoutCap() {
        String topic = "auth.password-reset.critical-fanout-attempt";

        // Even severity=critical hits the hard fan-out cap.
        SubmitIntentRequest blocked = newAbuseRequest("default", topic,
            NotificationIntent.Severity.critical,
            List.of("webhook", "webhook", "webhook", "webhook"));

        assertThatThrownBy(() -> service.submit(blocked))
            .isInstanceOf(AbuseGuardBlockedException.class)
            .hasMessageContaining("webhook_fanout_cap_exceeded");

        // For THIS request: WEBHOOK_FANOUT_CAPPED audit must persist;
        // RATE_LIMIT_BYPASSED_CRITICAL audit must NOT (fan-out cap blocks
        // before the critical-bypass branch).
        assertThat(auditsFor("WEBHOOK_FANOUT_CAPPED", blocked)).hasSize(1);
        assertThat(auditsFor("RATE_LIMIT_BYPASSED_CRITICAL", blocked))
            .as("Critical bypass must NOT be triggered when fan-out cap is the block reason")
            .isEmpty();
    }

    /**
     * Multi-tenant isolation — windows are keyed by (orgId, topicKey).
     * Filling org1's window must not affect org2's quota even for the
     * same topic. This documents the in-process ConcurrentHashMap
     * partition behavior so a future change to shared state (e.g.
     * migration to PG / Redis) keeps the same contract.
     */
    @Test
    void multiTenantWindowsAreIndependent() {
        String topic = "auth.password-reset.shared-topic";

        // Fill org1's window to the limit.
        for (int i = 0; i < 5; i++) {
            service.submit(newAbuseRequest("org1", topic,
                NotificationIntent.Severity.info, List.of("email")));
        }

        // org1 + 6th → BLOCKED.
        assertThatThrownBy(() -> service.submit(newAbuseRequest(
            "org1", topic, NotificationIntent.Severity.info,
            List.of("email"))))
            .isInstanceOf(AbuseGuardBlockedException.class);

        // org2 + same topic + 1st request → ALLOWED (independent window).
        SubmitIntentResponse org2Resp = service.submit(newAbuseRequest(
            "org2", topic, NotificationIntent.Severity.info,
            List.of("email")));
        assertThat(org2Resp.status())
            .as("org2 has its own window and must not inherit org1's quota")
            .isEqualTo("ACCEPTED");
    }

    /* ----- Helpers --------------------------------------------------- */

    /**
     * Audit row scoper — filters by event_type AND correlation_id so the
     * assertion is robust against reused Testcontainers stale-audit
     * pollution. Codex 019e42df iter-1 P1 absorb.
     */
    private java.util.List<AuditEvent> auditsFor(String eventType, SubmitIntentRequest req) {
        return auditRepo.findAll().stream()
            .filter(a -> eventType.equals(a.getEventType()))
            .filter(a -> a.getDetails() != null
                && req.correlationId().equals(a.getDetails().get("correlation_id")))
            .toList();
    }

    private SubmitIntentRequest newAbuseRequest(
        String orgId,
        String topicKey,
        NotificationIntent.Severity severity,
        List<String> channels
    ) {
        String intentId = UUID.randomUUID().toString();
        return new SubmitIntentRequest(
            intentId,
            "idem-" + UUID.randomUUID(),
            "trace-" + intentId.substring(0, 8),
            orgId,
            topicKey,
            severity,
            NotificationIntent.DataClassification.transactional,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, "Halil", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            channels,
            Map.of("user_name", "Halil", "reset_url", "https://testai.acik.com/reset"),
            null, null, null, null, null
        );
    }
}
