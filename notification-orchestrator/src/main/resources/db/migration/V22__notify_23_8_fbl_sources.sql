-- Faz 23.8 M7 T4.3.5 — FBL (Spam Complaint Feedback Loop) source widening
-- (Codex thread 019e4edd plan-time AGREE + 019e4fc6 impl-detail refresh).
--
-- BAGLAM
-- ------
-- T4.3.5 spam-complaint Feedback Loop: Office 365 Postmaster ARF (Abuse
-- Reporting Format, RFC 5965) raporlari IMAP mailbox'a gelir. FblService
-- ARF parse eder, ilgili recipient'i email_suppression'a SPAM_COMPLAINT
-- reason ile ekler, email_bounce_event ledger ile idempotency saglar.
--
-- T4.3.b (V17) email_bounce_event + email_suppression tablolarini yaratti
-- ama source CHECK constraint'leri yalnizca DSN/PROVIDER_WEBHOOK/MANUAL_API/
-- SMTP_IMMEDIATE iceriyordu. FBL iki yeni source degeri getirir:
--   - ARF_MAILBOX: IMAP mailbox-pull ARF report (Office 365 Postmaster)
--   - POSTMASTER_WEBHOOK: ileride webhook-push FBL (forward-compat; PR-1'de
--     kullanan kod yok, source enum + CHECK forward-ready birakilir)
--
-- classification CHECK degismez: SPAM_COMPLAINT zaten V17'de mevcut.

-- 1) email_bounce_event.source CHECK genislet
-- V17 inline CHECK -> PostgreSQL auto-name: email_bounce_event_source_check
ALTER TABLE notify.email_bounce_event
    DROP CONSTRAINT IF EXISTS email_bounce_event_source_check;
ALTER TABLE notify.email_bounce_event
    ADD CONSTRAINT email_bounce_event_source_check
    CHECK (source IN ('DSN', 'PROVIDER_WEBHOOK', 'MANUAL_API',
                      'SMTP_IMMEDIATE', 'ARF_MAILBOX', 'POSTMASTER_WEBHOOK'));

-- 2) email_suppression.last_source CHECK genislet (NULL allowance korunur)
-- V17 inline CHECK -> auto-name: email_suppression_last_source_check
ALTER TABLE notify.email_suppression
    DROP CONSTRAINT IF EXISTS email_suppression_last_source_check;
ALTER TABLE notify.email_suppression
    ADD CONSTRAINT email_suppression_last_source_check
    CHECK (last_source IN ('DSN', 'PROVIDER_WEBHOOK', 'MANUAL_API',
                          'SMTP_IMMEDIATE', 'ARF_MAILBOX', 'POSTMASTER_WEBHOOK')
           OR last_source IS NULL);

-- 3) provider_msg_id alignment (Codex 019e4fc6 additional_required_fix)
-- notification_delivery.provider_msg_id VARCHAR(255) (V1). FBL provider_msg_id
-- correlation o tablodan deger tasir; email_bounce_event.provider_msg_id +
-- email_suppression.last_provider_msg_id V17'de VARCHAR(128) idi -> uzun
-- Message-ID / correlator degerlerinde truncation/insert-fail riski.
-- 255'e hizala.
ALTER TABLE notify.email_bounce_event
    ALTER COLUMN provider_msg_id TYPE VARCHAR(255);
ALTER TABLE notify.email_suppression
    ALTER COLUMN last_provider_msg_id TYPE VARCHAR(255);

COMMENT ON CONSTRAINT email_bounce_event_source_check
    ON notify.email_bounce_event IS
    'Faz 23.8 M7 T4.3.5 — FBL ARF_MAILBOX + POSTMASTER_WEBHOOK source values added.';
