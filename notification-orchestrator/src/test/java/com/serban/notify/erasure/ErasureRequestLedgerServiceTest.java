package com.serban.notify.erasure;

import com.serban.notify.domain.ErasureRequestLedger;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.ErasureRequestLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErasureRequestLedgerService unit test — Faz 23.2 M3 R2 PR-K1 (Codex
 * {@code 019e4950} P0 #1 absorb).
 *
 * <p>Covered behaviors:
 * <ul>
 *   <li>openRequest yeni ledger entry yaratır (RECEIVED + due_at 30d)</li>
 *   <li>openRequest idempotent (aynı orgId+key ikinci çağrı mevcut row döner)</li>
 *   <li>concurrent insert race graceful (DataIntegrityViolationException → re-fetch)</li>
 *   <li>markProcessing transition RECEIVED → PROCESSING only</li>
 *   <li>completeRequest COMPLETED + closed_at + audit_event_id</li>
 *   <li>findOverdueRequests SLA breach scan</li>
 *   <li>classifySource Locale.ROOT defensive (self-service, legal, DPO, compliance, admin)</li>
 *   <li>deriveIdempotencyKey self-service günlük key</li>
 * </ul>
 */
class ErasureRequestLedgerServiceTest {

    private ErasureRequestLedgerRepository repo;
    private PiiRedactor piiRedactor;
    private ErasureRequestLedgerService service;

    @BeforeEach
    void setUp() {
        repo = mock(ErasureRequestLedgerRepository.class);
        piiRedactor = mock(PiiRedactor.class);
        when(piiRedactor.hashRecipient(anyString(), anyString(), anyString()))
            .thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        // save returns input entry (mimic JPA persist + @PrePersist
        // — assign UUID + receivedAt + dueAt)
        when(repo.save(any(ErasureRequestLedger.class))).thenAnswer(inv -> {
            ErasureRequestLedger e = inv.getArgument(0);
            if (e.getRequestId() == null) {
                e.setRequestId(UUID.randomUUID());
            }
            if (e.getReceivedAt() == null) {
                e.setReceivedAt(OffsetDateTime.now());
            }
            if (e.getDueAt() == null) {
                e.setDueAt(e.getReceivedAt().plus(ErasureRequestLedger.SLA_DURATION));
            }
            return e;
        });

        service = new ErasureRequestLedgerService(repo, piiRedactor);
    }

    @Test
    void openRequestCreatesNewLedgerEntry() {
        when(repo.findByOrgIdAndIdempotencyKey("acme", "test-key"))
            .thenReturn(Optional.empty());

        ErasureRequestLedger result = service.openRequest(
            "acme", "1204", "ticket-LK-2026-451", "test-key"
        );

        ArgumentCaptor<ErasureRequestLedger> captor =
            ArgumentCaptor.forClass(ErasureRequestLedger.class);
        verify(repo).save(captor.capture());

        ErasureRequestLedger saved = captor.getValue();
        assertThat(saved.getOrgId()).isEqualTo("acme");
        assertThat(saved.getStatus()).isEqualTo(ErasureRequestLedger.Status.RECEIVED);
        assertThat(saved.getIdempotencyKey()).isEqualTo("test-key");
        assertThat(saved.getSubjectRefHmac()).hasSize(64); // HMAC-SHA256 hex
        // due_at = received_at + 30 gün
        assertThat(saved.getDueAt()).isCloseTo(
            saved.getReceivedAt().plusDays(30),
            org.assertj.core.api.Assertions.within(1L, java.time.temporal.ChronoUnit.SECONDS)
        );
        assertThat(result.getRequestId()).isNotNull();
    }

    @Test
    void openRequestIdempotentReturnsExistingRow() {
        UUID existingId = UUID.randomUUID();
        ErasureRequestLedger existing = new ErasureRequestLedger();
        existing.setRequestId(existingId);
        existing.setOrgId("acme");
        existing.setStatus(ErasureRequestLedger.Status.PROCESSING);
        existing.setIdempotencyKey("test-key");
        // Codex 019e499c REVISE P0 #2: subject HMAC eşleşmesi zorunlu
        existing.setSubjectRefHmac(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );

        when(repo.findByOrgIdAndIdempotencyKey("acme", "test-key"))
            .thenReturn(Optional.of(existing));

        ErasureRequestLedger result = service.openRequest(
            "acme", "1204", "ticket-LK-2026-451", "test-key"
        );

        assertThat(result.getRequestId()).isEqualTo(existingId);
        assertThat(result.getStatus()).isEqualTo(ErasureRequestLedger.Status.PROCESSING);
        verify(repo, never()).save(any());
    }

    @Test
    void openRequestSubjectMismatchThrowsCollision() {
        // Codex 019e499c REVISE P0 #2 absorb: aynı idempotency_key
        // farklı subscriber'ın HMAC'i ile gelirse → IllegalStateException
        UUID existingId = UUID.randomUUID();
        ErasureRequestLedger existing = new ErasureRequestLedger();
        existing.setRequestId(existingId);
        existing.setOrgId("acme");
        existing.setIdempotencyKey("collision-key");
        existing.setSubjectRefHmac("different-subject-hmac");

        when(repo.findByOrgIdAndIdempotencyKey("acme", "collision-key"))
            .thenReturn(Optional.of(existing));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.openRequest("acme", "1204", "ticket-X", "collision-key")
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("collision");
    }

    @Test
    void openRequestRaceFallbackRefetchesExistingRow() {
        UUID existingId = UUID.randomUUID();
        ErasureRequestLedger existing = new ErasureRequestLedger();
        existing.setRequestId(existingId);
        existing.setOrgId("acme");
        existing.setIdempotencyKey("race-key");
        existing.setSubjectRefHmac(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );

        // First lookup empty; concurrent insert race throws on save;
        // re-fetch returns row from competing transaction.
        when(repo.findByOrgIdAndIdempotencyKey("acme", "race-key"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existing));
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("uniq violation"));

        ErasureRequestLedger result = service.openRequest(
            "acme", "1204", "ticket-X", "race-key"
        );

        assertThat(result.getRequestId()).isEqualTo(existingId);
    }

    @Test
    void markProcessingTransitionsOnlyFromReceived() {
        UUID id = UUID.randomUUID();
        ErasureRequestLedger entry = new ErasureRequestLedger();
        entry.setRequestId(id);
        entry.setStatus(ErasureRequestLedger.Status.RECEIVED);

        when(repo.findById(id)).thenReturn(Optional.of(entry));

        service.markProcessing(id);

        assertThat(entry.getStatus()).isEqualTo(ErasureRequestLedger.Status.PROCESSING);
        verify(repo).save(entry);
    }

    @Test
    void markProcessingNoOpWhenAlreadyTerminal() {
        UUID id = UUID.randomUUID();
        ErasureRequestLedger entry = new ErasureRequestLedger();
        entry.setRequestId(id);
        entry.setStatus(ErasureRequestLedger.Status.COMPLETED);

        when(repo.findById(id)).thenReturn(Optional.of(entry));

        service.markProcessing(id);

        assertThat(entry.getStatus()).isEqualTo(ErasureRequestLedger.Status.COMPLETED);
        verify(repo, never()).save(any());
    }

    @Test
    void completeRequestDelegatesToRepo() {
        // Codex 019e499c REVISE P1 #4 absorb: audit_event_v2 BIGINT + occurred_at composite
        UUID id = UUID.randomUUID();
        Long auditEventId = 12345L;
        OffsetDateTime auditOccurredAt = OffsetDateTime.now();
        when(repo.markCompleted(any(), any(), any(), any())).thenReturn(1);

        service.completeRequest(id, auditEventId, auditOccurredAt);

        verify(repo).markCompleted(any(UUID.class), any(OffsetDateTime.class),
            any(Long.class), any(OffsetDateTime.class));
    }

    @Test
    void markFailedDelegatesToRepo() {
        // Codex 019e499c REVISE P0 #1 absorb: durable failure tracking
        UUID id = UUID.randomUUID();
        when(repo.markFailed(any(), any(), any())).thenReturn(1);

        service.markFailed(id, "TRANSACTION_ROLLBACK");

        verify(repo).markFailed(any(UUID.class), any(OffsetDateTime.class),
            org.mockito.ArgumentMatchers.eq("TRANSACTION_ROLLBACK"));
    }

    @Test
    void findOverdueRequestsScansLedger() {
        ErasureRequestLedger overdue = new ErasureRequestLedger();
        overdue.setRequestId(UUID.randomUUID());
        overdue.setStatus(ErasureRequestLedger.Status.RECEIVED);
        overdue.setDueAt(OffsetDateTime.now().minusDays(1));

        when(repo.findOverdueRequests(any())).thenReturn(List.of(overdue));

        List<ErasureRequestLedger> result = service.findOverdueRequests();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ErasureRequestLedger.Status.RECEIVED);
    }

    // ========================================================================
    // classifySource — Locale.ROOT defensive (Turkish dotless-I guard)
    // ========================================================================

    @Test
    void classifySourceSelfServiceSentinel() {
        assertThat(ErasureRequestLedgerService.classifySource("self-service-kvkk-art-11"))
            .isEqualTo(ErasureRequestLedger.RequestSource.SELF_SERVICE);
        assertThat(ErasureRequestLedgerService.classifySource("KVKK Art 11 right to erasure"))
            .isEqualTo(ErasureRequestLedger.RequestSource.SELF_SERVICE);
    }

    @Test
    void classifySourceLegalKeywords() {
        assertThat(ErasureRequestLedgerService.classifySource("Legal ticket LK-2026-451"))
            .isEqualTo(ErasureRequestLedger.RequestSource.LEGAL);
        assertThat(ErasureRequestLedgerService.classifySource("court order received"))
            .isEqualTo(ErasureRequestLedger.RequestSource.LEGAL);
        assertThat(ErasureRequestLedgerService.classifySource("mahkeme kararı"))
            .isEqualTo(ErasureRequestLedger.RequestSource.LEGAL);
    }

    @Test
    void classifySourceDpoKeyword() {
        assertThat(ErasureRequestLedgerService.classifySource("DPO formal request"))
            .isEqualTo(ErasureRequestLedger.RequestSource.DPO);
    }

    @Test
    void classifySourceComplianceKeyword() {
        assertThat(ErasureRequestLedgerService.classifySource("Compliance audit Q2"))
            .isEqualTo(ErasureRequestLedger.RequestSource.COMPLIANCE_AUDIT);
    }

    @Test
    void classifySourceDefaultsToAdmin() {
        assertThat(ErasureRequestLedgerService.classifySource("ticket-1234"))
            .isEqualTo(ErasureRequestLedger.RequestSource.ADMIN);
        assertThat(ErasureRequestLedgerService.classifySource(null))
            .isEqualTo(ErasureRequestLedger.RequestSource.ADMIN);
        assertThat(ErasureRequestLedgerService.classifySource(""))
            .isEqualTo(ErasureRequestLedger.RequestSource.ADMIN);
    }

    // ========================================================================
    // deriveIdempotencyKey — auto-derive when caller doesn't provide
    // ========================================================================

    @Test
    void deriveIdempotencyKeySelfServiceUsesHashAndDate() {
        String key = service.deriveIdempotencyKey("acme", "1204", "self-service-kvkk-art-11");

        // self-{orgId}-{subjectHash16}-{YYYY-MM-DD}
        assertThat(key).startsWith("self-acme-");
        // Subject hash first 16 chars (privacy — raw subscriberId NOT in key)
        assertThat(key).contains("0123456789abcdef");
        // ISO-8601 date stamp
        assertThat(key).contains(java.time.LocalDate.now().toString());
    }

    @Test
    void deriveIdempotencyKeyAdminContainsSubjectHashAndEvidenceHmacNotRaw() {
        // Codex 019e499c REVISE P0 #2 + P1 #5 absorb: subject-scoped +
        // evidence_ref HMAC digest (ham metin YASAK).
        String key = service.deriveIdempotencyKey("acme", "1204", "ticket-1234");

        assertThat(key).startsWith("admin-acme-");
        // Subject hash16 present
        assertThat(key).contains("0123456789abcdef");
        // Evidence HMAC digest present (not raw text)
        assertThat(key).doesNotContain("ticket-1234");
    }

    @Test
    void deriveIdempotencyKeyLegalContainsSubjectHashAndEvidenceHmacNotRaw() {
        // Codex 019e499c REVISE P0 #2 + P1 #5 absorb: subject-scoped +
        // evidence_ref HMAC digest (ham metin YASAK).
        String key = service.deriveIdempotencyKey(
            "acme", "1204", "Legal ticket LK-2026-451 user contact alice@example.com"
        );

        assertThat(key).startsWith("legal-acme-");
        assertThat(key).contains("0123456789abcdef");
        // PII fragmentleri ASLA ledger key materyalinde olmamalı
        assertThat(key).doesNotContain("alice@example.com");
        assertThat(key).doesNotContain("LK-2026-451");
    }

    @Test
    void deriveIdempotencyKeyAdminTwoSubscribersSameEvidenceProducesDifferentKeys() {
        // Codex 019e499c REVISE P0 #2 absorb: aynı evidenceRef farklı
        // subscriber → ayrı key (collision YASAK).
        // Override mock hashRecipient: bu testte iki farklı subscriber için
        // farklı hash döndürmek lazım.
        when(piiRedactor.hashRecipient("acme", "subscriber", "1204"))
            .thenReturn("aaaaaaaaaaaaaaaa1111111111111111aaaaaaaaaaaaaaaa2222222222222222");
        when(piiRedactor.hashRecipient("acme", "subscriber", "5678"))
            .thenReturn("bbbbbbbbbbbbbbbb3333333333333333bbbbbbbbbbbbbbbb4444444444444444");
        // Evidence HMAC aynı (her ikisi de aynı evidenceRef ile çağrılır)
        when(piiRedactor.hashRecipient("acme", "subscriber", "evidence:ticket-1234"))
            .thenReturn("ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");

        String key1 = service.deriveIdempotencyKey("acme", "1204", "ticket-1234");
        String key2 = service.deriveIdempotencyKey("acme", "5678", "ticket-1234");

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).contains("aaaaaaaaaaaaaaaa");
        assertThat(key2).contains("bbbbbbbbbbbbbbbb");
    }
}
