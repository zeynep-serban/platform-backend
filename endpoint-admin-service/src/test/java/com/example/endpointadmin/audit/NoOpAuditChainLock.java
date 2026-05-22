package com.example.endpointadmin.audit;

import java.util.UUID;

/**
 * BE-016 — No-op {@link AuditChainLock} for H2 {@code @DataJpaTest} suites.
 *
 * <p>H2 has no {@code pg_advisory_xact_lock}; H2 unit tests run single-threaded
 * inside one transaction, so the lock is a functional no-op there. The genuine
 * Postgres advisory-lock serialization is exercised by
 * {@code AuditHashChainPostgresIntegrationTest} via Testcontainers.
 */
public class NoOpAuditChainLock implements AuditChainLock {

    @Override
    public void lockTenantChain(UUID tenantId) {
        // intentionally empty — see class javadoc
    }
}
