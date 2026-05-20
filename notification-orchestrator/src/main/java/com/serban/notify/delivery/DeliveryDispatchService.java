package com.serban.notify.delivery;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import com.serban.notify.eligibility.DeliveryEligibilityService;
import com.serban.notify.template.RenderedMessage;
import com.serban.notify.template.TemplateRenderer;
import com.serban.notify.unsubscribe.UnsubscribeFooterAppender;
import com.serban.notify.worker.BackoffCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DeliveryDispatchService — internal, direct-invoke (Codex 019df9ae Q1 REVISE absorb).
 *
 * <p>PR3 scope: tüm dispatch pipeline impl, AMA:
 * <ul>
 *   <li>Submit endpoint auto-dispatch çağırmaz (PR2 contract korunur)</li>
 *   <li>Scheduled worker yok (PR4)</li>
 *   <li>Test'lerde direct invoke; PR4 worker {@link #dispatchPlanned(NotificationIntent, List)}
 *       çağıracak</li>
 * </ul>
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Resolve template body (DB lookup by template_id+version+locale snapshot
 *       on intent)</li>
 *   <li>TemplateRenderer.render → RenderedMessage</li>
 *   <li>For each DeliveryTarget: ChannelAdapter.send → DeliveryAttemptResult</li>
 *   <li>Persist delivery row + audit DELIVERY_SUCCEEDED/FAILED</li>
 *   <li>Update intent status (PROCESSING → COMPLETED if all delivered)</li>
 * </ol>
 */
@Service
public class DeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatchService.class);

    private final TemplateRenderer renderer;
    private final NotificationTemplateRepository templateRepo;
    private final NotificationIntentRepository intentRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final ChannelAdapterRegistry adapterRegistry;
    private final AuditEventPublisher audit;
    private final BackoffCalculator backoffCalculator;
    private final DeliveryEligibilityService eligibilityService;
    private final UnsubscribeFooterAppender footerAppender;
    private DeliveryDispatchService self;  // Self-injection for REQUIRES_NEW boundary

    public DeliveryDispatchService(
        TemplateRenderer renderer,
        NotificationTemplateRepository templateRepo,
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        ChannelAdapterRegistry adapterRegistry,
        AuditEventPublisher audit,
        BackoffCalculator backoffCalculator,
        DeliveryEligibilityService eligibilityService,
        UnsubscribeFooterAppender footerAppender
    ) {
        this.renderer = renderer;
        this.templateRepo = templateRepo;
        this.intentRepo = intentRepo;
        this.deliveryRepo = deliveryRepo;
        this.adapterRegistry = adapterRegistry;
        this.audit = audit;
        this.backoffCalculator = backoffCalculator;
        this.eligibilityService = eligibilityService;
        this.footerAppender = footerAppender;
    }

    /**
     * Self-injection for {@code REQUIRES_NEW} per-target boundary (Codex
     * 019df9ef P2 absorb). Spring proxy {@code this.dispatchTarget(...)} direct
     * call bypasses transactional advice; via {@code self} bean reference the
     * proxy is invoked → REQUIRES_NEW takes effect per target.
     */
    @Autowired
    void setSelf(@Lazy DeliveryDispatchService self) {
        this.self = self;
    }

    /**
     * Dispatch planned targets for an intent.
     *
     * @param intent persisted NotificationIntent (status=PENDING)
     * @param targets DeliveryTargets from {@link DeliveryPlanService#plan}
     * @return number of targets attempted
     */
    @Transactional
    public int dispatchPlanned(NotificationIntent intent, List<DeliveryTarget> targets) {
        log.info("dispatch start: intentId={} target_count={}", intent.getIntentId(), targets.size());

        // Mark intent PROCESSING (idempotent — if already PROCESSING, retry path)
        if (intent.getStatus() == NotificationIntent.Status.PENDING) {
            intent.setStatus(NotificationIntent.Status.PROCESSING);
            intentRepo.save(intent);
        }

        NotificationTemplate template = templateRepo.findByTemplateIdAndVersionAndLocale(
            intent.getTemplateId(), intent.getTemplateVersion(), intent.getLocale()
        ).orElseThrow(() -> new IllegalStateException(
            "template missing at dispatch time: " + intent.getTemplateId()
                + " v" + intent.getTemplateVersion() + " " + intent.getLocale()
        ));

        RenderedMessage message = renderer.render(template, intent.getPayload());

        int attempted = 0;
        boolean anyFailedPermanent = false;
        boolean allDelivered = true;
        for (DeliveryTarget target : targets) {
            // Codex 019df9ef P2 absorb (iter-2): per-target idempotent skip —
            // ONLY if existing row is DELIVERED. RETRY / FAILED / BOUNCED rows
            // re-attempt path goes via UPSERT in dispatchSingleTarget (UPDATE
            // existing row, not INSERT — unique constraint preserved).
            Optional<NotificationDelivery> existing = deliveryRepo
                .findByIntentIdAndChannelAndRecipientHash(
                    intent.getIntentId(), target.channel(), target.recipientHash()
                );
            if (existing.isPresent() &&
                existing.get().getStatus() == NotificationDelivery.Status.DELIVERED) {
                log.info("dispatch skip (already delivered): intentId={} channel={} hash={}",
                    intent.getIntentId(), target.channel(), target.recipientHash());
                attempted++;
                continue;
            }

            // Codex 019dfaaa PR5 absorb: eligibility guard chain BEFORE adapter
            // call (external policy → preference → authz). Blocked targets get
            // BLOCKED_* delivery row + DELIVERY_BLOCKED audit; adapter NOT called.
            DeliveryEligibilityService.EligibilityDecision eligibility =
                eligibilityService.evaluate(intent, template, target);
            if (eligibility.blocked()) {
                self.persistBlockedDelivery(intent, target, eligibility);
                attempted++;
                anyFailedPermanent = true;
                allDelivered = false;
                continue;
            }

            // Codex 019df9ef P2 absorb: per-target REQUIRES_NEW transaction
            // boundary — provider call + delivery row + audit are atomic per
            // target; one target failure does NOT roll back earlier successful
            // sends. Provider-side dedup remains receiver responsibility.
            //
            // iter-4 absorb: catch unique-violation boundary OUTSIDE the inner
            // REQUIRES_NEW transaction. Inside the inner txn, PG marks txn
            // rollback-only on constraint violation; catch within is unsafe
            // because commit phase still throws UnexpectedRollbackException.
            // Outer catch (here) sees the rollback exception and maps to RETRY
            // (PR4 worker re-attempts; row already inserted by concurrent pod
            // is the source-of-truth).
            DispatchOutcome outcome;
            try {
                outcome = self.dispatchSingleTarget(intent, target, message);
            } catch (org.springframework.dao.DataIntegrityViolationException
                   | org.springframework.transaction.UnexpectedRollbackException
                   | org.springframework.transaction.TransactionSystemException e) {
                log.warn("dispatch concurrent insert race (outer catch): "
                    + "intentId={} channel={} hash={} cls={} — returning RETRY",
                    intent.getIntentId(), target.channel(), target.recipientHash(),
                    e.getClass().getSimpleName());
                outcome = new DispatchOutcome(
                    ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
            }
            attempted++;

            switch (outcome.status) {
                case DELIVERED -> { /* ok */ }
                case ACCEPTED -> {
                    // Faz 23.4 PR-F: provider queued, awaiting DLR.
                    // Intent stays PROCESSING (DLR will terminalize via
                    // DlrIngestService → IntentStatusResolver).
                    allDelivered = false;
                }
                case FAILED, BOUNCED -> { anyFailedPermanent = true; allDelivered = false; }
                case RETRY -> allDelivered = false;
            }
        }

        // Update intent status: COMPLETED iff all delivered
        // PR4 iter-4: terminated_at set together with terminal status (otherwise
        // OutboxPoller's later check sees status already terminal → skips
        // terminated_at set → assertion fails)
        if (allDelivered && !targets.isEmpty()) {
            intent.setStatus(NotificationIntent.Status.COMPLETED);
            intent.setTerminatedAt(OffsetDateTime.now());
            intentRepo.save(intent);
        } else if (anyFailedPermanent) {
            // PR3: keep PROCESSING; PR4 worker decides COMPLETED vs partial-fail terminal state
            log.info("dispatch complete with permanent failures: intentId={} retries pending",
                intent.getIntentId());
        }

        log.info("dispatch end: intentId={} attempted={} all_delivered={}",
            intent.getIntentId(), attempted, allDelivered);
        return attempted;
    }

    /**
     * Per-target dispatch — REQUIRES_NEW boundary + UPSERT (Codex 019df9ef
     * P2 absorb iter-2).
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Resolve adapter</li>
     *   <li>Pre-attempt audit (DELIVERY_ATTEMPTED)</li>
     *   <li>Provider call (adapter.send)</li>
     *   <li>UPSERT delivery row: existing row → UPDATE (state aggregate:
     *       attempt_count++, last_attempt_at, status, failure_reason,
     *       delivered_at); no existing row → INSERT</li>
     *   <li>Post-attempt audit (DELIVERY_SUCCEEDED / FAILED / ATTEMPTED)</li>
     * </ol>
     *
     * <p>Concurrency: PG unique constraint
     * {@code uq_delivery_intent_channel_recipient (intent_id, channel,
     * recipient_hash)} (V2 migration) makes parallel dispatch safe — concurrent
     * INSERT race raises {@code DataIntegrityViolationException} which PG
     * propagates as transaction-abort. Iter-4 absorb: this exception is NOT
     * caught here (REQUIRES_NEW txn marked rollback-only by PG; inner catch
     * unsafe). Outer {@link #dispatchPlanned} catches the rollback at the
     * transaction-boundary and maps to RETRY (PR4 worker re-tries based on
     * next_retry_at; row inserted by concurrent pod is source-of-truth).
     */

    /**
     * Persist a BLOCKED_* delivery row + DELIVERY_BLOCKED audit (Codex 019dfaaa
     * PR5 lock-in #5 absorb).
     *
     * <p>Adapter NOT invoked. Provider attempt count NOT incremented (it's a
     * policy decision, not a provider failure). Status set directly to
     * BLOCKED_BY_PREFERENCE / BLOCKED_BY_AUTHZ / BLOCKED_EXTERNAL_NOT_ALLOWED.
     *
     * <p>REQUIRES_NEW transaction (consistent with dispatchSingleTarget) —
     * one target's blocked persistence does not roll back others.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistBlockedDelivery(
        NotificationIntent intent, DeliveryTarget target,
        DeliveryEligibilityService.EligibilityDecision eligibility
    ) {
        OffsetDateTime now = OffsetDateTime.now();

        // Upsert delivery as BLOCKED_*
        Optional<NotificationDelivery> existing = deliveryRepo
            .findByIntentIdAndChannelAndRecipientHash(
                intent.getIntentId(), target.channel(), target.recipientHash()
            );
        NotificationDelivery delivery = existing.orElseGet(NotificationDelivery::new);
        if (delivery.getId() == null) {
            delivery.setIntentId(intent.getIntentId());
            delivery.setChannel(target.channel());
            delivery.setRecipientType(NotificationDelivery.RecipientType.valueOf(
                target.recipientType().equals("subscriber") ? "SUBSCRIBER"
                    : target.recipientType().equals("external") ? "EXTERNAL" : "CHANNEL"
            ));
            delivery.setRecipientId(target.recipientId());
            delivery.setRecipientHash(target.recipientHash());
            delivery.setProvider(target.providerKey());
            delivery.setAttemptCount(0);  // policy decision; no provider attempt
        }
        delivery.setStatus(eligibility.status());
        delivery.setFailureReason(eligibility.policy() + ": " + eligibility.reason());
        delivery.setPermanentFailureAt(now);  // BLOCKED_* are terminal
        delivery.setNextRetryAt(null);
        delivery.setProcessingLeaseUntil(null);
        delivery.setClaimToken(null);
        deliveryRepo.save(delivery);

        // Audit DELIVERY_BLOCKED (NOT DELIVERY_ATTEMPTED — adapter not called)
        audit.publish("DELIVERY_BLOCKED", intent, target.recipientHash(), target.channel(),
            Map.of(
                "policy", eligibility.policy(),
                "reason", eligibility.reason(),
                "status", eligibility.status().name()
            )
        );

        log.info("delivery blocked: intentId={} channel={} hash={} status={} policy={}",
            intent.getIntentId(), target.channel(), target.recipientHash(),
            eligibility.status(), eligibility.policy());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispatchOutcome dispatchSingleTarget(
        NotificationIntent intent, DeliveryTarget target, RenderedMessage message
    ) {
        ChannelAdapter adapter = adapterRegistry.get(target.channel())
            .orElseThrow(() -> new IllegalStateException(
                "adapter missing for channel '" + target.channel() + "'"
            ));

        // Pre-attempt audit
        audit.publish("DELIVERY_ATTEMPTED", intent, target.recipientHash(), target.channel(),
            Map.of("provider", target.providerKey()));

        // T1.1.8 unsubscribe footer (Codex 019e4476 absorb): email subscriber
        // targets receive a locale-aware footer injection BEFORE adapter.send.
        // No-op for slack/webhook/sms/in-app and for external email recipients.
        // The original `message` reference stays unchanged so the renderer's
        // RenderedMessage record + TemplateRenderer's SSTI/CRLF contract are
        // unaffected for non-email channels and for subject in all cases.
        RenderedMessage outbound = footerAppender.appendIfRequired(intent, target, message);

        ChannelAdapter.DeliveryAttemptResult result;
        try {
            result = adapter.send(target, outbound);
        } catch (RuntimeException e) {
            log.warn("adapter exception (treating as RETRY): channel={} provider={} err={}",
                target.channel(), target.providerKey(), e.getMessage());
            result = ChannelAdapter.DeliveryAttemptResult.retry(
                "exception: " + e.getClass().getSimpleName(), null);
        }

        // iter-4 absorb: do NOT catch DataIntegrityViolationException here —
        // catching inside REQUIRES_NEW transaction is unsafe because PG marks
        // the txn rollback-only on constraint violation; even with a try/catch
        // the commit phase still throws UnexpectedRollbackException. Let any
        // unique-violation propagate; outer dispatchPlanned() catches it and
        // maps to RETRY (PR4 worker re-attempts).
        boolean rowModified = upsertDelivery(intent, target, result);

        // iter-3 absorb: DELIVERED-regress guard fired (existing row was
        // already DELIVERED) — treat as idempotent success. No audit row
        // emitted (already done in earlier dispatch); outcome=DELIVERED so
        // intent COMPLETED computation is correct.
        if (!rowModified) {
            return new DispatchOutcome(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        }

        switch (result.status()) {
            case DELIVERED -> audit.publish("DELIVERY_SUCCEEDED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
            case ACCEPTED -> audit.publish("DELIVERY_ACCEPTED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
            case FAILED, BOUNCED -> audit.publish("DELIVERY_FAILED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
            case RETRY -> audit.publish("DELIVERY_ATTEMPTED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
        }

        return new DispatchOutcome(result.status());
    }

    /** Internal per-target outcome record (status only — full result stored in delivery row). */
    public record DispatchOutcome(ChannelAdapter.DeliveryAttemptResult.Status status) {}

    /**
     * UPSERT delivery row — Codex 019df9ef P2 absorb iter-3.
     *
     * <p>Existing row (RETRY/FAILED/BOUNCED on prior attempt) → UPDATE
     * aggregate fields ({@code attempt_count++}, {@code last_attempt_at},
     * {@code status}, {@code failure_reason}, {@code provider_msg_id},
     * {@code delivered_at}). No existing row → INSERT new row.
     *
     * <p><b>DELIVERED-regress guard (iter-3):</b> If existing row is already
     * {@code DELIVERED}, this method returns {@code true} without modification
     * (concurrent stale dispatch must not regress success state). Caller
     * interprets this as idempotent skip.
     *
     * <p><b>Commit-time unique violation (iter-3):</b> {@code saveAndFlush()}
     * forces immediate constraint check; concurrent INSERT race surfaces as
     * {@code DataIntegrityViolationException} inside this method, not at
     * outer transaction commit.
     *
     * <p>Unique constraint {@code uq_delivery_intent_channel_recipient}
     * preserved: each (intent_id, channel, recipient_hash) tuple has at most
     * 1 row; row is the aggregate state of all attempts (attempt_count,
     * last_attempt_at, current_status), audit_event rows hold full attempt
     * history (DELIVERY_ATTEMPTED/SUCCEEDED/FAILED).
     *
     * @return {@code true} if row was modified; {@code false} if existing row
     *         was DELIVERED (regress guard fired — idempotent skip)
     */
    private boolean upsertDelivery(
        NotificationIntent intent, DeliveryTarget target, ChannelAdapter.DeliveryAttemptResult result
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationDelivery.Status newStatus =
            NotificationDelivery.Status.valueOf(result.status().name());

        Optional<NotificationDelivery> existing = deliveryRepo
            .findByIntentIdAndChannelAndRecipientHash(
                intent.getIntentId(), target.channel(), target.recipientHash()
            );

        NotificationDelivery delivery;
        if (existing.isPresent()) {
            delivery = existing.get();
            // Faz 23.4 PR-F: extended terminal-regress guard. V11 trigger
            // enforces forward-only invariant at DB level; we short-circuit
            // here to avoid noisy DataIntegrityViolationException on stale
            // worker re-dispatch. Terminal states: DELIVERED, FAILED,
            // BOUNCED, BLOCKED_*. ACCEPTED is NOT terminal — DLR may
            // transition it; but a stale dispatch trying to overwrite
            // ACCEPTED with same/lower-precedence state is also no-op
            // (provider already has the message; second send would be a
            // duplicate). Allow ACCEPTED → ACCEPTED idempotent path so
            // attempt_count++ still meaningful.
            switch (delivery.getStatus()) {
                case DELIVERED, FAILED, BOUNCED,
                     BLOCKED_BY_PREFERENCE, BLOCKED_BY_AUTHZ,
                     BLOCKED_BY_IDEMPOTENCY, BLOCKED_EXTERNAL_NOT_ALLOWED -> {
                    log.info("upsert skip (terminal-regress guard {}): intentId={} channel={} hash={}",
                        delivery.getStatus(), intent.getIntentId(),
                        target.channel(), target.recipientHash());
                    return false;
                }
                default -> { /* PENDING/RETRY/ACCEPTED — allow update */ }
            }
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        } else {
            // INSERT new row
            delivery = new NotificationDelivery();
            delivery.setIntentId(intent.getIntentId());
            delivery.setChannel(target.channel());
            delivery.setRecipientType(mapRecipientType(target.recipientType()));
            delivery.setRecipientId(target.recipientId());
            delivery.setRecipientHash(target.recipientHash());
            delivery.setProvider(target.providerKey());
            delivery.setAttemptCount(1);
        }

        delivery.setStatus(newStatus);
        delivery.setLastAttemptAt(now);

        // Faz 23.3 multi-provider (Codex `019e3f82` absorb #1): SMS channel
        // failover sonrası gerçek dispatch eden provider'ı persist et. Plan-time
        // DeliveryTarget.providerKey() SMS için "sms" placeholder; SmsAdapter
        // failover sonucu DeliveryAttemptResult.actualProviderKey ile gerçek
        // provider'ı (jetsms|netgsm) taşır. INSERT + UPDATE path ikisinde de
        // uygulanır — RETRY sonrası secondary kabul ederse provider güncellenir.
        // SMS dışı kanallar actualProviderKey=null → setProvider çağrılmaz
        // (plan-time provider korunur, behavior-neutral).
        if (result.actualProviderKey() != null && !result.actualProviderKey().isBlank()) {
            delivery.setProvider(result.actualProviderKey());
        }

        if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED) {
            delivery.setProviderMsgId(result.providerMessageId());
            delivery.setDeliveredAt(now);
            delivery.setFailureReason(null);  // clear previous failure (recovered)
            delivery.setNextRetryAt(null);
            delivery.setProcessingLeaseUntil(null);
        } else if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED) {
            // Faz 23.4 PR-F: provider queued; no delivered_at (DLR will set
            // it on terminalization to DELIVERED). DLR correlator persisted.
            delivery.setProviderMsgId(result.providerMessageId());
            delivery.setFailureReason(null);
            delivery.setNextRetryAt(null);
            delivery.setProcessingLeaseUntil(null);
            // delivered_at NOT set — terminal status pending DLR
        } else {
            delivery.setFailureReason(result.failureReason());
            // PR4 absorb: BackoffCalculator schedules next_retry_at for RETRY
            if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.RETRY) {
                java.time.Duration delay = backoffCalculator.computeDelay(delivery.getAttemptCount());
                delivery.setNextRetryAt(now.plus(delay));
            } else {
                // FAILED / BOUNCED — terminal failure
                delivery.setPermanentFailureAt(now);
                delivery.setNextRetryAt(null);
            }
            delivery.setProcessingLeaseUntil(null);
        }

        // iter-3 absorb: saveAndFlush — surface unique constraint violations
        // within this method, not at outer commit. Concurrent INSERT race
        // catchable by caller's DataIntegrityViolationException handler.
        deliveryRepo.saveAndFlush(delivery);
        return true;
    }

    /**
     * Map DeliveryTarget recipient type string to NotificationDelivery enum
     * (Codex 019df9ef P2 absorb: CHANNEL value introduced for slack/webhook
     * target-addressed channels — previously fell through to EXTERNAL which
     * polluted audit/analytics semantics).
     */
    private static NotificationDelivery.RecipientType mapRecipientType(String s) {
        return switch (s) {
            case "subscriber" -> NotificationDelivery.RecipientType.SUBSCRIBER;
            case "external" -> NotificationDelivery.RecipientType.EXTERNAL;
            case "channel" -> NotificationDelivery.RecipientType.CHANNEL;
            default -> throw new IllegalStateException(
                "unknown DeliveryTarget recipientType: " + s
            );
        };
    }

    private static Map<String, Object> additionalDetails(ChannelAdapter.DeliveryAttemptResult result) {
        Map<String, Object> details = new HashMap<>();
        details.put("delivery_status", result.status().name());
        if (result.providerMessageId() != null) details.put("provider_msg_id", result.providerMessageId());
        if (result.failureReason() != null) details.put("failure_reason", result.failureReason());
        if (result.providerResponseCode() != null) details.put("provider_response_code", result.providerResponseCode());
        // Faz 23.3 multi-provider (Codex `019e3fc5` PR-1 review P1 absorb):
        // SMS failover sonrası gerçek dispatch eden provider outcome audit'inde
        // görünür (RetryWorker outcome audit ile paritede). SMS dışı kanallar
        // actualProviderKey=null → detail eklenmez.
        if (result.actualProviderKey() != null) {
            details.put("actual_provider", result.actualProviderKey());
        }
        return details;
    }
}
