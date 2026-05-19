package com.serban.notify.dlr;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.worker.IntentStatusResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DLR (Delivery Receipt) ingest service — Faz 23.4 PR-F.
 *
 * <p>Provider posts terminal delivery status after SMS reaches/fails-at
 * carrier. We atomically transition the matching {@link NotificationDelivery}
 * row from {@code ACCEPTED} to {@code DELIVERED} or {@code FAILED}, then
 * recompute parent intent terminal status via {@link IntentStatusResolver}.
 *
 * <p>Status mapping (NetGSM REST v2 DLR codes):
 * <ul>
 *   <li>{@code 00} → {@link NotificationDelivery.Status#DELIVERED} terminal</li>
 *   <li>{@code 04, 05, 16, 17, 70} → {@link NotificationDelivery.Status#FAILED}
 *       terminal (carrier reject / undeliverable / expired / IYS opt-out)</li>
 *   <li>others → no status mutation; audit-only transient ack</li>
 * </ul>
 *
 * <p><b>Atomic mutation</b> (Codex iter-1 P1.4 absorb): native UPDATE with
 * status predicate {@code WHERE provider_msg_id = ? AND status = 'ACCEPTED'}.
 * Multi-pod safe: two pods receiving same DLR → DB-level race resolution
 * (one wins count=1, other count=0). No SELECT-then-UPDATE TOCTOU window.
 *
 * <p><b>Forward-only invariant</b>: terminal states (DELIVERED, FAILED,
 * BOUNCED, BLOCKED_*) immutable. Late DLR for already-terminal row → atomic
 * UPDATE returns 0 (predicate WHERE status='ACCEPTED' fails); we audit
 * the conflict event ({@code DELIVERY_DLR_TERMINAL_CONFLICT}) for forensic
 * trail but no state mutation.
 *
 * <p><b>Parent intent recompute</b> (Codex iter-1 P1.1 absorb): on actual
 * mutation, fetch all sibling deliveries for the intent + invoke
 * {@link IntentStatusResolver} to determine new intent terminal status
 * (COMPLETED / PARTIALLY_FAILED / FAILED / null=PROCESSING).
 *
 * <p>Out of scope (this PR):
 * <ul>
 *   <li>Multi-provider DLR (İletimerkezi, Twilio) — provider-specific code
 *       maps belong in adapter-side translators future PR</li>
 *   <li>SSE push to subscriber on DLR terminalization (PR-G follow-up
 *       after inbox/SSE bridges established)</li>
 * </ul>
 */
@Service
public class DlrIngestService {

    private static final Logger log = LoggerFactory.getLogger(DlrIngestService.class);

    /**
     * NetGSM permanent failure codes (terminal FAILED). 17/70 KVKK opt-out
     * (IYS); 04/05/16 carrier-side undeliverable.
     */
    private static final Set<String> NETGSM_PERMANENT_FAILURE_CODES =
        Set.of("04", "05", "16", "17", "70");

    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationIntentRepository intentRepo;
    private final AuditEventPublisher audit;
    private final IntentStatusResolver intentStatusResolver;

    public DlrIngestService(
        NotificationDeliveryRepository deliveryRepo,
        NotificationIntentRepository intentRepo,
        AuditEventPublisher audit,
        IntentStatusResolver intentStatusResolver
    ) {
        this.deliveryRepo = deliveryRepo;
        this.intentRepo = intentRepo;
        this.audit = audit;
        this.intentStatusResolver = intentStatusResolver;
    }

    /**
     * Process NetGSM DLR callback (webhook PUSH).
     *
     * <p>Faz 23.3 PR-3 refactor (Codex `019e3f82` absorb): NetGSM-specific
     * code mapping burada; terminal mutation generic {@link #ingestTerminal}
     * core'una delege edilir. JetSMS poller da aynı core'u çağırır.
     *
     * @param jobid provider correlator (we look up "netgsm-{jobid}")
     * @param code provider DLR status code
     * @param description provider human-readable description (audit only)
     * @param providerDeliveredAtIso provider's terminal timestamp (ISO-8601);
     *                                 null/blank → service uses NOW()
     * @return {@link DlrResult} with action taken (UPDATED / NOOP / NOT_FOUND)
     */
    @Transactional
    public DlrResult ingestNetgsm(String jobid, String code, String description,
                                   String providerDeliveredAtIso) {
        String providerMsgId = "netgsm-" + jobid;
        NotificationDelivery.Status targetStatus = mapNetgsmCode(code);

        // Transient/unknown DLR code → audit only, no mutation attempt.
        // (NetGSM-specific: transient code'lar terminal status üretmez.)
        if (targetStatus == null) {
            Optional<NotificationDelivery> opt =
                deliveryRepo.findFirstByProviderMsgId(providerMsgId);
            if (opt.isEmpty()) {
                log.warn("dlr netgsm: delivery not found provider_msg_id={} code={}",
                    providerMsgId, code);
                return new DlrResult(DlrAction.NOT_FOUND, providerMsgId, null);
            }
            NotificationDelivery delivery = opt.get();
            log.info("dlr netgsm transient: code={} prior={} delivery_id={}",
                code, delivery.getStatus(), delivery.getId());
            emitAudit("netgsm", delivery, "DELIVERY_DLR_RECEIVED", code, false, null);
            return new DlrResult(DlrAction.NOOP, providerMsgId, delivery.getStatus());
        }

        // Terminal status → generic core.
        return ingestTerminal("netgsm", providerMsgId, targetStatus, code,
            parseTerminalAt(providerDeliveredAtIso));
    }

    /**
     * Generic DLR terminal ingest core — Faz 23.3 PR-3 (Codex `019e3f82`
     * absorb #3).
     *
     * <p>Provider-agnostic: NetGSM webhook ({@link #ingestNetgsm}) ve JetSMS
     * polling worker ({@code JetSmsDlrPollingWorker}) ortak bu method'u çağırır.
     * Atomik {@code UPDATE WHERE status='ACCEPTED'} (multi-pod race safe),
     * terminal-conflict audit, parent intent recompute — hepsi tek yerde.
     *
     * @param providerKey provider tanımlayıcısı ({@code "netgsm"}|{@code "jetsms"})
     *                    — audit {@code provider} detail'i
     * @param providerMsgId DLR correlator ({@code "<providerKey>-<rawId>"})
     * @param terminalStatus hedef terminal durum (DELIVERED | FAILED) — non-null
     * @param providerCode provider raw DLR/state code (audit)
     * @param providerTerminalAt provider'ın terminal timestamp'i; null → NOW()
     * @return {@link DlrResult} (UPDATED / NOOP / NOT_FOUND)
     */
    @Transactional
    public DlrResult ingestTerminal(String providerKey, String providerMsgId,
                                    NotificationDelivery.Status terminalStatus,
                                    String providerCode,
                                    OffsetDateTime providerTerminalAt) {
        Optional<NotificationDelivery> opt =
            deliveryRepo.findFirstByProviderMsgId(providerMsgId);
        if (opt.isEmpty()) {
            log.warn("dlr {}: delivery not found provider_msg_id={} code={}",
                providerKey, providerMsgId, providerCode);
            return new DlrResult(DlrAction.NOT_FOUND, providerMsgId, null);
        }
        NotificationDelivery delivery = opt.get();

        // Atomic UPDATE WHERE status='ACCEPTED' — multi-pod race safe.
        String failureReason = (terminalStatus == NotificationDelivery.Status.FAILED)
            ? "dlr " + providerKey + " code=" + providerCode : null;
        int affected = deliveryRepo.dlrTerminalize(
            providerMsgId, terminalStatus.name(), providerTerminalAt, failureReason);

        if (affected == 0) {
            // Terminal-conflict: row status ≠ ACCEPTED (late duplicate DLR /
            // already-terminal / unexpected state). Forensic audit, no mutation.
            // findFirstByProviderMsgId yukarıda UPDATE'ten ÖNCE okundu — multi-pod
            // race'te paralel bir pod row'u terminal yapmışsa o snapshot stale
            // olur (Codex `019e3ff7` P2). Re-fetch ile gerçek current status
            // audit'lenir; stale "prior_status_accepted" basılmaz.
            NotificationDelivery current = deliveryRepo.findById(delivery.getId())
                .orElse(delivery);
            NotificationDelivery.Status conflictStatus = current.getStatus();
            log.info("dlr {} terminal-conflict (no mutation): code={} current={} delivery_id={}",
                providerKey, providerCode, conflictStatus, current.getId());
            // Locale.ROOT — Turkish-i bug guard: tr-TR JVM'de "DELIVERED"
            // .toLowerCase() → "delıvered" (noktasız ı); audit string locale'e
            // bağımlı olmamalı (forensic query'ler kararlı eşleşme bekler).
            emitAudit(providerKey, current, "DELIVERY_DLR_TERMINAL_CONFLICT",
                providerCode, false,
                "prior_status_" + conflictStatus.name().toLowerCase(Locale.ROOT));
            return new DlrResult(DlrAction.NOOP, providerMsgId, conflictStatus);
        }

        // Mutation succeeded — re-fetch for accurate post-state.
        NotificationDelivery updated = deliveryRepo.findById(delivery.getId())
            .orElseThrow(() -> new IllegalStateException(
                "delivery " + delivery.getId() + " disappeared after dlrTerminalize"));

        log.info("dlr {} UPDATED: code={} delivery_id={} prior=ACCEPTED new={}",
            providerKey, providerCode, updated.getId(), updated.getStatus());

        emitAudit(providerKey, updated, "DELIVERY_DLR_RECEIVED", providerCode, true, null);

        // Parent intent recompute — DLR may complete last outstanding delivery.
        recomputeIntentStatus(updated.getIntentId());

        return new DlrResult(DlrAction.UPDATED, providerMsgId, updated.getStatus());
    }

    /**
     * Recompute intent terminal status from current delivery row aggregate.
     * Mirror of RetryWorker / OutboxPoller pattern.
     */
    private void recomputeIntentStatus(String intentId) {
        Optional<NotificationIntent> intentOpt = intentRepo.findByIntentId(intentId);
        if (intentOpt.isEmpty()) {
            log.warn("dlr netgsm: intent {} missing post-DLR — skip recompute", intentId);
            return;
        }
        NotificationIntent intent = intentOpt.get();
        // Skip if already terminal (no recompute needed; trigger guards future writes anyway)
        if (intent.getStatus() == NotificationIntent.Status.COMPLETED
            || intent.getStatus() == NotificationIntent.Status.FAILED
            || intent.getStatus() == NotificationIntent.Status.PARTIALLY_FAILED
            || intent.getStatus() == NotificationIntent.Status.EXPIRED) {
            return;
        }

        var deliveries = deliveryRepo.findByIntentId(intentId);
        NotificationIntent.Status newStatus = intentStatusResolver.resolve(deliveries);
        if (newStatus != null && newStatus != intent.getStatus()) {
            intent.setStatus(newStatus);
            intent.setTerminatedAt(OffsetDateTime.now());
            intentRepo.save(intent);
            log.info("dlr netgsm: intent {} terminal transition → {}", intentId, newStatus);
        }
    }

    private void emitAudit(String providerKey, NotificationDelivery delivery,
                            String eventType, String providerCode,
                            boolean stateMutated, String ignoredReason) {
        Optional<NotificationIntent> intentOpt =
            intentRepo.findByIntentId(delivery.getIntentId());
        if (intentOpt.isEmpty()) {
            log.warn("dlr {}: intent {} not found for delivery {} — audit skipped",
                providerKey, delivery.getIntentId(), delivery.getId());
            return;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("provider", providerKey);
        details.put("provider_code", providerCode);
        details.put("dlr_state_mutated", stateMutated);
        if (ignoredReason != null) {
            details.put("dlr_ignored_reason", ignoredReason);
        }
        audit.publishWithDelivery(eventType, intentOpt.get(), delivery, "sms", details);
    }

    /**
     * NetGSM DLR code → notification_delivery.Status mapping.
     *
     * @return target status, or null if code is transient (no terminal mutation)
     */
    private static NotificationDelivery.Status mapNetgsmCode(String code) {
        if ("00".equals(code)) {
            return NotificationDelivery.Status.DELIVERED;
        }
        if (code != null && NETGSM_PERMANENT_FAILURE_CODES.contains(code)) {
            return NotificationDelivery.Status.FAILED;
        }
        return null;  // transient — audit only
    }

    /**
     * Parse ISO-8601 timestamp; null/blank/malformed → null (caller defaults
     * to NOW()).
     */
    private static OffsetDateTime parseTerminalAt(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException ex) {
            // Provider clock unparseable — fall back to server time silently
            return null;
        }
    }

    /** Result of DLR ingest call (controller reports back to provider). */
    public record DlrResult(
        DlrAction action,
        String providerMsgId,
        NotificationDelivery.Status currentStatus
    ) {}

    public enum DlrAction {
        /** Delivery row state mutated (status terminal transition applied). */
        UPDATED,
        /** Delivery row found but no state mutation (idempotent re-call,
         *  transient code, or terminal-conflict for already-terminal row). */
        NOOP,
        /** Delivery row not found by provider_msg_id. */
        NOT_FOUND
    }
}
