package com.serban.notify.worker;

import com.serban.notify.adapter.sms.JetSmsProvider;
import com.serban.notify.adapter.sms.SmsDlrPollResult;
import com.serban.notify.dlr.DlrIngestService;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.NotificationDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JetSmsDlrPollingWorker unit test (Faz 23.3 PR-3) — mock repo/provider/ingest.
 *
 * <p>runCycle logic: claim → poll → terminal ingest (DELIVERED/FAILED) veya
 * pending reschedule + max-age timeout. Testcontainers gerekmez (repo mock'lu).
 */
@ExtendWith(MockitoExtension.class)
class JetSmsDlrPollingWorkerTest {

    @Mock NotificationDeliveryRepository deliveryRepo;
    @Mock JetSmsProvider jetSmsProvider;
    @Mock DlrIngestService dlrIngestService;

    private JetSmsDlrPollingWorker worker;

    @BeforeEach
    void setUp() {
        // schedulingEnabled=false — test runCycle'ı direkt çağırır; maxAge 72h.
        worker = new JetSmsDlrPollingWorker(
            deliveryRepo, jetSmsProvider, dlrIngestService,
            100, 60000L, 120000L, 72L, false);
        // Codex `019e3ff7` P1: @Transactional self-proxy boundary. Testte gerçek
        // Spring proxy yok — self=worker (transaction no-op; mock repo zaten
        // transaction-agnostik). Prod'da @Lazy proxy gerçek tx açar.
        worker.setSelf(worker);
    }

    private static NotificationDelivery delivery(long id, String providerMsgId,
                                                 OffsetDateTime createdAt) {
        NotificationDelivery d = new NotificationDelivery();
        d.setId(id);
        d.setIntentId("intent-" + id);
        d.setChannel("sms");
        d.setProvider("jetsms");
        d.setProviderMsgId(providerMsgId);
        d.setStatus(NotificationDelivery.Status.ACCEPTED);
        // createdAt setter yok (@PrePersist default) — test fixture için reflection.
        org.springframework.test.util.ReflectionTestUtils.setField(d, "createdAt", createdAt);
        return d;
    }

    @Test
    void noClaimedRowsReturnsZero() {
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(0);

        assertThat(worker.runCycle()).isZero();
        verify(jetSmsProvider, never()).pollDelivery(any());
    }

    @Test
    void deliveredResultIngestsTerminalDelivered() {
        NotificationDelivery d = delivery(1L, "jetsms-756", OffsetDateTime.now());
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(1);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(d));
        when(jetSmsProvider.pollDelivery(List.of("756")))
            .thenReturn(List.of(SmsDlrPollResult.delivered("756", "1")));

        int processed = worker.runCycle();

        assertThat(processed).isEqualTo(1);
        verify(dlrIngestService).ingestTerminal(
            eq("jetsms"), eq("jetsms-756"),
            eq(NotificationDelivery.Status.DELIVERED), eq("1"), any());
        verify(deliveryRepo, never()).rescheduleDlrPoll(anyLong(), any(), any());
        // Codex `019e3ff7` P2: terminal sonrası poll-state housekeeping
        verify(deliveryRepo).markJetsmsDlrPollTerminal(eq(1L), any());
    }

    @Test
    void failedResultIngestsTerminalFailed() {
        NotificationDelivery d = delivery(2L, "jetsms-900", OffsetDateTime.now());
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(1);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(d));
        when(jetSmsProvider.pollDelivery(List.of("900")))
            .thenReturn(List.of(SmsDlrPollResult.failed("900", "3")));

        worker.runCycle();

        verify(dlrIngestService).ingestTerminal(
            eq("jetsms"), eq("jetsms-900"),
            eq(NotificationDelivery.Status.FAILED), eq("3"), any());
        verify(deliveryRepo).markJetsmsDlrPollTerminal(eq(2L), any());
    }

    @Test
    void pendingResultWithinMaxAgeReschedules() {
        NotificationDelivery d = delivery(3L, "jetsms-111", OffsetDateTime.now());
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(1);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(d));
        when(jetSmsProvider.pollDelivery(List.of("111")))
            .thenReturn(List.of(SmsDlrPollResult.pending("111", "2")));

        worker.runCycle();

        // pending + maxAge içinde → reschedule, ingestTerminal YOK
        verify(deliveryRepo).rescheduleDlrPoll(eq(3L), any(), any());
        verify(dlrIngestService, never()).ingestTerminal(any(), any(), any(), any(), any());
        // pending → terminal housekeeping ÇAĞRILMAZ
        verify(deliveryRepo, never()).markJetsmsDlrPollTerminal(anyLong(), any());
    }

    @Test
    void pendingResultExceedingMaxAgeIngestsFailedTimeout() {
        // created_at 73h önce — maxAge 72h aşıldı → FAILED timeout
        NotificationDelivery d = delivery(4L, "jetsms-222",
            OffsetDateTime.now().minus(Duration.ofHours(73)));
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(1);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(d));
        when(jetSmsProvider.pollDelivery(List.of("222")))
            .thenReturn(List.of(SmsDlrPollResult.pending("222", "6")));

        worker.runCycle();

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(dlrIngestService).ingestTerminal(
            eq("jetsms"), eq("jetsms-222"),
            eq(NotificationDelivery.Status.FAILED), codeCaptor.capture(), any());
        assertThat(codeCaptor.getValue()).contains("timeout");
        verify(deliveryRepo, never()).rescheduleDlrPoll(anyLong(), any(), any());
        // max-age timeout da terminal → housekeeping çağrılır
        verify(deliveryRepo).markJetsmsDlrPollTerminal(eq(4L), any());
    }

    @Test
    void batchPollSingleApiCallForMultipleDeliveries() {
        NotificationDelivery d1 = delivery(10L, "jetsms-A", OffsetDateTime.now());
        NotificationDelivery d2 = delivery(11L, "jetsms-B", OffsetDateTime.now());
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(2);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(d1, d2));
        when(jetSmsProvider.pollDelivery(List.of("A", "B")))
            .thenReturn(List.of(
                SmsDlrPollResult.delivered("A", "1"),
                SmsDlrPollResult.failed("B", "9")));

        int processed = worker.runCycle();

        assertThat(processed).isEqualTo(2);
        // Tek pollDelivery çağrısı — batch (HttpSmsReport | separated)
        verify(jetSmsProvider, times(1)).pollDelivery(List.of("A", "B"));
        verify(dlrIngestService).ingestTerminal(eq("jetsms"), eq("jetsms-A"),
            eq(NotificationDelivery.Status.DELIVERED), any(), any());
        verify(dlrIngestService).ingestTerminal(eq("jetsms"), eq("jetsms-B"),
            eq(NotificationDelivery.Status.FAILED), any(), any());
        verify(deliveryRepo).markJetsmsDlrPollTerminal(eq(10L), any());
        verify(deliveryRepo).markJetsmsDlrPollTerminal(eq(11L), any());
    }

    @Test
    void prefixlessProviderMsgIdSkipped() {
        // Beklenmedik: jetsms claim ama providerMsgId prefix'siz → skip,
        // pollDelivery boş id listesiyle çağrılmaz.
        NotificationDelivery bad = delivery(20L, "malformed-id", OffsetDateTime.now());
        when(deliveryRepo.claimJetsmsDlrPollBatch(any(), any(), anyString(), anyInt()))
            .thenReturn(1);
        when(deliveryRepo.findByClaimToken(anyString())).thenReturn(List.of(bad));

        int processed = worker.runCycle();

        assertThat(processed).isZero();
        verify(jetSmsProvider, never()).pollDelivery(any());
    }
}
