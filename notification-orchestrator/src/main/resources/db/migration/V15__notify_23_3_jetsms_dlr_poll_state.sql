-- Faz 23.3 PR-3 — JetSMS DLR polling state columns.
--
-- Codex `019e3f82` iter-2 absorb: NetGSM DLR webhook PUSH (provider POST eder
-- /api/v1/notify/dlr/netgsm), JetSMS DLR POLLING PULL (backend HttpSmsReport
-- endpoint'ini periyodik sorgular). JetSmsDlrPollingWorker ACCEPTED durumdaki
-- jetsms delivery row'larını batch çeker.
--
-- Bu 3 kolon olmadan:
--   - pending JetSMS state'leri (MessageState 2/6/7/8 — operatöre gönderildi
--     rapor yok) her poll cycle'da tekrar tekrar sorgulanır (provider API rate
--     israfı), VEYA updated_at kirletilir (gerçek state değişimi sinyali bozulur)
--   - poll-count görünmez → bir delivery'nin kaç kez poll edildiği + max-age
--     timeout (72h sonrası FAILED) izlenemez
--
-- Kolonlar:
--   dlr_next_poll_at  — bir sonraki poll zamanı (worker WHERE dlr_next_poll_at
--                       <= now ile claim eder; pending sonrası reschedule)
--   dlr_last_poll_at  — son poll zamanı (observability + max-age timeout hesabı)
--   dlr_poll_count    — toplam poll sayısı (monitoring + runaway guard)

ALTER TABLE notify.notification_delivery
    ADD COLUMN IF NOT EXISTS dlr_next_poll_at timestamptz,
    ADD COLUMN IF NOT EXISTS dlr_last_poll_at timestamptz,
    ADD COLUMN IF NOT EXISTS dlr_poll_count integer NOT NULL DEFAULT 0;

-- Partial index: ACCEPTED row'lar (provider leading column). JetSmsDlrPolling
-- Worker claim query'sinin (WHERE status='ACCEPTED' AND provider='jetsms' AND
-- (dlr_next_poll_at IS NULL OR dlr_next_poll_at <= now) FOR UPDATE SKIP LOCKED)
-- fast path'i. dlr_next_poll_at NULL DAHİL: JetSMS ACCEPTED delivery ilk kez
-- NULL ile başlar (DeliveryDispatchService dokunmaz — behavior-neutral),
-- worker ilk cycle'da NULL'u da claim eder, pending ise reschedule ile ileri
-- atar. PENDING/RETRY/terminal row'lar status≠ACCEPTED → index dışı.
CREATE INDEX IF NOT EXISTS idx_delivery_jetsms_dlr_poll
    ON notify.notification_delivery (provider, dlr_next_poll_at)
    WHERE status = 'ACCEPTED';

COMMENT ON COLUMN notify.notification_delivery.dlr_next_poll_at IS
    'Faz 23.3 PR-3: JetSMS DLR poll due time; worker WHERE <= now claims. '
    'PUSH-mode provider (NetGSM) row''larında NULL.';
COMMENT ON COLUMN notify.notification_delivery.dlr_last_poll_at IS
    'Faz 23.3 PR-3: JetSMS DLR son poll zamanı (observability + max-age timeout).';
COMMENT ON COLUMN notify.notification_delivery.dlr_poll_count IS
    'Faz 23.3 PR-3: JetSMS DLR toplam poll sayısı (monitoring + runaway guard).';
