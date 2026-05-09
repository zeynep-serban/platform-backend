package com.serban.notify.erasure;

import com.serban.notify.api.dto.AuditHistoryListResponse;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubscriberErasureService unit test (Faz 23.2.B M3 stale audit 2026-05-09 —
 * Codex thread {@code 019e0c28} subscriber self-service path).
 *
 * <p>5 minimum tests cover:
 * <ul>
 *   <li>listMyAudit empty list → totalElements=0, items empty</li>
 *   <li>listMyAudit page size clamp (>100 → 100; 0 → 1)</li>
 *   <li>listMyAudit newest-first ordering by createdAt DESC</li>
 *   <li>eraseMyAudit reuses ErasureService with self-service evidence_ref</li>
 *   <li>eraseMyAudit defaults reason to evidence_ref when null</li>
 * </ul>
 */
class SubscriberErasureServiceTest {

    private ErasureService erasureService;
    private NotificationIntentRepository intentRepo;
    private SubscriberErasureService service;

    @BeforeEach
    void setUp() {
        erasureService = mock(ErasureService.class);
        intentRepo = mock(NotificationIntentRepository.class);
        service = new SubscriberErasureService(erasureService, intentRepo);
    }

    @Test
    void listMyAuditReturnsEmptyWhenNoMatch() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());

        AuditHistoryListResponse response = service.listMyAudit("acme", "1204", 0, 20);

        assertThat(response.totalElements()).isEqualTo(0);
        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
    }

    @Test
    void listMyAuditClampsPageSizeAboveMaximum() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());

        AuditHistoryListResponse response = service.listMyAudit("acme", "1204", 0, 999);

        assertThat(response.size()).isEqualTo(100);
    }

    @Test
    void listMyAuditClampsPageSizeBelowMinimum() {
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(List.of());

        AuditHistoryListResponse response = service.listMyAudit("acme", "1204", 0, 0);

        assertThat(response.size()).isEqualTo(1);
    }

    @Test
    void listMyAuditOrdersByCreatedAtDesc() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-01T10:00:00Z");
        OffsetDateTime t1 = OffsetDateTime.parse("2026-05-05T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-05-09T10:00:00Z");

        NotificationIntent older = newIntent("intent-old", t0);
        NotificationIntent middle = newIntent("intent-mid", t1);
        NotificationIntent newer = newIntent("intent-new", t2);
        // mutable list (subList'in çağrı yapacağı sort için)
        List<NotificationIntent> all = new ArrayList<>(List.of(older, middle, newer));
        when(intentRepo.findIntentsBySubscriber("acme", "1204")).thenReturn(all);

        AuditHistoryListResponse response = service.listMyAudit("acme", "1204", 0, 10);

        assertThat(response.items()).hasSize(3);
        assertThat(response.items().get(0).intentId()).isEqualTo("intent-new");
        assertThat(response.items().get(1).intentId()).isEqualTo("intent-mid");
        assertThat(response.items().get(2).intentId()).isEqualTo("intent-old");
    }

    @Test
    void eraseMyAuditReusesAdminWithSelfServiceEventTypeAndEvidenceRef() {
        when(erasureService.eraseSubscriber(any(), eq(ErasureService.EVENT_SELF_SERVICE_ERASURE)))
            .thenReturn(new ErasureService.EraseResult(2, 5, 0));

        ErasureService.EraseResult result = service.eraseMyAudit("acme", "1204");

        ArgumentCaptor<ErasureService.EraseRequest> captor =
            ArgumentCaptor.forClass(ErasureService.EraseRequest.class);
        verify(erasureService).eraseSubscriber(captor.capture(),
            eq(ErasureService.EVENT_SELF_SERVICE_ERASURE));

        ErasureService.EraseRequest captured = captor.getValue();
        assertThat(captured.orgId()).isEqualTo("acme");
        assertThat(captured.subscriberId()).isEqualTo("1204");
        // Codex P1 absorb: reason ve evidence_ref ikisi de sabit; user-provided
        // free-form metin yok → PII leakage riski elimine.
        assertThat(captured.reason()).isEqualTo(SubscriberErasureService.SELF_SERVICE_EVIDENCE_REF);
        assertThat(captured.evidenceRef()).isEqualTo(SubscriberErasureService.SELF_SERVICE_EVIDENCE_REF);
        assertThat(result.intentsErased()).isEqualTo(2);
        assertThat(result.deliveriesAnonymized()).isEqualTo(5);
    }

    @Test
    void eraseMyAuditAlwaysUsesSelfServiceEventTypeNotAdminEvent() {
        when(erasureService.eraseSubscriber(any(), any()))
            .thenReturn(new ErasureService.EraseResult(0, 0, 0));

        service.eraseMyAudit("acme", "1204");

        // Audit reporting netliği: admin scope EVENT_ADMIN_ERASURE'dan ayrı
        verify(erasureService).eraseSubscriber(any(),
            eq(ErasureService.EVENT_SELF_SERVICE_ERASURE));
    }

    private NotificationIntent newIntent(String intentId, OffsetDateTime createdAt) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(intentId);
        intent.setCorrelationId("corr-" + intentId);
        intent.setOrgId("acme");
        intent.setTopicKey("topic.test");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setTemplateId("test-template");
        intent.setTemplateVersion(1);
        intent.setLocale("tr");
        intent.setChannels(new String[]{"email"});
        intent.setStatus(NotificationIntent.Status.COMPLETED);
        // @PrePersist sets createdAt; bypass via reflection for unit test
        try {
            Field createdAtField = NotificationIntent.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(intent, createdAt);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("createdAt reflection failed", e);
        }
        return intent;
    }
}
