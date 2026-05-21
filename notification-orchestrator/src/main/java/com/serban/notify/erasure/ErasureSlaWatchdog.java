package com.serban.notify.erasure;

import com.serban.notify.domain.ErasureRequestLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KVKK Madde 13.2 SLA breach watchdog (Faz 23.2 M3 R2 PR-K1 — Codex
 * {@code 019e4950} P0 #1 absorb).
 *
 * <p>{@code @Scheduled} tick erasure ledger'ı tarar; {@code due_at <=
 * NOW()} ve terminal değil (RECEIVED / PROCESSING / LEGAL_HOLD)
 * row'lar = KVKK Madde 13.2 30-gün SLA breach. Her breach için:
 *
 * <ul>
 *   <li>Micrometer counter increment ({@code notify_kvkk_erasure_sla_breach_total})</li>
 *   <li>Last-cycle gauge timestamp (Prometheus alert: 26h cycle freshness)</li>
 *   <li>WARN log (orgId + ledgerId + dueAt + status)</li>
 * </ul>
 *
 * <p>Direct Slack/PagerDuty wiring follow-up (R6 PR-K-alert); şu an
 * counter + log yeterli — Prometheus alert {@code NotifyKvkkErasureSlaBreach}
 * (sub-faz 23.2.B follow-up) bunu Slack #compliance kanalına route eder.
 *
 * <p>Cron: günde 4 kez (her 6 saatte bir) — KVKK denetim dakika
 * hassasiyeti gerektirmiyor, ama günde 1 tarama kaçırılan-saat
 * riski yaratır. 6 saatte bir = 4 örnek/gün = blast radius yeterli.
 *
 * <p>Test isolation: {@code notify.kvkk.sla-scheduling-enabled=false}
 * (default true) → tick no-op; manuel {@link #runCycle()} test'lerde
 * çağrılabilir.
 */
@Component
public class ErasureSlaWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ErasureSlaWatchdog.class);

    private final ErasureRequestLedgerService ledgerService;
    private final MeterRegistry meterRegistry;
    private final boolean schedulingEnabled;
    private final AtomicLong lastCycleTimestamp = new AtomicLong(0L);
    private final AtomicLong lastBreachCount = new AtomicLong(0L);

    public ErasureSlaWatchdog(
        ErasureRequestLedgerService ledgerService,
        MeterRegistry meterRegistry,
        @Value("${notify.kvkk.sla-scheduling-enabled:true}") boolean schedulingEnabled
    ) {
        this.ledgerService = ledgerService;
        this.meterRegistry = meterRegistry;
        this.schedulingEnabled = schedulingEnabled;

        // Gauge: last successful scan timestamp (Prometheus alert
        // detects stale watchdog if cycle stops firing)
        meterRegistry.gauge(
            "notify_kvkk_erasure_sla_last_scan_timestamp",
            lastCycleTimestamp, AtomicLong::get
        );
        meterRegistry.gauge(
            "notify_kvkk_erasure_sla_overdue_count",
            lastBreachCount, AtomicLong::get
        );
        log.info("ErasureSlaWatchdog activated: schedulingEnabled={} (KVKK Madde 13.2 30-day breach watchdog)",
            schedulingEnabled);
    }

    /**
     * Scheduled tick — cron-driven. Test isolation: scheduling
     * disabled, no-op; manuel {@link #runCycle()} çağrılır.
     *
     * <p>Cron pattern {@code 0 0 every-6-hours} → 00:00, 06:00, 12:00,
     * 18:00 UTC (override: {@code notify.kvkk.sla-scan-cron}).
     */
    @Scheduled(cron = "${notify.kvkk.sla-scan-cron:0 0 */6 * * *}", zone = "UTC")
    public void tick() {
        if (!schedulingEnabled) return;
        runCycle();
    }

    /**
     * Public entry — called by {@code @Scheduled} tick OR test fixtures.
     */
    public CycleResult runCycle() {
        OffsetDateTime cycleStart = OffsetDateTime.now();
        try {
            List<ErasureRequestLedger> overdue = ledgerService.findOverdueRequests();
            lastBreachCount.set(overdue.size());

            for (ErasureRequestLedger entry : overdue) {
                // WARN log per breach — Loki/Slack #compliance alert pipeline
                // KVKK Madde 12 (data minimization): orgId görünür, raw
                // subscriberId YASAK; subjectRefHmac pseudonymous.
                long overdueHours = java.time.Duration.between(
                    entry.getDueAt(), cycleStart
                ).toHours();
                log.warn(
                    "KVKK SLA BREACH: orgId={} ledgerId={} status={} subjectRefHmac={} "
                        + "dueAt={} overdueHours={} source={}",
                    entry.getOrgId(),
                    entry.getRequestId(),
                    entry.getStatus(),
                    entry.getSubjectRefHmac(),
                    entry.getDueAt(),
                    overdueHours,
                    entry.getRequestSource()
                );
                meterRegistry.counter(
                    "notify_kvkk_erasure_sla_breach_total",
                    "org_id", entry.getOrgId(),
                    "status", entry.getStatus().name(),
                    "source", entry.getRequestSource().name()
                ).increment();
            }

            lastCycleTimestamp.set(cycleStart.toEpochSecond());
            log.info("KVKK SLA watchdog cycle complete: overdueCount={} cycleStart={}",
                overdue.size(), cycleStart);
            return new CycleResult(overdue.size(), null);
        } catch (RuntimeException e) {
            log.warn("KVKK SLA watchdog cycle error: {}", e.getMessage(), e);
            meterRegistry.counter("notify_kvkk_erasure_sla_watchdog_errors_total").increment();
            return new CycleResult(0, e.getMessage());
        }
    }

    public long getLastCycleTimestamp() {
        return lastCycleTimestamp.get();
    }

    public long getLastBreachCount() {
        return lastBreachCount.get();
    }

    /**
     * Cycle result — test fixture'lar için.
     */
    public record CycleResult(int overdueCount, String errorMessage) {
        public boolean successful() { return errorMessage == null; }
    }
}
