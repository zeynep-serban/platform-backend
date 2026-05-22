package com.example.endpointadmin.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * BE-016 — Postgres transaction-scoped advisory lock implementation of
 * {@link AuditChainLock} (Codex 019e4f8e plan-time AGREE).
 *
 * <p>Issues {@code SELECT pg_advisory_xact_lock(?)} with a stable tenant-derived
 * 64-bit key. The lock is held for the remainder of the current transaction and
 * released automatically on commit/rollback — no explicit unlock path, so it is
 * safe against leaks even if the audit write throws.
 *
 * <p>This bean is component-scanned in the running service. {@code @DataJpaTest}
 * unit suites on H2 import a no-op {@link AuditChainLock} instead (H2 has no
 * {@code pg_advisory_xact_lock}); the Postgres Testcontainers integration test
 * imports this real implementation to verify genuine serialization.
 */
@Component
public class PgAdvisoryAuditChainLock implements AuditChainLock {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void lockTenantChain(UUID tenantId) {
        long key = AuditChainLock.lockKey(tenantId);
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", key)
                .getSingleResult();
    }
}
