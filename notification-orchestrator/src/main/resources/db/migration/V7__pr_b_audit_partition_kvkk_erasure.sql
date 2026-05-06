-- Faz 23.2 PR-B — KVKK erasure foundation (Codex 019dfae5 iter-1 absorb).
--
-- Codex iter-1 P0 absorb: V7 partition migration revize edildi. Mevcut
-- audit_event tablosunu rename + recreate sırasında sequence/PK name
-- collision riski + DEFAULT partition overlap riski + composite PK JPA
-- mismatch → Testcontainers verify olmadan production'a yüklenmez.
--
-- Bu migration kapsamı daraltıldı (foundation only):
--   1. notification_intent.payload nullable (KVKK erasure için PII purge)
--
-- Audit partition migration follow-up commit'te (PR-B.iter-2 veya PR-D):
--   - audit_event_v2 + zero-downtime cutover pattern
--   - Composite PK (id, occurred_at) için @IdClass mapping
--   - Sequence/index name explicit yönetim
--   - Testcontainers integration test ile data preservation kanıtı

-- ============================================================================
-- 1) notification_intent — KVKK erasure: PII fields nullable
-- ============================================================================
-- Codex Q2 PARTIAL absorb: erasure sadece payload değil; recipients_snapshot,
-- metadata, channel_routing, preference_override içindeki PII de purge'lenmeli.
-- Bu migration: payload NOT NULL constraint kaldırılır (diğer alanlar zaten
-- nullable V2'den itibaren).

ALTER TABLE notify.notification_intent
    ALTER COLUMN payload DROP NOT NULL;

COMMENT ON COLUMN notify.notification_intent.payload IS
    'Intent payload (KVKK erasure: nullable; ErasureService.eraseSubscriber() '
    'sets to NULL after audit append SUBSCRIBER_ERASURE_REQUEST).';
