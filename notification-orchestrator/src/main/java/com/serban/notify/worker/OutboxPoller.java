package com.serban.notify.worker;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.delivery.DeliveryDispatchService;
import com.serban.notify.delivery.DeliveryPlanService;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OutboxPoller — claim PENDING intents + dispatch (Codex 019dfa47 Q1+Q6 absorb).
 *
 * <p>Cycle:
 * <ol>
 *   <li>Lease recovery: revert stale-lease PROCESSING intents → PENDING</li>
 *   <li>Expire terminalize: PENDING/PROCESSING with expire_at &lt;= now → EXPIRED</li>
 *   <li>Atomic native claim: PENDING due intents → PROCESSING + lease set</li>
 *   <li>Plan + dispatch each claimed intent (REQUIRES_NEW per intent)</li>
 * </ol>
 *
 * <p>Activated only when {@code notify.dispatch.enabled=true} (PR3 contract:
 * default false → submit-only mode; PR4 enable for D29-NOTIFY deploy).
 */
@Component
@ConditionalOnProperty(name = "notify.dispatch.enabled", havingValue = "true")
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final NotificationIntentRepository intentRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final DeliveryPlanService planService;
    private final DeliveryDispatchService dispatchService;
    private final AuditEventPublisher audit;
    private final IntentStatusResolver statusResolver;
    private final WorkerMetrics metrics;
    private final NotifyConfig.WorkerConfig workerCfg;
    private final String podOwner;
    private final boolean schedulingEnabled;
    private OutboxPoller self;  // Self-injection for @Transactional proxy boundary

    @Autowired
    void setSelf(@Lazy OutboxPoller self) { this.self = self; }

    public OutboxPoller(
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        DeliveryPlanService planService,
        DeliveryDispatchService dispatchService,
        AuditEventPublisher audit,
        IntentStatusResolver statusResolver,
        WorkerMetrics metrics,
        NotifyConfig notifyConfig,
        @org.springframework.beans.factory.annotation.Value("${notify.worker.scheduling-enabled:true}")
            boolean schedulingEnabled
    ) {
        this.intentRepo = intentRepo;
        this.deliveryRepo = deliveryRepo;
        this.planService = planService;
        this.dispatchService = dispatchService;
        this.audit = audit;
        this.statusResolver = statusResolver;
        this.metrics = metrics;
        this.workerCfg = notifyConfig.worker();
        this.schedulingEnabled = schedulingEnabled;
        String configured = workerCfg.owner();
        this.podOwner = (configured == null || configured.isBlank())
            ? deriveOwner() : configured;
        log.info("OutboxPoller activated: owner={} batchSize={} pollDelay={}ms leaseDuration={}ms scheduling={}",
            podOwner, workerCfg.intentBatchSize(),
            workerCfg.pollDelayMs(), workerCfg.leaseDurationMs(), schedulingEnabled);
    }

    /**
     * Scheduled poll cycle. Default fixedDelay=5s; configurable via
     * {@code notify.worker.poll-delay-ms}. Each invocation is a separate
     * thread; @Transactional on individual inner methods.
     *
     * <p>Codex iter-1 absorb: scheduling guarded by {@code notify.worker.scheduling-enabled}
     * (default true) — tests can disable auto-tick via property.
     */
    @Scheduled(fixedDelayString = "${notify.worker.poll-delay-ms:5000}")
    public void tick() {
        if (!schedulingEnabled) return;
        runCycle();
    }

    /**
     * Public cycle entry point — called by @Scheduled tick() OR directly by
     * tests. No scheduling guard.
     */
    public void runCycle() {
        try {
            int recovered = self.recoverStaleLeases();
            int expired = self.expireTimedOut();
            int claimed = claimAndDispatch();
            metrics.cycle("intent",
                (claimed > 0 || recovered > 0 || expired > 0) ? "active" : "empty");
            if (claimed > 0 || recovered > 0 || expired > 0) {
                log.info("OutboxPoller cycle: recovered={} expired={} claimed={} owner={}",
                    recovered, expired, claimed, podOwner);
            }
        } catch (RuntimeException e) {
            log.warn("OutboxPoller cycle error: {}", e.getMessage(), e);
            metrics.error("intent", "cycle");
            metrics.cycle("intent", "error");
        }
    }

    /** Lease recovery: stale PROCESSING → PENDING. Self-invoked. */
    @Transactional
    public int recoverStaleLeases() {
        int n = intentRepo.recoverStaleLeases(OffsetDateTime.now());
        if (n > 0) log.info("OutboxPoller recovered {} stale-lease intents", n);
        return n;
    }

    /** Terminalize PENDING/PROCESSING intents whose expire_at passed → EXPIRED. */
    @Transactional
    public int expireTimedOut() {
        OffsetDateTime now = OffsetDateTime.now();
        List<NotificationIntent> expired = intentRepo.findExpired(
            now, PageRequest.of(0, workerCfg.intentBatchSize()));
        for (NotificationIntent intent : expired) {
            intent.setStatus(NotificationIntent.Status.EXPIRED);
            intent.setTerminatedAt(now);
            intent.setProcessingLeaseUntil(null);
            intent.setProcessingOwner(null);
            intent.setClaimToken(null);
            intentRepo.save(intent);
            audit.publish("INTENT_EXPIRED", intent, null, null,
                Map.of("expire_at", String.valueOf(intent.getExpireAt())));
            metrics.intentTerminated("EXPIRED", intent.getOrgId());
        }
        return expired.size();
    }

    /**
     * Atomic claim + dispatch loop.
     *
     * <p>Codex iter-1 absorb: claim_token UUID per cycle replaces
     * findByStatusAndProcessingOwner — multi-pod isolation guaranteed.
     */
    private int claimAndDispatch() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plus(Duration.ofMillis(workerCfg.leaseDurationMs()));
        String claimToken = UUID.randomUUID().toString();
        int claimed = self.claimAtomic(now, leaseUntil, claimToken);
        if (claimed == 0) return 0;
        metrics.claimed("intent", claimed);

        // Fetch ONLY this cycle's claims (Codex iter-1 P0 #2 absorb)
        List<NotificationIntent> claimedIntents = intentRepo.findByClaimToken(claimToken);
        for (NotificationIntent intent : claimedIntents) {
            try {
                dispatchClaimedIntent(intent);
            } catch (RuntimeException e) {
                log.warn("dispatch failed for intentId={}: {}",
                    intent.getIntentId(), e.getMessage(), e);
                metrics.error("intent", "dispatch");
            }
        }
        return claimed;
    }

    /** Atomic native claim — fresh transaction (own commit boundary). */
    @Transactional
    public int claimAtomic(OffsetDateTime now, OffsetDateTime leaseUntil, String claimToken) {
        return intentRepo.claimDueForProcessing(
            now, leaseUntil, podOwner, claimToken, workerCfg.intentBatchSize());
    }

    /**
     * Dispatch single claimed intent.
     *
     * <p>Codex iter-1 P0 #1 absorb: post-dispatch lease/owner/claim_token MUST
     * be cleared regardless of terminal vs RETRY-outstanding outcome:
     * <ul>
     *   <li>Terminal (COMPLETED/FAILED/PARTIALLY_FAILED) → status terminal,
     *       lease cleared, terminated_at set.</li>
     *   <li>RETRY outstanding (resolver returns null) → status STAYS PROCESSING
     *       but lease/owner/token cleared. recoverStaleLeases'in PROCESSING
     *       intent'i tekrar PENDING'e çevirip backoff'u delmesini engeller
     *       (lease IS NULL → not recovery-eligible). RetryWorker delivery'leri
     *       işler; tüm retry tükendiğinde DLQ → DeadLetterService intent
     *       terminal'e çevirir.</li>
     * </ul>
     */
    private void dispatchClaimedIntent(NotificationIntent intent) {
        OffsetDateTime start = OffsetDateTime.now();
        // Plan from snapshot (PR3 fallback path: recipients=null → use intent.snapshot)
        List<DeliveryTarget> targets = planService.plan(intent, null);
        dispatchService.dispatchPlanned(intent, targets);

        // Recompute terminal status
        var deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        NotificationIntent.Status terminal = statusResolver.resolve(deliveries);
        OffsetDateTime now = OffsetDateTime.now();

        // Codex iter-1 P0 #1 absorb: ALWAYS clear lease/owner/token after
        // dispatch attempt — RETRY outstanding case stays PROCESSING but with
        // NULL lease so recoverStaleLeases doesn't revert it (retry backoff
        // honored by RetryWorker).
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElse(intent);
        reloaded.setProcessingLeaseUntil(null);
        reloaded.setProcessingOwner(null);
        reloaded.setClaimToken(null);
        if (terminal != null) {
            // PR4 iter-4 absorb: cover case where DispatchService already set
            // status terminal but terminated_at not yet present (defensive
            // double-write race-free since save is last write wins).
            if (reloaded.getStatus() != terminal) {
                reloaded.setStatus(terminal);
                metrics.intentTerminated(terminal.name(), reloaded.getOrgId());
            }
            if (reloaded.getTerminatedAt() == null) {
                reloaded.setTerminatedAt(now);
            }
        }
        intentRepo.save(reloaded);

        Duration duration = Duration.between(start, OffsetDateTime.now());
        metrics.recordIntentDuration(duration);

        // Per-channel outcome metrics
        for (var d : deliveries) {
            metrics.dispatchOutcome(d.getChannel(), d.getStatus().name(), intent.getOrgId());
        }
    }

    private static String deriveOwner() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String pid = String.valueOf(ProcessHandle.current().pid());
            return hostname + "-" + pid;
        } catch (Exception e) {
            return "unknown-" + System.nanoTime();
        }
    }

    public String getOwner() { return podOwner; }
}
