package com.example.permission.impersonation;

import com.example.permission.service.ImpersonationSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * User Impersonation v1 — TTL sweeper for expired-but-still-ACTIVE sessions.
 *
 * <p>Codex 019e0dfb iter-19 contract: KC token expiration is real but DB
 * row keeps {@code status=ACTIVE} until something flips it. Without a
 * sweeper, expired tokens still resolve via jti_session_lookup middleware
 * and audit row attribution stays "open". Worse, the
 * {@code ux_impersonation_sessions_one_active_per_impersonator} partial
 * UNIQUE constraint blocks new starts because the expired row hasn't
 * been demoted yet.
 *
 * <p>This scheduler ticks every {@code app.impersonation.sweep-interval-ms}
 * (default 60s) and calls {@link ImpersonationSessionService#sweepExpired()}
 * which runs {@code UPDATE ... SET status='EXPIRED', ended_reason='SYSTEM_SWEEP'
 * WHERE status='ACTIVE' AND expires_at <= now()}.
 *
 * <p>HPA-managed deployment safety (Codex iter-19): multiple replicas may
 * race the sweep query; PostgreSQL UPDATE … WHERE is atomic at row level,
 * the operation is idempotent (only ACTIVE → EXPIRED transitions count),
 * and the audit writer is only invoked by the row-counting path inside
 * {@link ImpersonationSessionService#stopSession} for explicit stops —
 * sweeps don't fire IMPERSONATION_STOPPED audit events (they are
 * implicit, traced only via session.ended_reason='SYSTEM_SWEEP').
 *
 * <p>Disabled by setting {@code app.impersonation.sweep.enabled=false}
 * (e.g. unit tests or one-shot scripts).
 */
@Component
@ConditionalOnProperty(
        name = "app.impersonation.sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImpersonationSessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationSessionSweeper.class);

    private final ImpersonationSessionService sessionService;
    private final long sweepIntervalMs;

    public ImpersonationSessionSweeper(
            ImpersonationSessionService sessionService,
            @Value("${app.impersonation.sweep-interval-ms:60000}") long sweepIntervalMs) {
        this.sessionService = sessionService;
        this.sweepIntervalMs = sweepIntervalMs;
    }

    /**
     * Periodic sweep — flips ACTIVE rows past expires_at to EXPIRED.
     */
    @Scheduled(fixedDelayString = "${app.impersonation.sweep-interval-ms:60000}")
    public void tick() {
        try {
            int swept = sessionService.sweepExpired();
            if (swept > 0) {
                log.info("Impersonation TTL sweep: {} session(s) marked EXPIRED", swept);
            } else {
                log.debug("Impersonation TTL sweep: no expired-but-ACTIVE rows");
            }
        } catch (Exception e) {
            // Never let scheduler thread die — log + continue next tick.
            log.warn("Impersonation TTL sweep failed (will retry next tick): {}", e.getMessage(), e);
        }
    }

    /** Test helper: invoke sweep deterministically (bypass @Scheduled). */
    public int sweepNow() {
        return sessionService.sweepExpired();
    }

    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }
}
