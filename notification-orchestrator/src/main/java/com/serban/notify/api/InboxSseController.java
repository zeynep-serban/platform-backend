package com.serban.notify.api;

import com.serban.notify.inbox.InboxService;
import com.serban.notify.inbox.InboxUpdatedEvent;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Inbox Server-Sent Events (SSE) endpoint for real-time badge updates
 * (Faz 23.3 PR-E.3 — charter §187 "WS endpoint — unread count badge update").
 *
 * <p>Why SSE instead of WebSocket/SockJS/STOMP per charter literal? The
 * acceptance criterion (§204) is "unread count badge update" — server-to-client
 * unidirectional push. SSE satisfies this via plain HTTP (no extra dependency,
 * no protocol negotiation, no upgrade handshake). Bidirectional WebSocket
 * deferred to PR-F if client→server push (e.g. typing indicators) becomes
 * required.
 *
 * <p>Endpoint: {@code GET /api/v1/notify/inbox/me/stream?orgId=<X>&subscriberId=<Y>}
 * <ul>
 *   <li>Initial event: current unread count</li>
 *   <li>Subsequent events: pushed when {@link InboxUpdatedEvent} fires for
 *       this subscriber (row insert, mark-read, archive)</li>
 *   <li>Heartbeat: every 25s — prevents intermediary proxies from idle-closing</li>
 * </ul>
 *
 * <p>Identity (Codex iter-1 P0 absorb): native browser {@code EventSource}
 * API cannot send custom headers; identity is passed via query params.
 * Spring Security on the route still authenticates the request (gateway
 * cookie→JWT chain); query params identify the subscriber scope. Production
 * lockdown: PR-D-future replaces query params with JWT subject/org claim
 * extraction so a caller cannot subscribe to another subscriber's stream.
 *
 * <p>Multi-pod posture (Faz 23.4 PR-E.4): cross-pod event delivery via PG
 * LISTEN/NOTIFY pattern (default cross-pod-enabled=true). Each pod's
 * {@code InboxNotifyListener} receives all NOTIFY events post-commit and
 * re-emits the Spring event locally — every pod's SSE clients see the
 * update regardless of which pod handled the original mutation. Single-pod
 * fallback path (cross-pod-enabled=false) preserved for local dev / unit
 * test. After PR-E.4 merge + rollout, gitops PR #385 (HPA min=max=1 lock)
 * can be reverted so HPA scale-out re-enables.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Connect → emitter added to {@link #emitters} keyed by (org, subscriber)</li>
 *   <li>Disconnect (timeout / error / client close) → emitter removed</li>
 *   <li>{@link #cleanupStale()} every 60s sweeps any stale entries (defensive)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/notify/inbox")
@Validated
public class InboxSseController {

    private static final Logger log = LoggerFactory.getLogger(InboxSseController.class);

    /** SSE timeout — long-lived connection (30 min). */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000;

    /** Heartbeat cadence — proxies typically idle-timeout at 30-60s. */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final InboxService inboxService;
    private final SubscriberIdentityGuard subscriberIdentityGuard;

    /** Per-(orgId, subscriberId) connected SSE emitters. */
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public InboxSseController(
        InboxService inboxService,
        SubscriberIdentityGuard subscriberIdentityGuard
    ) {
        this.inboxService = inboxService;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
    }

    /**
     * Subscribe to inbox events for the authenticated subscriber.
     *
     * <p>Codex iter-1 P0 absorb: identity via query params (browser EventSource
     * does not support custom headers). PR-D-future replaces with JWT claim.
     */
    @GetMapping(path = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
        @RequestParam(name = "orgId", required = true) @NotBlank String orgId,
        @RequestParam(name = "subscriberId", required = true) @NotBlank String subscriberId
    ) {
        // Faz 23.4 PR-E.5: enforce subscriberId query param matches JWT
        // principal so an authenticated caller cannot stream another
        // subscriber's unread updates by editing the query string.
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        String key = key(orgId, subscriberId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> {
            log.debug("inbox SSE timeout: key={}", key);
            removeEmitter(key, emitter);
        });
        emitter.onError(t -> {
            log.debug("inbox SSE error: key={} err={}", key, t.getMessage());
            removeEmitter(key, emitter);
        });

        // Initial event: current unread count.
        // Codex iter-1 P2.5 absorb: catch RuntimeException (e.g. emitter
        // already completed by timeout race) in addition to IOException so
        // emitter is always removed on failure.
        try {
            long unreadCount = inboxService.unreadCount(orgId, subscriberId);
            emitter.send(SseEmitter.event()
                .name("unread-count")
                .data(Map.of("unreadCount", unreadCount)));
        } catch (IOException | RuntimeException e) {
            log.warn("inbox SSE initial send failed: key={} err={}", key, e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignore) { /* already completed */ }
            removeEmitter(key, emitter);
        }

        log.info("inbox SSE subscribed: orgId={} subscriberId={} totalEmitters={}",
            orgId, subscriberId, totalEmitters());

        return emitter;
    }

    /**
     * Listen for inbox state changes; broadcast to subscriber's connected SSE
     * clients on this pod.
     *
     * <p>Faz 23.4 PR-E.4 (Codex iter-1 P1.3 absorb):
     * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)}.
     * <ul>
     *   <li>Cross-pod path: event arrives via {@link com.serban.notify.inbox.InboxNotifyListener}
     *       (PG NOTIFY post-commit). No active transaction at delivery time;
     *       {@code fallbackExecution=true} ensures the handler still fires.</li>
     *   <li>Single-pod fallback path (cross-pod-enabled=false): publisher
     *       calls {@code applicationEventPublisher.publishEvent} inside the
     *       caller's transaction. {@code AFTER_COMMIT} phase guard prevents
     *       phantom events on rollback — listener fires only after commit.</li>
     * </ul>
     *
     * <p>Codex PR-E.3 iter-1 P1.3 absorb: {@code @Async("inboxSseExecutor")}
     * — bounded {@code ThreadPoolTaskExecutor} (see {@code AsyncConfig});
     * SSE network IO does not block event-publishing thread.
     *
     * <p>Codex PR-E.3 iter-1 P2.5 absorb: catch {@link RuntimeException}
     * alongside {@link IOException} so a failed/completed emitter doesn't
     * leak into the map and consume future heartbeat slots.
     */
    @org.springframework.transaction.event.TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true
    )
    @Async("inboxSseExecutor")
    public void onInboxUpdated(InboxUpdatedEvent event) {
        String key = key(event.orgId(), event.subscriberId());
        List<SseEmitter> targets = emitters.get(key);
        if (targets == null || targets.isEmpty()) return;

        Map<String, Object> payload = Map.of("unreadCount", event.unreadCount());
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                    .name("unread-count")
                    .data(payload));
            } catch (IOException | RuntimeException e) {
                log.debug("inbox SSE event send failed (emitter dropping): key={} err={}",
                    key, e.getMessage());
                try { emitter.completeWithError(e); } catch (Exception ignore) {}
                removeEmitter(key, emitter);
            }
        }
    }

    /**
     * Heartbeat sweep — push comment line to all emitters every 25s. Keeps
     * intermediary proxies from idle-closing.
     */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        // Codex iter-1 P2.5 absorb: catch RuntimeException (e.g.
        // IllegalStateException from completed emitter) so heartbeat doesn't
        // re-fire on dead emitter every 25s.
        emitters.forEach((key, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | RuntimeException e) {
                    try { emitter.completeWithError(e); } catch (Exception ignore) {}
                    removeEmitter(key, emitter);
                }
            }
        });
    }

    /** Periodic stale entry cleanup (defensive — emitter callbacks should handle most). */
    @Scheduled(fixedDelay = 60_000L)
    public void cleanupStale() {
        emitters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void removeEmitter(String key, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(key);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(key);
            }
        }
    }

    private static String key(String orgId, String subscriberId) {
        return orgId + "::" + subscriberId;
    }

    /** Diagnostic accessor (test-only); count of connected emitters across all keys. */
    int totalEmitters() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    /** Test-only: clear all emitters (used in @AfterEach to isolate tests). */
    void clearAllForTest() {
        emitters.clear();
    }

    /**
     * @ConstraintViolationException (e.g. blank orgId/subscriberId query param)
     * → 400 (Codex iter-1 P2.6 absorb — matches InboxController pattern).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleConstraintViolation(ConstraintViolationException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }
}
