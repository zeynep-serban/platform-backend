package com.serban.notify.classification;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationIntent.DataClassification;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.AuditEventRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import com.serban.notify.service.IntentSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * Faz 23.2.E Data Classification — Acceptance Test Suite
 * (charter sub-faz 23.2.E "Data classification substantively LIVE; ~2h
 * acceptance test" canlı kapanış kanıtı).
 *
 * <p>Charter scope:
 * <ol>
 *   <li>{@link DataClassification} enum 4 değerli matrix
 *       (transactional / security / commercial / system)</li>
 *   <li>{@link IntentSubmissionService} her enum değerini accept eder + intent
 *       persiste + audit publish</li>
 *   <li>Audit event details'ında {@code data_classification} doğru enum name ile
 *       persist edilir (PiiRedactor whitelist allow-listed)</li>
 *   <li>Per-classification severity matrix (info/critical) doğru audit'lenir</li>
 * </ol>
 *
 * <p>**Why acceptance gate ayrı**: önceki Codex iter'lerde (`019dfae5`,
 * `019e0c28`) data_classification=security bypass kaldırıldı (T1.6 abuse
 * guards) — artık severity=critical only bypass. Bu test enum'un classification
 * boundary'sini abuse guard ortogonal kanıtlar: classification persistence +
 * audit serialization + DTO round-trip.
 *
 * <p>Refs:
 * <ul>
 *   <li>Charter sub-faz 23.2.E (RB-faz-23-charter.md line 227)</li>
 *   <li>HARD RULE — Cross-AI peer review (post-impl Codex review)</li>
 *   <li>Codex thread `019dfaaa` PR5 (DeliveryEligibilityService design)</li>
 *   <li>Codex thread `019e0c28` (data_classification security bypass kaldırma)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class DataClassificationAcceptanceTest extends AbstractPostgresTest {

    @Autowired private IntentSubmissionService service;
    @Autowired private NotificationTemplateRepository templateRepo;
    @Autowired private NotificationIntentRepository intentRepo;
    @Autowired private AuditEventRepository auditRepo;

    @BeforeEach
    void seedTemplate() {
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

    // ================================================================
    // 4 enum matrix — each classification accepts + persists + audits
    // ================================================================

    @Test
    @DisplayName("transactional classification → accept + intent persist + audit data_classification=transactional")
    void transactionalClassificationAccepted() {
        runAcceptanceMatrix(DataClassification.transactional, NotificationIntent.Severity.info);
    }

    @Test
    @DisplayName("security classification → accept + intent persist + audit data_classification=security")
    void securityClassificationAccepted() {
        runAcceptanceMatrix(DataClassification.security, NotificationIntent.Severity.info);
    }

    @Test
    @DisplayName("commercial classification → accept + intent persist + audit data_classification=commercial")
    void commercialClassificationAccepted() {
        runAcceptanceMatrix(DataClassification.commercial, NotificationIntent.Severity.info);
    }

    @Test
    @DisplayName("system classification → accept + intent persist + audit data_classification=system")
    void systemClassificationAccepted() {
        runAcceptanceMatrix(DataClassification.system, NotificationIntent.Severity.info);
    }

    // ================================================================
    // Severity x Classification matrix (critical bypass)
    // ================================================================

    @Test
    @DisplayName("critical severity x security classification → audit data_classification=security severity=critical")
    void criticalSecurityCombination() {
        runAcceptanceMatrix(DataClassification.security, NotificationIntent.Severity.critical);
    }

    @Test
    @DisplayName("critical severity x commercial classification → audit reflects both fields")
    void criticalCommercialCombination() {
        runAcceptanceMatrix(DataClassification.commercial, NotificationIntent.Severity.critical);
    }

    // ================================================================
    // Round-trip integrity: enum → DB → enum
    // ================================================================

    @Test
    @DisplayName("enum round-trip: persist → fetch → enum value match (4-classification)")
    void enumRoundTripAllValues() {
        for (DataClassification cls : DataClassification.values()) {
            String intentId = UUID.randomUUID().toString();
            SubmitIntentRequest req = buildRequest(intentId, "rt-" + cls.name(), cls,
                NotificationIntent.Severity.info);
            SubmitIntentResponse resp = service.submit(req);

            assertThat(resp.status())
                .as("classification %s must be accepted", cls.name())
                .isEqualTo("ACCEPTED");

            NotificationIntent fetched = intentRepo.findByIntentId(intentId).orElseThrow();
            assertThat(fetched.getDataClassification())
                .as("DB persistence preserves enum value: %s", cls.name())
                .isEqualTo(cls);
        }
    }

    // ================================================================
    // PiiRedactor whitelist — data_classification allowed in audit details
    // ================================================================

    @Test
    @DisplayName("PiiRedactor whitelist: data_classification field surfaces in INTENT_CREATED audit details")
    void piiRedactorAllowsDataClassificationField() {
        String intentId = UUID.randomUUID().toString();
        SubmitIntentRequest req = buildRequest(intentId, "pii-key", DataClassification.security,
            NotificationIntent.Severity.info);
        service.submit(req);

        List<AuditEvent> audits = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(req.correlationId());
        assertThat(audits).hasSize(1);
        AuditEvent intentCreated = audits.get(0);
        assertThat(intentCreated.getEventType()).isEqualTo("INTENT_CREATED");

        // Codex iter (019e1307) P1 absorb: PiiRedactor whitelist boundary assert.
        // PiiRedactor.java:59 whitelist allows "data_classification" key; payload values
        // (user_email, user_name) NOT in whitelist → redacted out.
        Map<String, Object> details = intentCreated.getDetails();
        assertThat(details)
            .as("PiiRedactor whitelist: data_classification surfaces in audit details")
            .containsEntry("data_classification", DataClassification.security.name());
        assertThat(details)
            .as("audit details contain template_id + recipient_hash (whitelist allow)")
            .containsKeys("template_id", "recipient_hash");
        assertThat(details)
            .as("PiiRedactor whitelist: payload PII (user_email/user_name) redacted out")
            .doesNotContainKeys("user_email", "user_name");

        NotificationIntent fetched = intentRepo.findByIntentId(intentId).orElseThrow();
        assertThat(fetched.getDataClassification()).isEqualTo(DataClassification.security);
    }

    // ================================================================
    // Codex iter (019e1307) P2 absorb: warning severity coverage
    // ================================================================

    @Test
    @DisplayName("warning severity x system classification → audit reflects both fields")
    void warningSystemCombination() {
        runAcceptanceMatrix(DataClassification.system, NotificationIntent.Severity.warning);
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private void runAcceptanceMatrix(DataClassification classification,
                                      NotificationIntent.Severity severity) {
        String intentId = UUID.randomUUID().toString();
        String idemKey = "acc-" + classification.name() + "-" + severity.name();
        SubmitIntentRequest req = buildRequest(intentId, idemKey, classification, severity);

        // Accept gate
        SubmitIntentResponse resp = service.submit(req);
        assertThat(resp.status())
            .as("data_classification=%s severity=%s must be accepted", classification, severity)
            .isEqualTo("ACCEPTED");
        assertThat(resp.intentId()).isEqualTo(intentId);

        // Intent persistence with classification preserved
        NotificationIntent intent = intentRepo.findByIntentId(intentId).orElseThrow();
        assertThat(intent.getDataClassification())
            .as("intent.data_classification persists as %s", classification)
            .isEqualTo(classification);
        assertThat(intent.getSeverity())
            .as("intent.severity persists as %s", severity)
            .isEqualTo(severity);
        assertThat(intent.getStatus()).isEqualTo(NotificationIntent.Status.PENDING);

        // Audit row INSERT
        List<AuditEvent> audits = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(req.correlationId());
        assertThat(audits)
            .as("INTENT_CREATED audit row written for %s/%s", classification, severity)
            .hasSize(1);
        AuditEvent audit = audits.get(0);
        assertThat(audit.getEventType()).isEqualTo("INTENT_CREATED");

        // Codex iter (019e1307) P1 absorb: audit details serialization assert.
        // AuditEventPublisher.java:56-57 raw details puts severity + data_classification;
        // PiiRedactor.java:59 whitelist allow data_classification + severity (line 60).
        // Acceptance gate: classification name + severity name surface in audit details.
        Map<String, Object> details = audit.getDetails();
        assertThat(details)
            .as("audit details contain data_classification=%s for %s/%s",
                classification.name(), classification, severity)
            .containsEntry("data_classification", classification.name());
        assertThat(details)
            .as("audit details contain severity=%s for %s/%s",
                severity.name(), classification, severity)
            .containsEntry("severity", severity.name());
    }

    private SubmitIntentRequest buildRequest(String intentId, String idemKey,
                                              DataClassification classification,
                                              NotificationIntent.Severity severity) {
        return new SubmitIntentRequest(
            intentId,
            idemKey,
            "trace-cls-" + intentId.substring(0, 8),
            "default",
            "auth.password-reset",
            severity,
            classification,
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
