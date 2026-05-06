package com.serban.notify.worker;

import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Worker observability metrics (Codex 019dfa47 Q7 PARTIAL absorb).
 *
 * <p>Counters/timers (low-cardinality tags only — no intent_id, recipient_hash):
 * <ul>
 *   <li>{@code notify.worker.cycles{worker, outcome}} — poll cycle counter</li>
 *   <li>{@code notify.worker.claimed{worker}} — claimed-row count per cycle</li>
 *   <li>{@code notify.dispatch.outcome{channel, status}} — dispatch result</li>
 *   <li>{@code notify.retry.scheduled{channel}} — retry scheduling</li>
 *   <li>{@code notify.dlq.terminated{reason}} — DLQ row insert</li>
 *   <li>{@code notify.intent.terminated{terminal}} — intent terminal transition</li>
 *   <li>{@code notify.worker.errors{worker, stage}} — error counter</li>
 *   <li>{@code notify.intent.processing.duration} — timer</li>
 * </ul>
 *
 * <p>Gauges:
 * <ul>
 *   <li>{@code notify.queue.pending.intents} — countByStatus(PENDING)</li>
 *   <li>{@code notify.queue.retry.due} — countByStatus(RETRY)</li>
 * </ul>
 */
@Component
public class WorkerMetrics {

    private final MeterRegistry registry;

    /**
     * Codex iter-1 P2 absorb: AtomicInteger strong reference (Micrometer
     * gauge target weak reference — GC sonrası NaN olur). Field olarak tutuldu.
     */
    private final java.util.concurrent.atomic.AtomicInteger authzDisabledStateRef;

    public WorkerMetrics(
        MeterRegistry registry,
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        com.serban.notify.repository.DeadLetterRepository dlqRepo,
        @org.springframework.beans.factory.annotation.Value("${notify.authz.enabled:true}")
            boolean authzEnabled
    ) {
        this.registry = registry;
        // Gauges — registered once at construction (Codex 019dfae5 PR-B Q4 AGREE)
        registry.gauge("notify.queue.pending.intents", intentRepo,
            r -> r.countByStatus(com.serban.notify.domain.NotificationIntent.Status.PENDING));
        registry.gauge("notify.queue.retry.due", deliveryRepo,
            r -> r.countByStatus(com.serban.notify.domain.NotificationDelivery.Status.RETRY));
        // Codex Q6 absorb: authz-disabled state gauge (1=disabled, 0=enabled)
        // Alert condition: notify.authz.disabled.state > 0 → CRITICAL (security regression)
        // iter-1 P2: strong reference field (Micrometer weak ref → GC NaN risk)
        this.authzDisabledStateRef = new java.util.concurrent.atomic.AtomicInteger(authzEnabled ? 0 : 1);
        registry.gauge("notify.authz.disabled.state", authzDisabledStateRef,
            java.util.concurrent.atomic.AtomicInteger::get);
        // Codex Q4 PR-B: DLQ unreplayed total (low cardinality; channel tag follow-up)
        registry.gauge("notify.dlq.unreplayed", dlqRepo,
            r -> r.countByReplayedFalse());
    }

    /**
     * Authz bypass counter (Codex 019dfae5 Q6 absorb).
     *
     * <p>Increment'lenir when DeliveryEligibilityService skips authz guard
     * (subscriber/external path; channel target hariç). Production gauge
     * notify.authz.disabled.bypass alert basis.
     */
    public void authzBypass(String channel) {
        Counter.builder("notify.authz.disabled.bypass")
            .tags(Tags.of("channel", channel))
            .register(registry).increment();
    }

    public void cycle(String worker, String outcome) {
        Counter.builder("notify.worker.cycles")
            .tags(Tags.of("worker", worker, "outcome", outcome))
            .register(registry).increment();
    }

    public void claimed(String worker, int amount) {
        Counter.builder("notify.worker.claimed")
            .tags(Tags.of("worker", worker))
            .register(registry).increment(amount);
    }

    public void dispatchOutcome(String channel, String status) {
        Counter.builder("notify.dispatch.outcome")
            .tags(Tags.of("channel", channel, "status", status))
            .register(registry).increment();
    }

    public void retryScheduled(String channel) {
        Counter.builder("notify.retry.scheduled")
            .tags(Tags.of("channel", channel))
            .register(registry).increment();
    }

    public void dlqTerminated(String reason) {
        Counter.builder("notify.dlq.terminated")
            .tags(Tags.of("reason", reason))
            .register(registry).increment();
    }

    public void intentTerminated(String terminal) {
        Counter.builder("notify.intent.terminated")
            .tags(Tags.of("terminal", terminal))
            .register(registry).increment();
    }

    public void error(String worker, String stage) {
        Counter.builder("notify.worker.errors")
            .tags(Tags.of("worker", worker, "stage", stage))
            .register(registry).increment();
    }

    public void recordIntentDuration(Duration duration) {
        Timer.builder("notify.intent.processing.duration")
            .register(registry).record(duration);
    }
}
