package com.serban.notify.worker;

import com.serban.notify.adapter.sms.JetSmsProvider;
import com.serban.notify.adapter.sms.SmsDlrPollResult;
import com.serban.notify.dlr.DlrIngestService;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JetSMS DLR polling worker — Faz 23.3 PR-3 (Codex `019e3f82` AGREE,
 * `019e3ff7` REVISE absorb).
 *
 * <p>NetGSM DLR'yi webhook PUSH ile bildirir ({@code DlrController}); JetSMS
 * DLR webhook GÖNDERMEZ — backend {@code HttpSmsReport} endpoint'ini periyodik
 * poll eder. Bu worker ACCEPTED durumdaki {@code provider='jetsms'} delivery
 * row'larını batch çeker, {@link JetSmsProvider#pollDelivery} ile tek API
 * çağrısında durum sorgular, terminal sonuçları generic
 * {@link DlrIngestService#ingestTerminal} core'una verir.
 *
 * <p><b>Multi-pod safe</b>: {@code claimJetsmsDlrPollBatch} CTE + FOR UPDATE
 * SKIP LOCKED + lease + claim_token (OutboxPoller/RetryWorker pattern).
 *
 * <p><b>Transaction boundary</b> (Codex `019e3ff7` P1 absorb): native
 * {@code @Modifying} claim/reschedule/terminal-housekeeping query'leri aktif
 * transaction gerektirir. {@code @Scheduled tick()} → {@link #runCycle()}
 * transaction-sız; bu yüzden her native UPDATE {@code @Transactional} public
 * method'a sarılır ({@link #claimAtomic} / {@link #rescheduleAtomic} /
 * {@link #markPollTerminalAtomic}) ve {@code self} proxy üzerinden çağrılır
 * (RetryWorker/OutboxPoller {@code claimAtomic} pattern'i).
 *
 * <p><b>Activation</b> (Codex `019e3ff7` P2 absorb): yalnız
 * {@code notify.dispatch.enabled=true} iken bean oluşur
 * ({@code @ConditionalOnProperty} — OutboxPoller/RetryWorker ile tutarlı).
 * Dispatch kapalıyken JetSMS dispatch de yok → poll edilecek ACCEPTED row yok.
 *
 * <p><b>Pending reschedule</b>: JetSMS hâlâ pending bildirdiğinde
 * ({@code MessageState 0/2/6/7/8}) {@code dlr_next_poll_at} ileri alınır
 * ({@code rescheduleDlrPoll}); her cycle aynı row'u tekrar tekrar poll etmez.
 *
 * <p><b>Max-age timeout</b>: {@code dlr-max-age-hours} (default 72h) boyunca
 * terminal olmayan delivery {@code FAILED} (failure_reason "dlr timeout after
 * Nh") — JetSMS {@code MessageState 4} (timeout) ile aynı terminal aileye düşer.
 */
@Component
@ConditionalOnProperty(name = "notify.dispatch.enabled", havingValue = "true")
public class JetSmsDlrPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(JetSmsDlrPollingWorker.class);
    private static final String JETSMS_PREFIX = "jetsms-";

    private final NotificationDeliveryRepository deliveryRepo;
    private final JetSmsProvider jetSmsProvider;
    private final DlrIngestService dlrIngestService;

    private final int batchSize;
    private final Duration pollInterval;
    private final Duration leaseDuration;
    private final Duration maxAge;
    private final boolean schedulingEnabled;

    /** Self-injection — {@code @Transactional} proxy boundary (RetryWorker pattern). */
    private JetSmsDlrPollingWorker self;

    public JetSmsDlrPollingWorker(
        NotificationDeliveryRepository deliveryRepo,
        JetSmsProvider jetSmsProvider,
        DlrIngestService dlrIngestService,
        @Value("${notify.adapters.sms.jetsms.dlr-batch-size:100}") int batchSize,
        @Value("${notify.adapters.sms.jetsms.dlr-poll-delay-ms:60000}") long pollDelayMs,
        @Value("${notify.adapters.sms.jetsms.dlr-lease-duration-ms:120000}") long leaseDurationMs,
        @Value("${notify.adapters.sms.jetsms.dlr-max-age-hours:72}") long maxAgeHours,
        @Value("${notify.adapters.sms.jetsms.dlr-scheduling-enabled:true}") boolean schedulingEnabled
    ) {
        this.deliveryRepo = deliveryRepo;
        this.jetSmsProvider = jetSmsProvider;
        this.dlrIngestService = dlrIngestService;
        this.batchSize = Math.max(batchSize, 1);
        this.pollInterval = Duration.ofMillis(pollDelayMs);
        this.leaseDuration = Duration.ofMillis(leaseDurationMs);
        this.maxAge = Duration.ofHours(maxAgeHours);
        this.schedulingEnabled = schedulingEnabled;
        log.info("JetSmsDlrPollingWorker activated: batchSize={} pollInterval={} "
                + "leaseDuration={} maxAge={} scheduling={}",
            this.batchSize, pollInterval, leaseDuration, maxAge, schedulingEnabled);
    }

    /**
     * Self-injection for {@code @Transactional} proxy boundary (Codex
     * `019e3ff7` P1). {@code @Lazy} — circular bean ref'i kırar.
     */
    @Autowired
    void setSelf(@Lazy JetSmsDlrPollingWorker self) {
        this.self = self;
    }

    /**
     * Scheduled poll cycle. Default fixedDelay 60s, initialDelay 30s
     * (config: {@code notify.adapters.sms.jetsms.dlr-poll-delay-ms} /
     * {@code dlr-initial-delay-ms}).
     */
    @Scheduled(
        fixedDelayString = "${notify.adapters.sms.jetsms.dlr-poll-delay-ms:60000}",
        initialDelayString = "${notify.adapters.sms.jetsms.dlr-initial-delay-ms:30000}")
    public void tick() {
        if (!schedulingEnabled) return;
        runCycle();
    }

    /**
     * Public cycle entry — {@code @Scheduled} tick() VEYA test direkt çağırır.
     *
     * <p>Transaction-sız orchestration: claim/reschedule/terminal-housekeeping
     * native UPDATE'leri {@code self.*Atomic()} {@code @Transactional} method'lar
     * üzerinden; her {@code ingestTerminal} kendi {@code @Transactional}.
     * Cycle-level + per-delivery hata izolasyonu (RetryWorker pattern): claim/
     * poll hatası ya da tek bir poison delivery batch'in geri kalanını bloklamaz.
     *
     * @return işlenen delivery sayısı
     */
    public int runCycle() {
        OffsetDateTime now = OffsetDateTime.now();
        String claimToken = UUID.randomUUID().toString();
        try {
            int claimed = self.claimAtomic(
                now, now.plus(leaseDuration), claimToken, batchSize);
            if (claimed == 0) {
                return 0;
            }

            List<NotificationDelivery> batch = deliveryRepo.findByClaimToken(claimToken);
            if (batch.isEmpty()) {
                return 0;
            }

            // raw JetSMS message id (prefix çıkarılmış) ↔ delivery eşleme.
            Map<String, NotificationDelivery> byRawId = new HashMap<>(batch.size());
            List<String> rawIds = new ArrayList<>(batch.size());
            for (NotificationDelivery d : batch) {
                String raw = stripPrefix(d.getProviderMsgId());
                if (raw == null) {
                    // Beklenmedik: jetsms claim'lendi ama providerMsgId prefix'siz.
                    // Lease'i bırak, bir sonraki cycle yeniden değerlendirilir.
                    log.warn("jetsms DLR poll: delivery {} providerMsgId prefix-siz '{}' — skip",
                        d.getId(), d.getProviderMsgId());
                    continue;
                }
                byRawId.put(raw, d);
                rawIds.add(raw);
            }
            if (rawIds.isEmpty()) {
                return 0;
            }

            // Tek HttpSmsReport çağrısı — batch (| separated MessageIDs).
            List<SmsDlrPollResult> results = jetSmsProvider.pollDelivery(rawIds);

            int processed = 0;
            for (SmsDlrPollResult result : results) {
                NotificationDelivery d = byRawId.get(result.rawProviderMsgId());
                if (d == null) continue;
                try {
                    processOne(d, result, now);
                    processed++;
                } catch (RuntimeException e) {
                    // Tek delivery hatası batch'in geri kalanını bloklamaz.
                    log.warn("jetsms DLR poll: delivery {} işlenemedi: {}",
                        d.getId(), e.getMessage(), e);
                }
            }
            log.info("JetSmsDlrPollingWorker cycle: claimed={} polled={} processed={}",
                claimed, rawIds.size(), processed);
            return processed;
        } catch (RuntimeException e) {
            // Claim / pollDelivery / fetch hatası — claimed row lease'leri
            // dolunca bir sonraki cycle yeniden claim eder (orphan yok).
            // Scheduled cycle düşmez.
            log.warn("JetSmsDlrPollingWorker cycle error: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** Tek DLR poll sonucunu işle — terminal ingest veya reschedule. */
    private void processOne(NotificationDelivery delivery, SmsDlrPollResult result,
                            OffsetDateTime now) {
        String providerMsgId = delivery.getProviderMsgId();
        switch (result.deliveryStatus()) {
            case DELIVERED -> terminalize(delivery, providerMsgId,
                NotificationDelivery.Status.DELIVERED, result.providerStateCode(), now);
            case FAILED -> terminalize(delivery, providerMsgId,
                NotificationDelivery.Status.FAILED, result.providerStateCode(), now);
            case PENDING -> {
                // Max-age timeout: created_at + maxAge geçtiyse FAILED.
                if (delivery.getCreatedAt() != null
                    && delivery.getCreatedAt().plus(maxAge).isBefore(now)) {
                    log.warn("jetsms DLR poll max-age timeout: delivery_id={} created={} "
                            + "maxAge={} → FAILED", delivery.getId(),
                        delivery.getCreatedAt(), maxAge);
                    terminalize(delivery, providerMsgId,
                        NotificationDelivery.Status.FAILED,
                        "timeout-" + maxAge.toHours() + "h", now);
                } else {
                    // Hâlâ pending — bir sonraki poll'e reschedule.
                    self.rescheduleAtomic(delivery.getId(), now.plus(pollInterval), now);
                }
            }
        }
    }

    /**
     * Terminal DLR ingest + JetSMS poll-state housekeeping.
     *
     * <p>Generic {@link DlrIngestService#ingestTerminal} core status'u terminal
     * yapar (atomik {@code UPDATE WHERE status='ACCEPTED'}); ardından
     * {@code markJetsmsDlrPollTerminal} JetSMS-özel poll kolonlarını temizler —
     * generic core claim_token / dlr_* metadata'ya dokunmaz (Codex `019e3ff7`
     * P2). Housekeeping {@code ingestTerminal} sonrası ayrı tx; başarısız olsa
     * bile row terminal kalır (claim query {@code status='ACCEPTED'} → re-claim
     * yok), yalnız claim_token stale kalır — fonksiyonel risk yok.
     */
    private void terminalize(NotificationDelivery delivery, String providerMsgId,
                             NotificationDelivery.Status status, String code,
                             OffsetDateTime now) {
        dlrIngestService.ingestTerminal(
            JetSmsProvider.PROVIDER_KEY, providerMsgId, status, code, now);
        self.markPollTerminalAtomic(delivery.getId(), now);
    }

    // ─── @Transactional proxy boundary (Codex `019e3ff7` P1) ─────────────────

    /**
     * Atomic batch claim — kendi transaction. {@code claimJetsmsDlrPollBatch}
     * {@code @Modifying} native CTE UPDATE aktif transaction gerektirir;
     * {@code self} proxy üzerinden çağrılınca {@code @Transactional} devreye girer.
     */
    @Transactional
    public int claimAtomic(OffsetDateTime now, OffsetDateTime leaseUntil,
                           String claimToken, int limit) {
        return deliveryRepo.claimJetsmsDlrPollBatch(now, leaseUntil, claimToken, limit);
    }

    /** Pending reschedule — kendi transaction ({@code @Modifying} native UPDATE). */
    @Transactional
    public void rescheduleAtomic(Long deliveryId, OffsetDateTime nextPollAt,
                                 OffsetDateTime now) {
        deliveryRepo.rescheduleDlrPoll(deliveryId, nextPollAt, now);
    }

    /** Terminal poll housekeeping — kendi transaction ({@code @Modifying} native UPDATE). */
    @Transactional
    public void markPollTerminalAtomic(Long deliveryId, OffsetDateTime pollAt) {
        deliveryRepo.markJetsmsDlrPollTerminal(deliveryId, pollAt);
    }

    /** {@code "jetsms-756"} → {@code "756"}; prefix yoksa null. */
    private static String stripPrefix(String providerMsgId) {
        if (providerMsgId == null || !providerMsgId.startsWith(JETSMS_PREFIX)) {
            return null;
        }
        return providerMsgId.substring(JETSMS_PREFIX.length());
    }
}
