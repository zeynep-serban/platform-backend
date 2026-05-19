package com.serban.notify.dlr;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.worker.IntentStatusResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DlrIngestService unit test (Faz 23.4 PR-F — comprehensive rewrite after
 * Codex iter-1 RED absorb).
 *
 * <p>Test scope (post-rewrite):
 * <ul>
 *   <li>Code 00 + ACCEPTED row → atomic dlrTerminalize → UPDATED + intent recompute</li>
 *   <li>Codes 04/05/16/17/70 + ACCEPTED row → FAILED + intent recompute</li>
 *   <li>Code unknown + ACCEPTED row → NOOP + audit-only DELIVERY_DLR_RECEIVED</li>
 *   <li>Atomic UPDATE returns 0 (terminal-conflict) → NOOP + DELIVERY_DLR_TERMINAL_CONFLICT audit</li>
 *   <li>provider_msg_id not found → NOT_FOUND + warn log + no audit</li>
 *   <li>Idempotent: 2nd DLR after row terminalized → atomic returns 0 → NOOP audit</li>
 *   <li>delivered_at parse: ISO valid → passed; null/blank/malformed → null fallback</li>
 *   <li>Intent missing post-terminalize → audit + recompute skipped (defensive)</li>
 *   <li>Intent already-terminal → recompute skipped (no transition)</li>
 * </ul>
 */
class DlrIngestServiceTest {

    private NotificationDeliveryRepository deliveryRepo;
    private NotificationIntentRepository intentRepo;
    private AuditEventPublisher audit;
    private IntentStatusResolver intentStatusResolver;
    private DlrIngestService service;

    @BeforeEach
    void setUp() {
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        intentRepo = mock(NotificationIntentRepository.class);
        audit = mock(AuditEventPublisher.class);
        intentStatusResolver = mock(IntentStatusResolver.class);
        service = new DlrIngestService(deliveryRepo, intentRepo, audit, intentStatusResolver);
    }

    // ─── Happy path: code 00 ACCEPTED → DELIVERED ────────────────────────

    @Test
    void code00AtomicTerminalizationDelivered() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId("netgsm-abc-1"))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(eq("netgsm-abc-1"), eq("DELIVERED"), any(), eq(null)))
            .thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId("intent-1"))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.PROCESSING)));
        when(deliveryRepo.findByIntentId("intent-1"))
            .thenReturn(List.of(deliveredRow));
        when(intentStatusResolver.resolve(any()))
            .thenReturn(NotificationIntent.Status.COMPLETED);

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        verify(deliveryRepo).dlrTerminalize(eq("netgsm-abc-1"), eq("DELIVERED"), any(), eq(null));
        verify(audit).publishWithDelivery(
            eq("DELIVERY_DLR_RECEIVED"),
            any(NotificationIntent.class),
            any(NotificationDelivery.class),
            eq("sms"),
            any()
        );
    }

    // ─── Permanent failure codes ─────────────────────────────────────────

    @Test
    void code04AtomicFailsCarrierReject() {
        verifyPermanentFailureCode("04");
    }

    @Test
    void code05AtomicFailsUndeliverable() {
        verifyPermanentFailureCode("05");
    }

    @Test
    void code16AtomicFailsExpired() {
        verifyPermanentFailureCode("16");
    }

    @Test
    void code17AtomicFailsIysOptOut() {
        verifyPermanentFailureCode("17");
    }

    @Test
    void code70AtomicFailsIysOptOutVariant() {
        verifyPermanentFailureCode("70");
    }

    private void verifyPermanentFailureCode(String code) {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery failedRow = stubDelivery(NotificationDelivery.Status.FAILED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(eq("netgsm-abc-1"), eq("FAILED"), any(),
                eq("dlr netgsm code=" + code))).thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(failedRow));
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.PROCESSING)));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of(failedRow));
        when(intentStatusResolver.resolve(any()))
            .thenReturn(NotificationIntent.Status.FAILED);

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", code, "reject", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        verify(deliveryRepo).dlrTerminalize(eq("netgsm-abc-1"), eq("FAILED"), any(),
            eq("dlr netgsm code=" + code));
    }

    // ─── Transient/unknown codes ─────────────────────────────────────────

    @Test
    void unknownCodeNoOpsButAuditEmits() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.PROCESSING)));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "99", "?", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.ACCEPTED);
        verify(deliveryRepo, never()).dlrTerminalize(anyString(), anyString(), any(), any());
        // Audit emitted for compliance trail (transient acked)
        verify(audit).publishWithDelivery(eq("DELIVERY_DLR_RECEIVED"), any(), any(), any(), any());
    }

    // ─── Terminal conflict (atomic returns 0) ────────────────────────────

    @Test
    void terminalConflictAtomicZeroReturnsNoopAndAuditConflict() {
        // Pre-state: row already DELIVERED (late duplicate DLR or different
        // pod won the race). Atomic UPDATE returns 0 because predicate
        // WHERE status='ACCEPTED' fails.
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(deliveredRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any()))
            .thenReturn(0);
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.COMPLETED)));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "16", "expired", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        verify(audit).publishWithDelivery(
            eq("DELIVERY_DLR_TERMINAL_CONFLICT"),
            any(NotificationIntent.class),
            any(NotificationDelivery.class),
            eq("sms"),
            any()
        );
        // Intent recompute NOT invoked (no mutation)
        verify(intentStatusResolver, never()).resolve(any());
    }

    @Test
    void duplicate00DlrOnAlreadyDeliveredRowConflict() {
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(deliveredRow));
        when(deliveryRepo.dlrTerminalize(anyString(), eq("DELIVERED"), any(), any()))
            .thenReturn(0);
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.COMPLETED)));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK retry", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        verify(audit).publishWithDelivery(eq("DELIVERY_DLR_TERMINAL_CONFLICT"), any(), any(), any(), any());
    }

    @Test
    void terminalConflictReFetchesCurrentStatusForAccurateAudit() {
        // Codex `019e3ff7` P2: multi-pod race — bu pod findFirstByProviderMsgId
        // ile ACCEPTED snapshot okudu; dlrTerminalize'dan ÖNCE paralel bir pod
        // row'u DELIVERED yaptı → affected=0. Conflict audit stale
        // "prior_status_accepted" BASMAMALI; re-fetch ile gerçek current status
        // (DELIVERED) raporlanmalı.
        NotificationDelivery staleAcceptedSnapshot =
            stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery currentDelivered =
            stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(staleAcceptedSnapshot));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any()))
            .thenReturn(0);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(currentDelivered));
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.COMPLETED)));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        // re-fetch sonrası gerçek current status — stale ACCEPTED snapshot DEĞİL
        assertThat(result.currentStatus())
            .isEqualTo(NotificationDelivery.Status.DELIVERED);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(audit).publishWithDelivery(eq("DELIVERY_DLR_TERMINAL_CONFLICT"),
            any(NotificationIntent.class), any(NotificationDelivery.class),
            eq("sms"), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().get("dlr_ignored_reason"))
            .isEqualTo("prior_status_delivered");
    }

    // ─── Not found ───────────────────────────────────────────────────────

    @Test
    void unknownProviderMsgIdReturnsNotFound() {
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.empty());

        DlrIngestService.DlrResult result = service.ingestNetgsm("ghost", "00", "OK", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOT_FOUND);
        assertThat(result.providerMsgId()).isEqualTo("netgsm-ghost");
        assertThat(result.currentStatus()).isNull();
        verify(deliveryRepo, never()).dlrTerminalize(any(), any(), any(), any());
        verifyNoInteractions(audit);
        verifyNoInteractions(intentStatusResolver);
    }

    // ─── Intent recompute ────────────────────────────────────────────────

    @Test
    void intentRecomputeAppliesNewStatusOnTerminalTransition() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        NotificationIntent intent = stubIntent(NotificationIntent.Status.PROCESSING);

        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any()))
            .thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(intent));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of(deliveredRow));
        when(intentStatusResolver.resolve(any()))
            .thenReturn(NotificationIntent.Status.COMPLETED);

        service.ingestNetgsm("abc-1", "00", "OK", null);

        assertThat(intent.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);
        assertThat(intent.getTerminatedAt()).isNotNull();
        verify(intentRepo).save(intent);
    }

    @Test
    void intentRecomputeSkippedWhenAlreadyTerminal() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        // Intent already COMPLETED — recompute should not re-fire
        NotificationIntent intent = stubIntent(NotificationIntent.Status.COMPLETED);

        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any()))
            .thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(intent));

        service.ingestNetgsm("abc-1", "00", "OK", null);

        verify(intentStatusResolver, never()).resolve(any());
        verify(intentRepo, never()).save(intent);
    }

    @Test
    void intentRecomputeNoOpsWhenResolverReturnsNull() {
        // Resolver returns null (no terminal transition possible — other
        // deliveries still RETRY/PENDING/ACCEPTED)
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        NotificationIntent intent = stubIntent(NotificationIntent.Status.PROCESSING);

        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any())).thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(intent));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of(deliveredRow));
        when(intentStatusResolver.resolve(any())).thenReturn(null);

        service.ingestNetgsm("abc-1", "00", "OK", null);

        // No save (status didn't change)
        verify(intentRepo, never()).save(intent);
        assertThat(intent.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);
    }

    // ─── delivered_at parse ──────────────────────────────────────────────

    @Test
    void deliveredAtIsoValidPassedToRepo() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(),
                eq(java.time.OffsetDateTime.parse("2026-05-07T10:15:30Z")), any())).thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.PROCESSING)));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of(deliveredRow));

        service.ingestNetgsm("abc-1", "00", "OK", "2026-05-07T10:15:30Z");

        verify(deliveryRepo).dlrTerminalize(anyString(), anyString(),
            eq(java.time.OffsetDateTime.parse("2026-05-07T10:15:30Z")), any());
    }

    @Test
    void deliveredAtMalformedFallsBackToNull() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), eq(null), any())).thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString()))
            .thenReturn(Optional.of(stubIntent(NotificationIntent.Status.PROCESSING)));
        when(deliveryRepo.findByIntentId(anyString())).thenReturn(List.of(deliveredRow));

        service.ingestNetgsm("abc-1", "00", "OK", "not-a-date");

        verify(deliveryRepo).dlrTerminalize(anyString(), anyString(), eq(null), any());
    }

    // ─── Intent missing post-mutation ────────────────────────────────────

    @Test
    void intentMissingSkipsAuditButMutationStillApplied() {
        NotificationDelivery acceptedRow = stubDelivery(NotificationDelivery.Status.ACCEPTED);
        NotificationDelivery deliveredRow = stubDelivery(NotificationDelivery.Status.DELIVERED);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(acceptedRow));
        when(deliveryRepo.dlrTerminalize(anyString(), anyString(), any(), any())).thenReturn(1);
        when(deliveryRepo.findById(42L)).thenReturn(Optional.of(deliveredRow));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.empty());

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK", null);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        verifyNoInteractions(audit);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static NotificationDelivery stubDelivery(NotificationDelivery.Status status) {
        NotificationDelivery d = new NotificationDelivery();
        d.setId(42L);
        d.setIntentId("intent-1");
        d.setChannel("sms");
        d.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        d.setRecipientId("sub-1");
        d.setRecipientHash("hash-x");
        d.setProvider("netgsm");  // Faz 23.3: gerçek provider key (plan-time "sms" placeholder runtime'da actualProviderKey ile çözülür)
        d.setProviderMsgId("netgsm-abc-1");
        d.setStatus(status);
        return d;
    }

    private static NotificationIntent stubIntent(NotificationIntent.Status status) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("intent-1");
        i.setOrgId("default");
        i.setTopicKey("test.topic");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setDataClassification(NotificationIntent.DataClassification.transactional);
        i.setTemplateId("t");
        i.setTemplateVersion(1);
        i.setLocale("tr-TR");
        i.setStatus(status);
        return i;
    }
}
