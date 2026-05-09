package com.serban.notify.abuse;

import com.serban.notify.domain.NotificationIntent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AbuseGuardService — Faz 23.2.F abuse prevention guards (T1.6).
 *
 * <p>M3 stale audit closure path (Codex thread {@code 019e0c28} strategic
 * finding): T1.6 backend code "RateLimitGuard / AbuseGuard NOT FOUND in code"
 * olarak işaretlenmişti; bu sınıf gerçek pending implementation'ı sağlar.
 *
 * <p>Scope (MVP — D45 abuse axis):
 * <ul>
 *   <li>Rate limit per source: (orgId, sourceKey) tuple başına sliding window
 *       (default 60s / 100 request); critical severity bypass</li>
 *   <li>Webhook fan-out cap: intent.channels[] içinde "webhook" entry
 *       sayısı &gt; cap (default 10) → reject</li>
 *   <li>429 response + audit `RATE_LIMITED` / `WEBHOOK_FANOUT_CAPPED` event</li>
 *   <li>Micrometer counter: {@code notify_abuse_blocked_total{reason}}</li>
 * </ul>
 *
 * <p>Multi-pod bypass risk (HARD RULE — explicit dokümante):
 * In-process atomic counter (ConcurrentHashMap + AtomicLong) → her pod ayrı
 * window tutar. 2-pod deployment'ta gerçek rate effective_limit = pod_count
 * × per_pod_limit. Strict enforcement için PG-backed sliding window veya
 * Redis token bucket sonraki iter (Codex önerisi: "Yeni Redis gibi stateful
 * dependency ekleme MVP'de"). Bu MVP single-pod soft enforcement; multi-pod
 * deployment'ta cap effective olarak 2x'lenir, R13 (webhook fan-out flood)
 * + R19 (mass send storm) materialization risk azaltılır ama elimine
 * edilmez.
 *
 * <p>Critical bypass (D45 + must-have #8 alignment) — Codex `019e0c28` P1 absorb:
 * **Sadece** {@code severity=critical} intent'ler **rate limit**'i bypass
 * eder (operational alarm + system event'lerin abuse guard tarafından bloke
 * edilmemesi için). Audit'te `RATE_LIMIT_BYPASSED_CRITICAL` event publish
 * edilir.
 *
 * <p><strong>Bypass scope kasıtlı dar tutuldu</strong>:
 * <ul>
 *   <li>{@code data_classification=security} bypass KALDIRILDI — request DTO
 *       client-controlled, trusted producer authority yok; abuse guard
 *       trivial bypass riski (Codex P1 absorb 2026-05-09).</li>
 *   <li>Webhook fan-out cap **hiçbir koşulda bypass edilmez** — hard
 *       safety limit; severity=critical bile fan-out flood'a izin vermez.</li>
 * </ul>
 * Trusted producer signal (e.g., service-to-service authority) gelecek
 * iter follow-up.
 *
 * <p>Source key derivation: caller `orgId` + `topicKey` (default; future:
 * client_id from JWT). Aynı org + aynı topic'e gelen ardışık intent'ler
 * rate limited; farklı topic'ler bağımsız sayılır.
 */
@Service
public class AbuseGuardService {

    private static final Logger log = LoggerFactory.getLogger(AbuseGuardService.class);

    /**
     * Audit event types (Codex audit reporting netlik discipline).
     */
    public static final String EVENT_RATE_LIMITED = "RATE_LIMITED";
    public static final String EVENT_WEBHOOK_FANOUT_CAPPED = "WEBHOOK_FANOUT_CAPPED";
    public static final String EVENT_RATE_LIMIT_BYPASSED_CRITICAL = "RATE_LIMIT_BYPASSED_CRITICAL";

    /**
     * Sliding window key — (orgId, topicKey) tuple. Future: clientId
     * extension için ek field eklenebilir.
     */
    private record WindowKey(String orgId, String topicKey) {}

    /**
     * Sliding window value — current count + window start timestamp (ms).
     * AtomicLong combined: bits 0-31 count, bits 32-63 window start (sec).
     * Lock-free CAS update; ConcurrentHashMap shard contention için yeterli.
     */
    private static class WindowState {
        final AtomicLong count = new AtomicLong(0);
        volatile long windowStartMs;

        WindowState(long now) {
            this.windowStartMs = now;
        }
    }

    private final ConcurrentHashMap<WindowKey, WindowState> windows = new ConcurrentHashMap<>();

    private final Counter rateLimitedCounter;
    private final Counter fanoutCappedCounter;
    private final Counter criticalBypassCounter;

    private final long windowMillis;
    private final long rateLimit;
    private final int webhookFanoutCap;

    public AbuseGuardService(
        MeterRegistry meterRegistry,
        @Value("${notify.abuse.rate-limit.window-seconds:60}") long windowSeconds,
        @Value("${notify.abuse.rate-limit.max-per-window:100}") long rateLimit,
        @Value("${notify.abuse.webhook-fanout.cap:10}") int webhookFanoutCap
    ) {
        this.windowMillis = windowSeconds * 1000L;
        this.rateLimit = rateLimit;
        this.webhookFanoutCap = webhookFanoutCap;

        this.rateLimitedCounter = Counter.builder("notify_abuse_blocked_total")
            .description("Notification abuse guard blocks (rate limit, fan-out cap, etc.)")
            .tag("reason", "rate_limit")
            .register(meterRegistry);
        this.fanoutCappedCounter = Counter.builder("notify_abuse_blocked_total")
            .description("Notification abuse guard blocks (rate limit, fan-out cap, etc.)")
            .tag("reason", "webhook_fanout_cap")
            .register(meterRegistry);
        this.criticalBypassCounter = Counter.builder("notify_abuse_bypassed_total")
            .description("Notification abuse guard rate limit bypasses (severity=critical only)")
            .tag("reason", "critical_severity")
            .register(meterRegistry);

        log.info("AbuseGuardService initialized: window={}s rateLimit={}/window webhookFanoutCap={} (multi-pod soft enforcement)",
            windowSeconds, rateLimit, webhookFanoutCap);
    }

    /**
     * Pre-submit abuse guard check.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Webhook fan-out cap: channels.count("webhook") &gt; cap →
     *       audit `WEBHOOK_FANOUT_CAPPED` + return BLOCKED_FANOUT
     *       (HARD limit — severity=critical bile bypass etmez)</li>
     *   <li>Critical bypass (rate limit only): severity=critical →
     *       audit `RATE_LIMIT_BYPASSED_CRITICAL` + return ALLOWED
     *       (data_classification=security bypass YOK — Codex P1 absorb)</li>
     *   <li>Rate limit: sliding window check → audit `RATE_LIMITED` if exceeded
     *       + return BLOCKED_RATE_LIMIT</li>
     *   <li>Else: increment window counter + return ALLOWED</li>
     * </ol>
     *
     * <p>Idempotent: returns same decision for same input within window
     * (counter increment side-effect; idempotency replay should bypass via
     * upstream idempotencyService.findActiveOriginal check).
     *
     * @param orgId caller org_id
     * @param topicKey intent topic key (sliding window axis)
     * @param severity intent severity (critical bypass check)
     * @param dataClassification intent data classification (security bypass check)
     * @param channels intent channels[] (fan-out cap check)
     * @return Decision (ALLOWED + reason; BLOCKED_RATE_LIMIT/BLOCKED_FANOUT_CAP)
     */
    public Decision check(
        String orgId,
        String topicKey,
        NotificationIntent.Severity severity,
        NotificationIntent.DataClassification dataClassification,
        java.util.List<String> channels
    ) {
        // Step 1: Webhook fan-out cap — HARD SAFETY LIMIT (no bypass even for critical)
        // Codex P1 absorb: fan-out flood her koşulda hard limit'e takılır;
        // severity=critical bile çoklu webhook subscription istemine izin vermez.
        if (channels != null && !channels.isEmpty()) {
            long webhookCount = channels.stream()
                .filter("webhook"::equalsIgnoreCase)
                .count();
            if (webhookCount > webhookFanoutCap) {
                fanoutCappedCounter.increment();
                log.warn("AbuseGuard webhook fanout cap: orgId={} topic={} webhookCount={} cap={}",
                    orgId, topicKey, webhookCount, webhookFanoutCap);
                return Decision.blocked("webhook_fanout_cap_exceeded",
                    EVENT_WEBHOOK_FANOUT_CAPPED,
                    Map.of("webhook_count", webhookCount, "cap", (long) webhookFanoutCap));
            }
        }

        // Step 2: Critical bypass for RATE LIMIT only (Codex P1 absorb dar scope)
        // Sadece severity=critical bypass; data_classification=security bypass
        // kaldırıldı (request DTO client-controlled, authority signal yok).
        if (severity == NotificationIntent.Severity.critical) {
            criticalBypassCounter.increment();
            log.debug("AbuseGuard rate limit critical bypass: orgId={} topic={} severity={}",
                orgId, topicKey, severity);
            return Decision.allowed("critical_bypass");
        }

        // Step 3: Rate limit sliding window (data_classification=security DAHIL non-bypass)
        WindowKey key = new WindowKey(orgId, topicKey);
        long now = System.currentTimeMillis();
        WindowState state = windows.computeIfAbsent(key, k -> new WindowState(now));

        // Window expired → reset
        synchronized (state) {
            if (now - state.windowStartMs >= windowMillis) {
                state.windowStartMs = now;
                state.count.set(0);
            }
        }

        long currentCount = state.count.incrementAndGet();
        if (currentCount > rateLimit) {
            rateLimitedCounter.increment();
            log.warn("AbuseGuard rate limit exceeded: orgId={} topic={} count={} limit={}",
                orgId, topicKey, currentCount, rateLimit);
            return Decision.blocked("rate_limit_exceeded",
                EVENT_RATE_LIMITED,
                Map.of("count", currentCount, "limit", rateLimit, "window_ms", windowMillis));
        }

        return Decision.allowed("within_window");
    }

    /**
     * Decision result for AbuseGuardService.check().
     *
     * <p>{@code allowed}=true: caller MAY proceed; {@code reason} taşınır
     * (audit context için).
     *
     * <p>{@code allowed}=false: caller MUST reject (HTTP 429 or 4xx);
     * {@code auditEventType} ile {@code auditDetails} caller tarafından
     * audit append yapılır (intent context dışarıda).
     */
    public record Decision(
        boolean allowed,
        String reason,
        String auditEventType,
        Map<String, Object> auditDetails
    ) {
        public static Decision allowed(String reason) {
            return new Decision(true, reason, null, Map.of());
        }

        public static Decision blocked(String reason, String auditEventType, Map<String, Object> details) {
            return new Decision(false, reason, auditEventType, details);
        }
    }

    /**
     * Test/admin helper — clear sliding windows (e.g., for test isolation
     * or operational reset). NOT exposed via REST.
     */
    public void resetWindows() {
        windows.clear();
        log.info("AbuseGuardService: sliding windows cleared");
    }
}
