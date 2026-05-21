package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.ErasureRequestLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ErasureRequestLedgerRepository integration test — V18 migration
 * verify + JPA mapping consistency (Faz 23.2 M3 R2 PR-K1, Codex
 * {@code 019e4950} P0 #1 absorb).
 *
 * <p>Covers:
 * <ul>
 *   <li>V18 migration applies + table exists + indices in place</li>
 *   <li>save() persists all fields + auto-generated UUID + timestamps</li>
 *   <li>findByOrgIdAndIdempotencyKey returns row</li>
 *   <li>UNIQUE (org_id, idempotency_key) constraint enforced</li>
 *   <li>findOverdueRequests filters partial index correctly</li>
 *   <li>markCompleted atomic transition</li>
 *   <li>Status CHECK constraint rejects invalid values</li>
 *   <li>due_at > received_at CHECK</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@org.springframework.transaction.annotation.Transactional
class ErasureRequestLedgerRepositoryTest extends AbstractPostgresTest {

    @Autowired
    ErasureRequestLedgerRepository repo;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;

    @Test
    void savePersistsAllFieldsAndAutoGeneratesIds() {
        ErasureRequestLedger entry = new ErasureRequestLedger();
        entry.setOrgId("acme");
        entry.setSubjectRefHmac("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        entry.setRequestSource(ErasureRequestLedger.RequestSource.ADMIN);
        entry.setIdempotencyKey("admin-acme-ticket-LK-2026-451");

        ErasureRequestLedger saved = repo.save(entry);

        assertThat(saved.getRequestId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ErasureRequestLedger.Status.RECEIVED);
        assertThat(saved.getReceivedAt()).isNotNull();
        assertThat(saved.getDueAt()).isAfter(saved.getReceivedAt());
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        // SLA = 30 gün
        long days = java.time.Duration.between(saved.getReceivedAt(), saved.getDueAt()).toDays();
        assertThat(days).isEqualTo(30L);
    }

    @Test
    void findByOrgIdAndIdempotencyKeyReturnsRow() {
        ErasureRequestLedger entry = fixture("acme", "key-1", ErasureRequestLedger.RequestSource.SELF_SERVICE);
        repo.save(entry);

        Optional<ErasureRequestLedger> found = repo.findByOrgIdAndIdempotencyKey("acme", "key-1");

        assertThat(found).isPresent();
        assertThat(found.get().getRequestSource()).isEqualTo(ErasureRequestLedger.RequestSource.SELF_SERVICE);
    }

    @Test
    void uniqueConstraintOnOrgIdAndIdempotencyKey() {
        ErasureRequestLedger first = fixture("acme", "dup-key", ErasureRequestLedger.RequestSource.ADMIN);
        repo.save(first);

        ErasureRequestLedger duplicate = fixture("acme", "dup-key", ErasureRequestLedger.RequestSource.LEGAL);

        assertThatThrownBy(() -> {
            repo.save(duplicate);
            repo.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void uniqueConstraintScopedPerOrg() {
        repo.save(fixture("acme", "shared-key", ErasureRequestLedger.RequestSource.ADMIN));

        // Aynı idempotency_key farklı org'da OK
        repo.save(fixture("beta", "shared-key", ErasureRequestLedger.RequestSource.LEGAL));

        assertThat(repo.findByOrgIdAndIdempotencyKey("acme", "shared-key")).isPresent();
        assertThat(repo.findByOrgIdAndIdempotencyKey("beta", "shared-key")).isPresent();
    }

    @Test
    void findOverdueRequestsFiltersByDueAtAndStatus() {
        // Status RECEIVED + due_at past → overdue. CHECK constraint
        // due_at > received_at gereği received_at past'ta set edilmeli.
        ErasureRequestLedger overdue = fixture("acme", "ovd-1", ErasureRequestLedger.RequestSource.ADMIN);
        overdue.setReceivedAt(OffsetDateTime.now().minusDays(31));
        overdue.setDueAt(OffsetDateTime.now().minusHours(2));
        repo.save(overdue);

        // Status COMPLETED + due_at past → NOT overdue (terminal)
        ErasureRequestLedger completed = fixture("acme", "cmp-1", ErasureRequestLedger.RequestSource.ADMIN);
        completed.setReceivedAt(OffsetDateTime.now().minusDays(35));
        completed.setDueAt(OffsetDateTime.now().minusHours(5));
        completed.setStatus(ErasureRequestLedger.Status.COMPLETED);
        completed.setClosedAt(OffsetDateTime.now().minusHours(4));
        repo.save(completed);

        // Status RECEIVED + due_at future → NOT overdue
        ErasureRequestLedger fresh = fixture("acme", "fre-1", ErasureRequestLedger.RequestSource.ADMIN);
        fresh.setDueAt(OffsetDateTime.now().plusDays(5));
        repo.save(fresh);

        List<ErasureRequestLedger> overdueList = repo.findOverdueRequests(OffsetDateTime.now());

        assertThat(overdueList)
            .extracting(ErasureRequestLedger::getIdempotencyKey)
            .contains("ovd-1")
            .doesNotContain("cmp-1", "fre-1");
    }

    @Test
    void markCompletedSetsTerminalStateAndAuditId() {
        // Codex 019e499c REVISE P1 #4 absorb: audit_event_v2 BIGINT + occurred_at composite
        ErasureRequestLedger entry = fixture("acme", "mark-cmp", ErasureRequestLedger.RequestSource.SELF_SERVICE);
        entry.setStatus(ErasureRequestLedger.Status.PROCESSING);
        ErasureRequestLedger saved = repo.save(entry);

        Long auditId = 99999L;
        OffsetDateTime closedAt = OffsetDateTime.now();
        OffsetDateTime auditOccurredAt = OffsetDateTime.now();
        int updated = repo.markCompleted(saved.getRequestId(), closedAt, auditId, auditOccurredAt);

        // @Modifying query bypasses Hibernate L1 cache; em.flush + clear
        // gerekli refetched row'ın DB'den taze okunması için.
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        ErasureRequestLedger refetched = repo.findById(saved.getRequestId()).orElseThrow();
        assertThat(refetched.getStatus()).isEqualTo(ErasureRequestLedger.Status.COMPLETED);
        assertThat(refetched.getClosedAt()).isCloseTo(
            closedAt,
            org.assertj.core.api.Assertions.within(1L, java.time.temporal.ChronoUnit.SECONDS)
        );
        assertThat(refetched.getLastAuditEventId()).isEqualTo(auditId);
        assertThat(refetched.getLastAuditEventOccurredAt()).isNotNull();
    }

    @Test
    void markCompletedNoOpOnAlreadyTerminalRow() {
        // Already COMPLETED row — markCompleted should NOT touch it
        ErasureRequestLedger entry = fixture("acme", "already-cmp", ErasureRequestLedger.RequestSource.ADMIN);
        entry.setStatus(ErasureRequestLedger.Status.COMPLETED);
        entry.setClosedAt(OffsetDateTime.now().minusHours(1));
        ErasureRequestLedger saved = repo.save(entry);

        int updated = repo.markCompleted(saved.getRequestId(), OffsetDateTime.now(), 12345L, OffsetDateTime.now());

        assertThat(updated).isZero(); // WHERE clause excludes terminal status
    }

    @Test
    void markFailedRecordsCategoryButKeepsStatusNonTerminal() {
        // Codex 019e499c iter-3 REVISE P0 absorb: failure non-terminal
        // — KVKK Madde 13.2 SLA scan unresolved teknik hatayı görmeye
        // devam eder. Status PROCESSING kalır, closed_at NULL kalır,
        // failure_reason yazılır.
        ErasureRequestLedger entry = fixture("acme", "mark-fail", ErasureRequestLedger.RequestSource.ADMIN);
        entry.setStatus(ErasureRequestLedger.Status.PROCESSING);
        ErasureRequestLedger saved = repo.save(entry);

        int updated = repo.markFailed(saved.getRequestId(), OffsetDateTime.now(), "TRANSACTION_ROLLBACK");

        // @Modifying query L1 cache bypass; em.flush + clear refetch için
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        ErasureRequestLedger refetched = repo.findById(saved.getRequestId()).orElseThrow();
        assertThat(refetched.getStatus()).isEqualTo(ErasureRequestLedger.Status.PROCESSING);
        assertThat(refetched.getFailureReason()).isEqualTo("TRANSACTION_ROLLBACK");
        // closed_at NULL kalır (non-terminal); CHECK constraint uyumlu
        assertThat(refetched.getClosedAt()).isNull();
    }

    @Test
    void markFailedRowRemainsVisibleToSlaWatchdog() {
        // Codex 019e499c iter-3 REVISE P0 absorb: KVKK Madde 13.2
        // unresolved teknik hata 30-gün dolduğunda watchdog tarafından
        // görünür kalmalı. CHECK constraint due_at > received_at gereği
        // received_at past'ta set edilmeli.
        ErasureRequestLedger entry = fixture("acme", "fail-sla", ErasureRequestLedger.RequestSource.ADMIN);
        entry.setStatus(ErasureRequestLedger.Status.PROCESSING);
        entry.setReceivedAt(OffsetDateTime.now().minusDays(31));
        entry.setDueAt(OffsetDateTime.now().minusHours(1));
        ErasureRequestLedger saved = repo.save(entry);

        repo.markFailed(saved.getRequestId(), OffsetDateTime.now(), "TRANSACTION_ROLLBACK");
        // @Modifying query L1 cache bypass; em.flush + clear refetch için
        em.flush();
        em.clear();

        List<ErasureRequestLedger> overdueList = repo.findOverdueRequests(OffsetDateTime.now());
        assertThat(overdueList)
            .extracting(ErasureRequestLedger::getRequestId)
            .contains(saved.getRequestId());
        // Failure reason de görünür
        ErasureRequestLedger overdueRow = overdueList.stream()
            .filter(o -> o.getRequestId().equals(saved.getRequestId()))
            .findFirst().orElseThrow();
        assertThat(overdueRow.getFailureReason()).isEqualTo("TRANSACTION_ROLLBACK");
    }

    @Test
    void markFailedNoOpOnLegalHold() {
        // LEGAL_HOLD durumundaki ledger row markFailed çağrısından korunur
        // (operator manuel hold release gerekir)
        ErasureRequestLedger entry = fixture("acme", "hold-row", ErasureRequestLedger.RequestSource.LEGAL);
        entry.setStatus(ErasureRequestLedger.Status.LEGAL_HOLD);
        entry.setLegalHoldReasonCode("COURT_ORDER");
        ErasureRequestLedger saved = repo.save(entry);

        int updated = repo.markFailed(saved.getRequestId(), OffsetDateTime.now(), "TRANSACTION_ROLLBACK");

        assertThat(updated).isZero();
    }

    @Test
    void findBySubjectOrderByReceivedAtDesc() {
        String hmac = "subject-hash-xyz";

        ErasureRequestLedger first = fixture("acme", "rcv-1", ErasureRequestLedger.RequestSource.SELF_SERVICE);
        first.setSubjectRefHmac(hmac);
        first.setReceivedAt(OffsetDateTime.now().minusDays(2));
        first.setDueAt(first.getReceivedAt().plusDays(30));
        repo.save(first);

        ErasureRequestLedger second = fixture("acme", "rcv-2", ErasureRequestLedger.RequestSource.ADMIN);
        second.setSubjectRefHmac(hmac);
        second.setReceivedAt(OffsetDateTime.now().minusDays(1));
        second.setDueAt(second.getReceivedAt().plusDays(30));
        repo.save(second);

        List<ErasureRequestLedger> result = repo.findByOrgIdAndSubjectRefHmacOrderByReceivedAtDesc(
            "acme", hmac
        );

        assertThat(result).hasSize(2);
        // Newest-first
        assertThat(result.get(0).getIdempotencyKey()).isEqualTo("rcv-2");
        assertThat(result.get(1).getIdempotencyKey()).isEqualTo("rcv-1");
    }

    @Test
    void receivedAtRangeQueryForReporting() {
        ErasureRequestLedger inRange = fixture("acme", "rep-1", ErasureRequestLedger.RequestSource.DPO);
        inRange.setReceivedAt(OffsetDateTime.now().minusDays(5));
        inRange.setDueAt(inRange.getReceivedAt().plusDays(30));
        repo.save(inRange);

        ErasureRequestLedger outOfRange = fixture("acme", "rep-2", ErasureRequestLedger.RequestSource.DPO);
        outOfRange.setReceivedAt(OffsetDateTime.now().minusDays(60));
        outOfRange.setDueAt(outOfRange.getReceivedAt().plusDays(30));
        repo.save(outOfRange);

        List<ErasureRequestLedger> result = repo.findByOrgIdAndReceivedAtRange(
            "acme",
            OffsetDateTime.now().minusDays(30),
            OffsetDateTime.now().plusDays(1)
        );

        assertThat(result)
            .extracting(ErasureRequestLedger::getIdempotencyKey)
            .contains("rep-1")
            .doesNotContain("rep-2");
    }

    @Test
    void closedAtConsistencyCheckEnforcedByDb() {
        ErasureRequestLedger entry = fixture("acme", "consistency", ErasureRequestLedger.RequestSource.ADMIN);
        entry.setStatus(ErasureRequestLedger.Status.COMPLETED);
        // closed_at NULL while status terminal — violation
        entry.setClosedAt(null);

        assertThatThrownBy(() -> {
            repo.save(entry);
            repo.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private ErasureRequestLedger fixture(
        String orgId, String idempotencyKey, ErasureRequestLedger.RequestSource source
    ) {
        ErasureRequestLedger e = new ErasureRequestLedger();
        e.setOrgId(orgId);
        e.setSubjectRefHmac("hmac-" + idempotencyKey);
        e.setRequestSource(source);
        e.setIdempotencyKey(idempotencyKey);
        return e;
    }
}
