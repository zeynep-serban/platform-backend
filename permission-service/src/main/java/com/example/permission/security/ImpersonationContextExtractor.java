package com.example.permission.security;

import com.example.permission.model.ImpersonationSession;
import com.example.permission.repository.ImpersonationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * jti_session_lookup binding middleware (Spike-2 PASS_JTI_SESSION_LOOKUP).
 *
 * <p>Codex 019e0dfb iter-10/19 design:
 * <ul>
 *   <li>Exchange token JWT'den jti+sid+issuer extract</li>
 *   <li>impersonation_sessions DB lookup (HPA-managed pod-independent)</li>
 *   <li>azp=impersonation-broker token'larda DB session ZORUNLU
 *       — yoksa fail-closed reject</li>
 *   <li>Normal frontend token'ları bu lookup'a sokulmaz (broker-issued only)</li>
 *   <li>impersonator identity authoritative DB session'dan, JWT'den DEĞİL</li>
 * </ul>
 *
 * <p>Token revocation: KC access token kriptografik olarak exp dolana kadar
 * valid; gerçek revoke sinyali DB'den (status=STOPPED/EXPIRED/REVOKED).
 */
@Component
public class ImpersonationContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationContextExtractor.class);

    /**
     * Broker client_id — azp claim ile match (yalnız bu token'lar lookup'a girer).
     * Codex iter-25 absorb: hard-coded yerine config property (KC realm/client
     * isimleri ileride değişebilir).
     */
    private final String brokerAzp;

    private final ImpersonationSessionRepository sessionRepository;
    private final boolean requireBrokerSessionLookup;

    public ImpersonationContextExtractor(
            ImpersonationSessionRepository sessionRepository,
            @Value("${auth.impersonation.broker-client-id:impersonation-broker}") String brokerAzp,
            @Value("${auth.impersonation.require-broker-session-lookup:true}") boolean requireBrokerSessionLookup) {
        this.sessionRepository = sessionRepository;
        this.brokerAzp = brokerAzp;
        this.requireBrokerSessionLookup = requireBrokerSessionLookup;
    }

    /**
     * Codex iter-25 absorb: prod log'da raw jti/sid binding identifier'ları
     * truncate edilir (token değil, ama session reference; PII/replay surface
     * minimize). Audit tablosunda full saklanır, sadece log'da truncated.
     */
    private static String truncateForLog(String value) {
        if (value == null || value.length() <= 12) {
            return value;
        }
        return value.substring(0, 8) + "..." + value.substring(value.length() - 4);
    }

    /**
     * Extract impersonation context from JWT.
     *
     * @return Empty if token is NOT broker-issued (normal frontend token).
     *         Present if broker token + active DB session match.
     * @throws ImpersonationContextException if broker token but DB session
     *         missing/expired/inactive (fail-closed).
     */
    public Optional<ImpersonationContext> extract(Jwt jwt) {
        if (jwt == null) {
            return Optional.empty();
        }

        String azp = jwt.getClaimAsString("azp");
        if (azp == null || !brokerAzp.equals(azp)) {
            // Normal frontend/auth-service token — not broker-issued; skip lookup.
            return Optional.empty();
        }

        // Broker-issued token — DB session lookup ZORUNLU.
        String issuer = jwt.getClaimAsString("iss");
        String jti = jwt.getId();
        String sid = jwt.getClaimAsString("sid");
        String targetSubject = jwt.getSubject();

        if (issuer == null || jti == null || sid == null || targetSubject == null) {
            log.warn("Broker-issued token missing required claims (iss/jti/sid/sub) — fail-closed reject");
            if (requireBrokerSessionLookup) {
                throw new ImpersonationContextException(
                        "Broker-issued token missing required claims");
            }
            return Optional.empty();
        }

        Instant now = Instant.now();
        Optional<ImpersonationSession> sessionOpt = sessionRepository.findActiveByTokenBinding(
                issuer, jti, sid, now);

        if (sessionOpt.isEmpty()) {
            log.warn("Broker-issued token but no active impersonation session: issuer={} jti={} sid={} — fail-closed",
                    issuer, truncateForLog(jti), truncateForLog(sid));
            if (requireBrokerSessionLookup) {
                throw new ImpersonationContextException(
                        "Broker-issued token requires active impersonation session");
            }
            return Optional.empty();
        }

        ImpersonationSession session = sessionOpt.get();

        // Defensive: target_subject mismatch fail-closed (replay/forge guard)
        if (!targetSubject.equals(session.getTargetSubject())) {
            log.warn("Token sub mismatch with session target_subject: jti={} sid={} — fail-closed",
                    truncateForLog(jti), truncateForLog(sid));
            throw new ImpersonationContextException(
                    "Token subject does not match session target_subject");
        }

        ImpersonationContext context = new ImpersonationContext(
                session.getId(),
                session.getImpersonatorUserId(),
                session.getImpersonatorSubject(),
                session.getImpersonatorEmail(),
                session.getTargetUserId(),
                session.getTargetSubject(),
                session.getTargetEmail(),
                session.getReason(),
                session.getStartedAt());

        log.debug("Impersonation context resolved: session_id={} impersonator={} target={}",
                context.sessionId(), context.impersonatorUserId(), context.targetUserId());
        return Optional.of(context);
    }

    /**
     * Authoritative impersonation context — DB session'dan resolve edilmiş.
     * impersonator_user_id JWT'den DEĞİL session row'dan gelir.
     */
    public record ImpersonationContext(
            java.util.UUID sessionId,
            Long impersonatorUserId,
            String impersonatorSubject,
            String impersonatorEmail,
            Long targetUserId,
            String targetSubject,
            String targetEmail,
            String reason,
            Instant startedAt) {
    }

    /**
     * Fail-closed reject signal for broker-issued tokens missing/invalid
     * DB session.
     *
     * <p>Codex iter-25 absorb: filter/controller chain'inde 500 yerine
     * 403 IMPERSONATION_SESSION_REQUIRED'a map edilmek üzere ayrı tip.
     * Mapping PR-B Step 2 exception handler'da yapılır
     * ({@code @ControllerAdvice} class veya Spring Security
     * {@code AuthenticationEntryPoint}). Bu commit sadece exception
     * tipini ve sabit error code'unu sağlar.
     */
    public static class ImpersonationContextException extends RuntimeException {
        public static final String ERROR_CODE = "IMPERSONATION_SESSION_REQUIRED";

        public ImpersonationContextException(String message) {
            super(message);
        }

        public String errorCode() {
            return ERROR_CODE;
        }
    }
}
