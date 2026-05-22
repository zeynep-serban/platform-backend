package com.serban.notify.repository;

import com.serban.notify.domain.EmailBounceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * Repository for {@link EmailBounceEvent} — the bounce/complaint dedupe
 * ledger (Faz 23.8 M7 T4.3.5 FBL, Codex 019e4fc6).
 *
 * <p>The {@link #insertIfAbsent} native query is the idempotency anchor:
 * {@code INSERT ... ON CONFLICT (event_fingerprint) DO NOTHING} returns
 * the affected row count — {@code 1} means a fresh event (caller proceeds
 * to suppression upsert), {@code 0} means a duplicate already processed
 * (caller is metric-only). This mirrors the
 * {@code DeadLetterRepository.insertIfAbsent} concurrency-safe pattern:
 * no {@code DataIntegrityViolationException} catch-in-transaction, so the
 * surrounding transaction is never aborted by a concurrent duplicate.
 */
@Repository
public interface EmailBounceEventRepository
    extends JpaRepository<EmailBounceEvent, String> {

    /**
     * Atomic idempotent insert. Returns {@code 1} when the row was newly
     * inserted, {@code 0} when {@code event_fingerprint} already existed.
     *
     * <p>Native query (not JPA persist) so {@code ON CONFLICT DO NOTHING}
     * is concurrency-safe across multiple FBL worker pods without raising
     * a constraint-violation exception.
     */
    @Modifying
    @Query(value = """
        INSERT INTO notify.email_bounce_event
            (event_fingerprint, org_id, recipient_hash, provider,
             provider_msg_id, source, classification, received_at,
             summary_redacted)
        VALUES
            (:eventFingerprint, :orgId, :recipientHash, :provider,
             :providerMsgId, :source, :classification, :receivedAt,
             :summaryRedacted)
        ON CONFLICT (event_fingerprint) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("eventFingerprint") String eventFingerprint,
        @Param("orgId") String orgId,
        @Param("recipientHash") String recipientHash,
        @Param("provider") String provider,
        @Param("providerMsgId") String providerMsgId,
        @Param("source") String source,
        @Param("classification") String classification,
        @Param("receivedAt") OffsetDateTime receivedAt,
        @Param("summaryRedacted") String summaryRedacted
    );
}
