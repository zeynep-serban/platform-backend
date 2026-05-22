package com.example.endpointadmin.audit;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.service.EndpointAuditService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-016 — Postgres-only integration tests for the audit hash-chain
 * (Codex 019e4f8e plan-time AGREE + post-impl review absorb). Verifies the
 * parts that H2 cannot exercise:
 * <ul>
 *   <li>Flyway V4 migration applies cleanly on a real Postgres engine;</li>
 *   <li>the {@code endpoint_audit_events} append-only trigger rejects direct
 *       {@code UPDATE} and {@code DELETE};</li>
 *   <li>the insert-enforcement trigger rejects a post-V4 null-hash insert
 *       (Codex P2-4);</li>
 *   <li>the real {@link PgAdvisoryAuditChainLock} actually serializes
 *       concurrent same-tenant writers into one linear chain (Codex P2-5).</li>
 * </ul>
 *
 * <p>Uses the genuine {@link PgAdvisoryAuditChainLock}, NOT the H2 no-op.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointAuditService.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class AuditHashChainPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("endpoint_admin")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema on the real Postgres engine.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Schema alignment (CI fix): base application.yml flyway default-schema
        // defaults to `endpoint_admin_service` while the @DataJpaTest had
        // Hibernate validating `public` → "missing table" mismatch. Pin BOTH
        // Flyway and Hibernate to `public` so migrations + validation agree.
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    private static final UUID TENANT = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Autowired
    private EndpointAuditService auditService;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private EndpointAuditEvent record(String eventType) {
        return auditService.record(TENANT, null, null, eventType,
                "TEST_ACTION", "subject@test", "corr-" + eventType,
                Map.of("event", eventType), null, null);
    }

    @Test
    void flywayV4MigrationAppliedHashColumnsPresent() {
        Object result = entityManager.createNativeQuery(
                        "SELECT count(*) FROM endpoint_audit_events "
                                + "WHERE event_hash IS NULL OR prev_event_hash IS NULL "
                                + "OR event_hash_alg IS NULL OR event_hash_version IS NULL")
                .getSingleResult();
        assertThat(result).isNotNull();
    }

    @Test
    void chainWritePathProducesVerifiableChainOnPostgres() {
        record("PG_EVENT_1");
        record("PG_EVENT_2");
        record("PG_EVENT_3");
        entityManager.flush();
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(3);
    }

    @Test
    void appendOnlyTriggerRejectsDirectUpdate() {
        EndpointAuditEvent event = record("PG_UPDATE_TARGET");
        entityManager.flush();
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "UPDATE endpoint_audit_events SET action = 'TAMPERED' WHERE id = :id")
                    .setParameter("id", event.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    void appendOnlyTriggerRejectsDirectDelete() {
        EndpointAuditEvent event = record("PG_DELETE_TARGET");
        entityManager.flush();
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "DELETE FROM endpoint_audit_events WHERE id = :id")
                    .setParameter("id", event.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    void insertEnforcementTriggerRejectsNullHashInsert() {
        // BE-016 (Codex 019e4f8e P2-4): a post-V4 direct insert that omits the
        // hash columns must be rejected — otherwise an unhashed row would slip
        // past the verifier (which only walks hashed rows).
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "INSERT INTO endpoint_audit_events "
                                    + "(id, tenant_id, event_type, action, metadata, occurred_at) "
                                    + "VALUES (:id, :tenant, 'X', 'Y', '{}'::jsonb, now())")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("tenant", TENANT)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("requires event_hash");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSameTenantWritersProduceLinearChain() throws Exception {
        // BE-016 (Codex 019e4f8e P2-5): genuine advisory-lock serialization.
        // 12 threads each open their own transaction and call record() for the
        // SAME tenant. If pg_advisory_xact_lock works, all 12 serialize into
        // one linear chain; if it did NOT, concurrent writers would read the
        // same tail and fork the chain (verifier would then detect a break).
        UUID concurrentTenant = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        int writers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        try {
            for (int i = 0; i < writers; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        tx.executeWithoutResult(status -> auditService.record(
                                concurrentTenant, null, null, "CONCURRENT_" + idx,
                                "TEST_ACTION", "subject@test", "corr-" + idx,
                                Map.of("idx", idx), null, null));
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown(); // release all writers at once
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).as("no concurrent writer should fail").isZero();
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(concurrentTenant);
        assertThat(result.valid())
                .as("advisory lock must serialize writers into one intact chain")
                .isTrue();
        assertThat(result.checkedCount()).isEqualTo(writers);

        // Cleanup: append-only trigger blocks DELETE, so drop+recreate is not
        // attempted — the per-class container is discarded after the suite.
        assertThat(Instant.now()).isNotNull();
    }
}
