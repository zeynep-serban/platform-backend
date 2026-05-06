package com.serban.notify.service;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.IntakeCapacityExceededException;
import com.serban.notify.exception.TemplateNotFoundException;
import com.serban.notify.repository.AuditEventRepository;
import com.serban.notify.repository.IdempotencyKeyRepository;
import com.serban.notify.repository.NotificationIntentRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class IntentSubmissionServiceIntegrationTest extends AbstractPostgresTest {

    @Autowired IntentSubmissionService service;
    @Autowired NotificationTemplateRepository templateRepo;
    @Autowired NotificationIntentRepository intentRepo;
    @Autowired IdempotencyKeyRepository idempRepo;
    @Autowired AuditEventRepository auditRepo;

    @BeforeEach
    void seedTemplate() {
        // Idempotent seed — Testcontainers reuse + DirtiesContext combo:
        // container persistent, context restart per test method. Skip if exists.
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

    @Test
    void submitNewIntentSuccess() {
        SubmitIntentRequest req = newRequest(UUID.randomUUID().toString(), "test-key-1");
        SubmitIntentResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("ACCEPTED");
        assertThat(resp.intentId()).isEqualTo(req.intentId());

        // Persist verify
        var intent = intentRepo.findByIntentId(req.intentId()).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(NotificationIntent.Status.PENDING);
        assertThat(intent.getOrgId()).isEqualTo("default");
        assertThat(intent.getTemplateVersion()).isEqualTo(1);

        // Idempotency key persist
        assertThat(idempRepo.findActiveKey("default", "test-key-1",
            java.time.OffsetDateTime.now()))
            .isPresent();

        // Audit row INSERT
        var audits = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(req.correlationId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getEventType()).isEqualTo("INTENT_CREATED");
        assertThat(audits.get(0).getDetails()).containsKeys("template_id", "recipient_hash");
        // PII whitelist enforcement: payload values absent from audit details
        assertThat(audits.get(0).getDetails()).doesNotContainKeys("user_email", "user_name");
    }

    @Test
    void duplicateIdempotencyKeyReturnsReplayed() {
        String key = "test-key-replay";
        String firstIntentId = UUID.randomUUID().toString();
        SubmitIntentResponse first = service.submit(newRequest(firstIntentId, key));
        assertThat(first.status()).isEqualTo("ACCEPTED");

        // Submit second time with SAME idempotency_key but DIFFERENT intent_id
        SubmitIntentResponse second = service.submit(
            newRequest(UUID.randomUUID().toString(), key)
        );
        assertThat(second.status()).isEqualTo("REPLAYED");
        assertThat(second.intentId()).isEqualTo(firstIntentId);  // original intent_id

        // Verify only the FIRST intent persisted (Testcontainers reuse: count'tan
        // ziyade direkt original intent presence + key uniqueness check)
        assertThat(intentRepo.findByIntentId(firstIntentId)).isPresent();
    }

    @Test
    void invalidTemplateThrowsNotFound() {
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "key-invalid-template",
            "trace-1",
            "default",
            "test.topic",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.transactional,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, null, "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("nonexistent-template", null, "tr-TR"),
            List.of("email"),
            Map.of("key", "value"),
            null, null, null, null, null
        );
        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(TemplateNotFoundException.class)
            .hasMessageContaining("nonexistent-template");
    }

    @Test
    void unsupportedChannelRejected() {
        // Faz 23.3.1: SMS now first-class (PR2 kernel = email/sms/slack/webhook).
        // Use "push-fcm" as canonical not-yet-implemented channel for this gate.
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "ch-key",
            "trace-ch",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, "Halil", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("push-fcm"),  // not in PR2/Faz23.3.1 kernel
            Map.of("k", "v"),
            null, null, null, null, null
        );
        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("push-fcm");
    }

    @Test
    void externalRecipientWithoutContactRejected() {
        // Codex post-impl bulgu #3 absorb: external requires email or phone
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "ext-key",
            "trace-ext",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, null, null, "External User", "tr-TR"
                // email + phone both null
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("email"),
            Map.of("k", "v"),
            null, null, null, null, null
        );
        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("email or recipient.phone");
    }

    @Test
    void smsExternalWithPhoneAccepted() {
        // Faz 23.3.1 (Codex iter-2 P1 absorb): channels=["sms"] external
        // recipient with E.164 phone → ACCEPTED at submit gate.
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "sms-ext-ok-key",
            "trace-sms-ok",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, null, "+905321234567", "External SMS", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("sms"),
            Map.of("user_name", "Halil"),
            null, null, null, null, null
        );

        SubmitIntentResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("ACCEPTED");
        assertThat(resp.intentId()).isEqualTo(req.intentId());
    }

    @Test
    void smsExternalWithoutPhoneRejectedAtSubmit() {
        // Faz 23.3.1 (Codex iter-2 P1 absorb): channels=["sms"] external
        // recipient with email-only must be rejected at submit gate, NOT
        // accepted-then-fail-at-plan (would leave intent in PENDING limbo).
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "sms-ext-fail-key",
            "trace-sms-fail",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, "External Email Only", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("sms"),
            Map.of("k", "v"),
            null, null, null, null, null
        );

        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("phone")
            .hasMessageContaining("sms");
    }

    @Test
    void mixedEmailSmsExternalEmailOnlyRejectedAtSubmit() {
        // Mixed channel intent: external recipient must satisfy ALL channels.
        // email-only would let email dispatch but block sms; this would
        // partial-deliver — rejected at submit gate.
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "mixed-ext-fail-key",
            "trace-mixed-fail",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, "External Email Only", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("email", "sms"),
            Map.of("k", "v"),
            null, null, null, null, null
        );

        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("phone");
    }

    // ─── Faz 23.3 PR-E.2 in-app channel ────────────────────────────────

    @Test
    void inAppSubscriberAccepted() {
        // in-app + subscriber → ACCEPTED at submit gate
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "inapp-sub-ok",
            "trace-inapp-ok",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, "Halil", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("in-app"),
            Map.of("user_name", "Halil"),
            null, null, null, null, null
        );

        SubmitIntentResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("ACCEPTED");
        assertThat(resp.intentId()).isEqualTo(req.intentId());
    }

    @Test
    void inAppExternalRejectedAtSubmit() {
        // in-app + external → reject at submit (no inbox account)
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "inapp-ext-fail",
            "trace-inapp-ext-fail",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, "External", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("in-app"),
            Map.of("k", "v"),
            null, null, null, null, null
        );

        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("in-app")
            .hasMessageContaining("subscriber");
    }

    @Test
    void mixedEmailInAppExternalRejectedAtSubmit() {
        // Mixed channels: external recipient is fine for email but blocks for in-app
        // → submit gate rejects (in-app forbids external regardless of other channels)
        SubmitIntentRequest req = new SubmitIntentRequest(
            UUID.randomUUID().toString(),
            "mixed-inapp-ext-fail",
            "trace-mixed-inapp-ext",
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "ext@example.com", null, "External", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("email", "in-app"),
            Map.of("k", "v"),
            null, null, null, null, null
        );

        assertThatThrownBy(() -> service.submit(req))
            .isInstanceOf(com.serban.notify.exception.InvalidRequestException.class)
            .hasMessageContaining("in-app");
    }

    private SubmitIntentRequest newRequest(String intentId, String idemKey) {
        return new SubmitIntentRequest(
            intentId,
            idemKey,
            "trace-" + intentId.substring(0, 8),
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, "Halil", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("email"),
            Map.of("user_name", "Halil", "reset_url", "https://testai.acik.com/..."),
            null, null, null, null, null
        );
    }
}
