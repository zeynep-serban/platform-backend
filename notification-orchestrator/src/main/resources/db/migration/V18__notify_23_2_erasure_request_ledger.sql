-- Faz 23.2 M3 R2 PR-K1 — KVKK Madde 13.2 erasure request ledger
-- (Codex 019e4950 P0 #1 absorb).
--
-- Hukuki dayanak: KVKK Madde 13.2 — "Veri sorumlusu, başvuruyu
-- talebin niteliğine göre en kısa sürede ve en geç **otuz gün**
-- içinde ücretsiz olarak sonuçlandırır."
--
-- Tablo amacı:
--   1. Her KVKK silme başvurusunun received_at + due_at (30 gün) takibi
--   2. Idempotency_key ile cross-request deduplication
--   3. Legal_hold reason (mahkeme kararı / aktif soruşturma) görünür
--   4. ErasureSlaWatchdog scheduled scan → due_at <= NOW() warning
--
-- Bu tablo Madde 13.2 audit kanıtı için **append-only saklanır**;
-- 90-gün retention purge buna dokunmaz (KVKK denetim sorumluluğu).
--
-- KVKK Madde 12 (data minimization) uyumu:
--   - subject_ref_hmac VARCHAR(128): PiiRedactor.hashRecipient
--     (HMAC-SHA256 with org-namespaced Vault pepper); raw email/phone
--     YASAK
--   - legal_hold_reason_code VARCHAR(64): enum-like sabit kategori
--     (COURT_ORDER / ACTIVE_INVESTIGATION / REGULATORY_RETENTION /
--     TAX_AUDIT_5Y / OTHER); operator serbest metin YASAK (Codex
--     019e499c REVISE P1 #5 absorb)
--   - legal_hold_external_reference VARCHAR(128): kısa external
--     ticket/mahkeme no — açıklama DEĞİL
--   - idempotency_key VARCHAR(128): subject HMAC + evidence HMAC
--     digest birleşimi (Codex 019e499c REVISE P0 #2 + P1 #5 absorb);
--     ham evidence_ref ASLA key materyali değil
--   - failure_reason VARCHAR(256): exception kategori sabiti
--     (TRANSACTION_ROLLBACK / AUDIT_PUBLISH_ERROR / DB_CONSTRAINT /
--     UNKNOWN); stack trace YASAK
--
-- Codex 019e4950 P0 verdict: "30-gün takip için per-request ledger
-- şart. audit_event tek başına SLA monitor yapmaz; received_at →
-- due_at delta hesabı dedicated tabloda olmalı."

-- ============================================================================
-- 1) erasure_request_ledger — KVKK Madde 13.2 başvuru takibi
-- ============================================================================

CREATE TABLE notify.erasure_request_ledger (
    request_id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                           VARCHAR(64)  NOT NULL,
    subject_ref_hmac                 VARCHAR(128) NOT NULL,
    request_source                   VARCHAR(32)  NOT NULL,
    received_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    due_at                           TIMESTAMPTZ  NOT NULL,
    status                           VARCHAR(32)  NOT NULL,
    closed_at                        TIMESTAMPTZ,
    failure_reason                   VARCHAR(256),
    legal_hold_reason_code           VARCHAR(64),
    legal_hold_external_reference    VARCHAR(128),
    idempotency_key                  VARCHAR(128) NOT NULL,
    last_audit_event_id              BIGINT,
    last_audit_event_occurred_at     TIMESTAMPTZ,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT erasure_ledger_idemp_unique
        UNIQUE (org_id, idempotency_key),
    CONSTRAINT erasure_ledger_source_check
        CHECK (request_source IN ('SELF_SERVICE', 'ADMIN', 'LEGAL', 'DPO', 'COMPLIANCE_AUDIT')),
    CONSTRAINT erasure_ledger_status_check
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'LEGAL_HOLD', 'FAILED')),
    CONSTRAINT erasure_ledger_due_at_check
        CHECK (due_at > received_at),
    CONSTRAINT erasure_ledger_closed_consistency
        CHECK ((status IN ('COMPLETED', 'FAILED') AND closed_at IS NOT NULL)
            OR (status IN ('RECEIVED', 'PROCESSING', 'LEGAL_HOLD') AND closed_at IS NULL)),
    -- Codex 019e499c REVISE P1 #4: audit chain referansı composite PK + occurred_at
    -- (audit_event_v2 BIGINT + partition discriminator). Şu an
    -- AuditEventPublisher event_id döndürmüyor → null kalır; follow-up
    -- (audit-event id capture) sonrası backfill yapılır.
    -- Codex 019e499c REVISE P1 #5: legal_hold serbest metin → reason_code
    -- (enum-like) + external_reference (ticket id) ayrı kolonlar; PII
    -- minimization. Kullanıcı operator dilediği uzun metni buraya
    -- yazamaz.
    CONSTRAINT erasure_ledger_audit_chain_consistency
        CHECK ((last_audit_event_id IS NULL AND last_audit_event_occurred_at IS NULL)
            OR (last_audit_event_id IS NOT NULL AND last_audit_event_occurred_at IS NOT NULL)),
    -- Codex 019e499c iter-3 REVISE P2 absorb: legal_hold_reason_code
    -- enum CHECK (operator serbest metin YASAK).
    CONSTRAINT erasure_ledger_legal_hold_reason_code_check
        CHECK (legal_hold_reason_code IS NULL
            OR legal_hold_reason_code IN (
                'COURT_ORDER',
                'ACTIVE_INVESTIGATION',
                'REGULATORY_RETENTION',
                'TAX_AUDIT_5Y',
                'OTHER'
            ))
);

COMMENT ON TABLE notify.erasure_request_ledger IS
    'Faz 23.2 M3 R2 PR-K1 (Codex 019e4950 P0 #1) — KVKK Madde 13.2 '
    'erasure request 30-gün SLA ledger. Append-only saklanır (denetim '
    'kanıtı). 90-gün retention purge buna dokunmaz.';

COMMENT ON COLUMN notify.erasure_request_ledger.subject_ref_hmac IS
    'HMAC-SHA256 with org-namespaced Vault pepper (PiiRedactor). '
    'Pseudonymous; raw email/phone YASAK (KVKK Madde 12).';

COMMENT ON COLUMN notify.erasure_request_ledger.due_at IS
    'KVKK Madde 13.2 SLA: received_at + 30 gün. ErasureSlaWatchdog '
    'scheduled scan due_at <= NOW() AND status NOT IN '
    '(COMPLETED, FAILED) → Slack alert.';

COMMENT ON COLUMN notify.erasure_request_ledger.idempotency_key IS
    'Cross-request deduplication. Aynı (org_id, idempotency_key) '
    'ikinci başvuru ledger insert UNIQUE violation → service-side '
    'no-op (request_id mevcut row döner).';

COMMENT ON COLUMN notify.erasure_request_ledger.legal_hold_reason_code IS
    'KVKK Madde 28 istisna kategorisi (Codex 019e499c P1 #5 absorb): '
    'COURT_ORDER / ACTIVE_INVESTIGATION / REGULATORY_RETENTION / '
    'TAX_AUDIT_5Y / OTHER. Serbest metin YASAK (PII sızması riski).';

COMMENT ON COLUMN notify.erasure_request_ledger.legal_hold_external_reference IS
    'Mahkeme kararı / soruşturma ticket / vergi denetim no — kısa '
    'referans kodu; insan okunabilir açıklama DEĞİL.';

COMMENT ON COLUMN notify.erasure_request_ledger.failure_reason IS
    'Erasure runtime hatası kategorisi (Codex 019e499c P0 #1 + iter-3 P0 '
    'absorb): TRANSACTION_ROLLBACK / AUDIT_PUBLISH_ERROR / DB_CONSTRAINT / '
    'UNKNOWN. Status non-terminal kalır (PROCESSING) → KVKK Madde 13.2 '
    'SLA scan unresolved teknik hatayı görmeye devam eder. Terminal '
    'FAILED state DPO/legal formal denied closure için reserve. Stack '
    'trace YASAK.';

COMMENT ON COLUMN notify.erasure_request_ledger.last_audit_event_id IS
    'Bağlı audit_event_v2 row BIGINT id (Codex 019e499c P1 #4 absorb: '
    'UUID değil, schema gerçek tipiyle uyumlu). Composite PK '
    '(id, occurred_at) gereği last_audit_event_occurred_at ile '
    'birlikte JOIN edilir. AuditEventPublisher event_id döndürene '
    'kadar null kalır.';

COMMENT ON COLUMN notify.erasure_request_ledger.last_audit_event_occurred_at IS
    'audit_event_v2 partition discriminator (composite PK) — JOIN '
    'için gerekli. last_audit_event_id ile NULL-NOT NULL atomic '
    '(CHECK constraint).';

-- ============================================================================
-- 2) İndeksler — SLA scan + idempotency check + reporting
-- ============================================================================

-- SLA Watchdog: due_at <= NOW() AND status NOT IN (COMPLETED, FAILED)
CREATE INDEX idx_erasure_ledger_sla_scan
    ON notify.erasure_request_ledger (due_at)
    WHERE status IN ('RECEIVED', 'PROCESSING', 'LEGAL_HOLD');

-- Subject reverse lookup (DPO audit query)
CREATE INDEX idx_erasure_ledger_subject_lookup
    ON notify.erasure_request_ledger (org_id, subject_ref_hmac, received_at DESC);

-- Reporting: monthly KVKK denetim raporu
CREATE INDEX idx_erasure_ledger_received_at
    ON notify.erasure_request_ledger (received_at DESC);

-- ============================================================================
-- 3) updated_at trigger — append-only ama status update audit edilir
-- ============================================================================

CREATE OR REPLACE FUNCTION notify.erasure_ledger_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_erasure_ledger_touch_updated_at
    BEFORE UPDATE ON notify.erasure_request_ledger
    FOR EACH ROW
    EXECUTE FUNCTION notify.erasure_ledger_touch_updated_at();
