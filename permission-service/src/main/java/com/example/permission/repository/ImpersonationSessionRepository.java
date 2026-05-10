package com.example.permission.repository;

import com.example.permission.model.ImpersonationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Impersonation session lookup repository.
 *
 * <p>Codex 019e0dfb iter-10/19 design — runtime middleware hot path:
 * jti+sid+issuer composite lookup with status='ACTIVE' filter.
 *
 * <p>HPA-managed assumption (Codex iter-19): pod-independent; multiple
 * podlardan aynı sonucu verir, DB authoritative.
 */
@Repository
public interface ImpersonationSessionRepository extends JpaRepository<ImpersonationSession, UUID> {

    /**
     * Runtime middleware lookup — exchange token jti/sid/issuer ile aktif
     * session bulma. Hot path; ix_impersonation_sessions_active_lookup index.
     *
     * <p>Single result (UNIQUE issuer+jti). Returns Optional.empty() eğer:
     * <ul>
     *   <li>Session yok</li>
     *   <li>Session ended (status != ACTIVE veya ended_at IS NOT NULL)</li>
     *   <li>Session expired (expires_at <= now)</li>
     * </ul>
     */
    @Query("""
            SELECT s FROM ImpersonationSession s
            WHERE s.issuer = :issuer
              AND s.jti = :jti
              AND s.sid = :sid
              AND s.status = com.example.permission.model.ImpersonationSession.Status.ACTIVE
              AND s.endedAt IS NULL
              AND s.expiresAt > :now
            """)
    Optional<ImpersonationSession> findActiveByTokenBinding(
            @Param("issuer") String issuer,
            @Param("jti") String jti,
            @Param("sid") String sid,
            @Param("now") Instant now);

    /**
     * Single-active-session policy check — bir kullanıcı en fazla 1 aktif
     * impersonation session'ı olabilir. POST /sessions sırasında 409
     * dönmek için.
     */
    @Query("""
            SELECT s FROM ImpersonationSession s
            WHERE s.impersonatorUserId = :impersonatorUserId
              AND s.status = com.example.permission.model.ImpersonationSession.Status.ACTIVE
              AND s.endedAt IS NULL
              AND s.expiresAt > :now
            """)
    Optional<ImpersonationSession> findActiveByImpersonator(
            @Param("impersonatorUserId") Long impersonatorUserId,
            @Param("now") Instant now);

    /**
     * Audit dashboard — actor history (PR-D query pattern).
     * ix_impersonation_sessions_actor_target_started index.
     */
    @Query("""
            SELECT s FROM ImpersonationSession s
            WHERE s.impersonatorUserId = :impersonatorUserId
            ORDER BY s.startedAt DESC
            """)
    List<ImpersonationSession> findByImpersonatorOrderByStartedDesc(
            @Param("impersonatorUserId") Long impersonatorUserId);

    /**
     * TTL sweeper — expired sessions to mark as EXPIRED.
     * Scheduled job pattern (cron).
     */
    @Modifying
    @Query("""
            UPDATE ImpersonationSession s
            SET s.status = com.example.permission.model.ImpersonationSession.Status.EXPIRED,
                s.endedAt = :now,
                s.endedReason = 'SYSTEM_SWEEP'
            WHERE s.status = com.example.permission.model.ImpersonationSession.Status.ACTIVE
              AND s.endedAt IS NULL
              AND s.expiresAt <= :now
            """)
    int sweepExpiredSessions(@Param("now") Instant now);

    /**
     * Stop session by id (DELETE /sessions/current handler).
     * Sets ended_at + status=STOPPED + ended_reason.
     */
    @Modifying
    @Query("""
            UPDATE ImpersonationSession s
            SET s.status = com.example.permission.model.ImpersonationSession.Status.STOPPED,
                s.endedAt = :now,
                s.endedReason = :reason
            WHERE s.id = :id
              AND s.status = com.example.permission.model.ImpersonationSession.Status.ACTIVE
            """)
    int stopSession(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);
}
