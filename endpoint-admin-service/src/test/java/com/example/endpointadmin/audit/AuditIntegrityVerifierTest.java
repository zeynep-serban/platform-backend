package com.example.endpointadmin.audit;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.service.EndpointAuditService;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-016 — H2 integration tests for {@link AuditIntegrityVerifier} and the
 * {@link EndpointAuditService} hash-chain write path (Codex 019e4f8e).
 *
 * <p>The Postgres-only append-only trigger + advisory lock are covered
 * separately by {@code AuditHashChainPostgresIntegrationTest} (Testcontainers).
 * This suite covers chain construction + verification + tamper detection,
 * which need no real Postgres.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class AuditIntegrityVerifierTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private EndpointAuditService auditService;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private EntityManager entityManager;

    private EndpointAuditEvent record(UUID tenant, String eventType, Map<String, Object> metadata) {
        return auditService.record(tenant, null, null, eventType,
                "TEST_ACTION", "subject@test", "corr-" + eventType, metadata, null, null);
    }

    @Test
    void emptyChainIsValid() {
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT_A);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isZero();
    }

    @Test
    void singleGenesisRowVerifies() {
        record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT_A);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(1);
    }

    @Test
    void genesisRowHasNullPrevHash() {
        EndpointAuditEvent genesis = record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        entityManager.flush();
        entityManager.clear();
        EndpointAuditEvent reloaded = auditRepository.findById(genesis.getId()).orElseThrow();
        assertThat(reloaded.getPrevEventHash()).isNull();
        assertThat(reloaded.getEventHash()).isNotNull().hasSize(64);
        assertThat(reloaded.getEventHashAlg()).isEqualTo(AuditChainSupport.HASH_ALGORITHM);
        assertThat(reloaded.getEventHashVersion()).isEqualTo(AuditChainSupport.HASH_VERSION);
    }

    @Test
    void multiRowLinkedChainVerifies() {
        record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        record(TENANT_A, "EVENT_2", Map.of("k", "v2"));
        record(TENANT_A, "EVENT_3", Map.of("k", "v3"));
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT_A);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(3);
    }

    @Test
    void chainLinkageIsCorrect() {
        EndpointAuditEvent e1 = record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        EndpointAuditEvent e2 = record(TENANT_A, "EVENT_2", Map.of("k", "v2"));
        entityManager.flush();
        entityManager.clear();
        EndpointAuditEvent r1 = auditRepository.findById(e1.getId()).orElseThrow();
        EndpointAuditEvent r2 = auditRepository.findById(e2.getId()).orElseThrow();
        // e2's prev_event_hash must equal e1's event_hash.
        assertThat(r2.getPrevEventHash()).isEqualTo(r1.getEventHash());
    }

    @Test
    void tenantChainsAreIndependent() {
        record(TENANT_A, "A_EVENT_1", Map.of("k", "a1"));
        record(TENANT_B, "B_EVENT_1", Map.of("k", "b1"));
        record(TENANT_A, "A_EVENT_2", Map.of("k", "a2"));
        // Each tenant has its own GENESIS + chain.
        assertThat(verifier.verifyTenant(TENANT_A).checkedCount()).isEqualTo(2);
        assertThat(verifier.verifyTenant(TENANT_B).checkedCount()).isEqualTo(1);
        assertThat(verifier.verifyTenant(TENANT_A).valid()).isTrue();
        assertThat(verifier.verifyTenant(TENANT_B).valid()).isTrue();
    }

    @Test
    void tamperedPayloadIsDetected() {
        record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        EndpointAuditEvent target = record(TENANT_A, "EVENT_2", Map.of("k", "v2"));
        record(TENANT_A, "EVENT_3", Map.of("k", "v3"));
        entityManager.flush();
        entityManager.clear();

        // Simulate tamper: mutate the stored action of EVENT_2 directly in the
        // DB (H2 has no append-only trigger; that path is Postgres-only). The
        // stored event_hash now no longer matches a fresh re-hash.
        entityManager.createNativeQuery(
                        "UPDATE endpoint_audit_events SET action = 'TAMPERED' WHERE id = :id")
                .setParameter("id", target.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT_A);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstFailureEventId()).isEqualTo(target.getId());
        assertThat(result.message()).contains("Tamper detected");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordOutsideTransactionIsRejected() {
        // BE-016 (Codex 019e4f8e P1-2): record() is @Transactional(MANDATORY).
        // Suspending the @DataJpaTest transaction (NOT_SUPPORTED) means there
        // is no active transaction — record() must fail fast rather than
        // silently losing the advisory-lock chain serialization guarantee.
        assertThatThrownBy(() -> record(TENANT_A, "NO_TX_EVENT", Map.of("k", "v")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void tamperedEventHashIsDetected() {
        EndpointAuditEvent target = record(TENANT_A, "EVENT_1", Map.of("k", "v1"));
        record(TENANT_A, "EVENT_2", Map.of("k", "v2"));
        entityManager.flush();
        entityManager.clear();

        // Corrupt the stored event_hash of the GENESIS row directly.
        entityManager.createNativeQuery(
                        "UPDATE endpoint_audit_events SET event_hash = :h WHERE id = :id")
                .setParameter("h", "0".repeat(64))
                .setParameter("id", target.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT_A);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstFailureEventId()).isEqualTo(target.getId());
    }
}
