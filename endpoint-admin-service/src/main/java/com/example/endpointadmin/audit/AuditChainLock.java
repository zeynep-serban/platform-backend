package com.example.endpointadmin.audit;

import java.util.UUID;

/**
 * BE-016 — Tenant-scoped serialization lock for the audit hash-chain
 * (Codex 019e4f8e plan-time AGREE).
 *
 * <p>Before a new hashed audit row is written, the writer must hold the
 * per-tenant chain lock so that two concurrent {@code record()} calls for the
 * same tenant cannot both read the same chain tail and fork the chain.
 *
 * <p>The production implementation ({@link PgAdvisoryAuditChainLock}) uses a
 * Postgres transaction-scoped advisory lock; it is released automatically when
 * the surrounding transaction commits or rolls back. Unit tests on H2 supply a
 * no-op implementation (H2 has no {@code pg_advisory_xact_lock}); real lock
 * behavior is exercised by the Postgres Testcontainers integration test.
 */
public interface AuditChainLock {

    /**
     * Acquire the per-tenant audit-chain lock within the current transaction.
     * Must be called inside an active transaction; the lock is held until that
     * transaction ends.
     *
     * @param tenantId tenant whose chain is being appended to
     */
    void lockTenantChain(UUID tenantId);

    /**
     * Derive a stable 64-bit lock key from a tenant UUID. XOR of the two 64-bit
     * halves — deterministic, full-width (Codex 019e4f8e: do not use the
     * 32-bit {@code UUID.hashCode()}). Key collisions only over-serialize two
     * unrelated tenants very rarely; correctness is unaffected.
     */
    static long lockKey(UUID tenantId) {
        return tenantId.getMostSignificantBits() ^ tenantId.getLeastSignificantBits();
    }
}
