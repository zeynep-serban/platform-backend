package com.serban.notify.service;

import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.IdempotencyKey;
import com.serban.notify.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * IdempotencyService — 24h TTL window check via PG advisory lock (Codex 019df9ae Q1 AGREE).
 *
 * <p>Strategy: {@code pg_advisory_xact_lock(stable64BitHash(orgId + RS + key))}
 * + {@code findActiveKey(orgId, key, now)} + INSERT all in single transaction.
 *
 * <p>Why advisory lock not partial unique index:
 * Codex 019df86f post-impl bulgu #2 absorb: V1 migration originally had
 * {@code CREATE UNIQUE INDEX idx_idem_active ... WHERE expires_at > NOW()}
 * but PG forbids volatile/stable functions in partial index predicates
 * ({@code now()} is not immutable). Service-layer transactional advisory lock
 * achieves equivalent uniqueness guarantee.
 *
 * <p>Concurrent submit safety: same {@code (org_id, idempotency_key)} pair → both
 * transactions request same advisory lock → 1 acquires, other waits → first
 * INSERT, second sees existing key, returns original intent_id (REPLAYED).
 *
 * <p>Lock key calculation: SHA-256 first 8 bytes → long. Stable across JVM
 * runs (Codex Q1 önerisi: hashtextextended yerine deterministic 64-bit).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final char RECORD_SEPARATOR = '\u001F';

    private final IdempotencyKeyRepository repository;
    private final NotifyConfig config;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyService(IdempotencyKeyRepository repository, NotifyConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /**
     * Check if (orgId, key) has active intent within idempotency window.
     *
     * <p>MUST be called inside parent {@code @Transactional}. Acquires PG advisory
     * lock; auto-released at transaction commit/rollback.
     *
     * @return Optional.of(originalIntentId) if active duplicate found,
     *         Optional.empty() if new submission allowed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> findActiveOriginal(String orgId, String key) {
        long lockId = stable64BitHash(orgId, key);
        // Advisory lock — released at transaction end
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
            .setParameter("lockId", lockId)
            .getSingleResult();
        log.debug("idempotency advisory lock acquired: orgId={} keyHash={}", orgId, lockId);

        Optional<IdempotencyKey> active = repository.findActiveKey(
            orgId, key, OffsetDateTime.now()
        );
        return active.map(IdempotencyKey::getIntentId);
    }

    /**
     * Insert new idempotency_key row (caller must have called {@link #findActiveOriginal}
     * inside same transaction and confirmed empty result).
     *
     * @param intentId fresh intent id assigned by caller
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerKey(String orgId, String key, String intentId) {
        IdempotencyKey row = new IdempotencyKey();
        row.setOrgId(orgId);
        row.setIdempotencyKey(key);
        row.setIntentId(intentId);
        row.setExpiresAt(OffsetDateTime.now().plusHours(config.idempotency().windowHours()));
        repository.save(row);
        log.debug("idempotency key registered: orgId={} intentId={} expiresIn={}h",
            orgId, intentId, config.idempotency().windowHours());
    }

    /**
     * Compute stable 64-bit lock id from (orgId, key).
     *
     * <p>SHA-256 first 8 bytes interpreted as signed long. Deterministic across
     * JVM runs (no random salt). Collision probability ~2^-32 acceptable for
     * advisory lock granularity (collision causes serialization, not error).
     */
    static long stable64BitHash(String orgId, String key) {
        String input = orgId + RECORD_SEPARATOR + key;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (hash[i] & 0xFFL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
