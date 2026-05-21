package com.serban.notify.erasure;

import com.serban.notify.domain.ErasureRequestLedger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErasureSlaWatchdog unit test — Faz 23.2 M3 R2 PR-K1 (Codex
 * {@code 019e4950} P0 #1 absorb).
 *
 * <p>Covers:
 * <ul>
 *   <li>schedulingEnabled=false → tick no-op</li>
 *   <li>runCycle finds overdue + increments per-org counter</li>
 *   <li>runCycle updates last_cycle_timestamp gauge</li>
 *   <li>runCycle resilient — exception caught + error counter increment</li>
 * </ul>
 */
class ErasureSlaWatchdogTest {

    private ErasureRequestLedgerService ledgerService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        ledgerService = mock(ErasureRequestLedgerService.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void tickNoOpWhenSchedulingDisabled() {
        ErasureSlaWatchdog watchdog = new ErasureSlaWatchdog(
            ledgerService, meterRegistry, false
        );

        watchdog.tick();

        // ledger never called because tick exited early
        verify(ledgerService, times(0)).findOverdueRequests();
        assertThat(watchdog.getLastCycleTimestamp()).isZero();
    }

    @Test
    void runCycleScansAndIncrementsCounters() {
        ErasureRequestLedger overdue1 = makeOverdue(
            "acme",
            ErasureRequestLedger.Status.RECEIVED,
            ErasureRequestLedger.RequestSource.SELF_SERVICE,
            48L
        );
        ErasureRequestLedger overdue2 = makeOverdue(
            "beta",
            ErasureRequestLedger.Status.PROCESSING,
            ErasureRequestLedger.RequestSource.LEGAL,
            72L
        );
        when(ledgerService.findOverdueRequests()).thenReturn(List.of(overdue1, overdue2));

        ErasureSlaWatchdog watchdog = new ErasureSlaWatchdog(
            ledgerService, meterRegistry, true
        );

        ErasureSlaWatchdog.CycleResult result = watchdog.runCycle();

        assertThat(result.overdueCount()).isEqualTo(2);
        assertThat(result.successful()).isTrue();
        assertThat(watchdog.getLastBreachCount()).isEqualTo(2L);
        assertThat(watchdog.getLastCycleTimestamp()).isGreaterThan(0L);

        // Counters: one per (org_id, status, source) tag combo
        assertThat(meterRegistry.find("notify_kvkk_erasure_sla_breach_total")
            .tag("org_id", "acme")
            .tag("status", "RECEIVED")
            .tag("source", "SELF_SERVICE")
            .counter().count()
        ).isEqualTo(1.0);
        assertThat(meterRegistry.find("notify_kvkk_erasure_sla_breach_total")
            .tag("org_id", "beta")
            .tag("status", "PROCESSING")
            .tag("source", "LEGAL")
            .counter().count()
        ).isEqualTo(1.0);
    }

    @Test
    void runCycleNoOverdueZeroBreachCount() {
        when(ledgerService.findOverdueRequests()).thenReturn(List.of());

        ErasureSlaWatchdog watchdog = new ErasureSlaWatchdog(
            ledgerService, meterRegistry, true
        );

        ErasureSlaWatchdog.CycleResult result = watchdog.runCycle();

        assertThat(result.overdueCount()).isZero();
        assertThat(result.successful()).isTrue();
        assertThat(watchdog.getLastBreachCount()).isZero();
        // last_cycle_timestamp still incremented (clean cycle)
        assertThat(watchdog.getLastCycleTimestamp()).isGreaterThan(0L);
    }

    @Test
    void runCycleResilientToServiceError() {
        when(ledgerService.findOverdueRequests())
            .thenThrow(new RuntimeException("simulated DB outage"));

        ErasureSlaWatchdog watchdog = new ErasureSlaWatchdog(
            ledgerService, meterRegistry, true
        );

        ErasureSlaWatchdog.CycleResult result = watchdog.runCycle();

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("simulated DB outage");
        // Error counter incremented (Prometheus alert rule)
        assertThat(meterRegistry.find("notify_kvkk_erasure_sla_watchdog_errors_total")
            .counter().count()).isEqualTo(1.0);
        // last_cycle_timestamp NOT updated on error
        assertThat(watchdog.getLastCycleTimestamp()).isZero();
    }

    private ErasureRequestLedger makeOverdue(
        String orgId,
        ErasureRequestLedger.Status status,
        ErasureRequestLedger.RequestSource source,
        long overdueHours
    ) {
        ErasureRequestLedger e = new ErasureRequestLedger();
        e.setRequestId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setSubjectRefHmac("hmac-test-" + orgId);
        e.setStatus(status);
        e.setRequestSource(source);
        e.setReceivedAt(OffsetDateTime.now().minusDays(30L).minusHours(overdueHours));
        e.setDueAt(OffsetDateTime.now().minusHours(overdueHours));
        return e;
    }
}
