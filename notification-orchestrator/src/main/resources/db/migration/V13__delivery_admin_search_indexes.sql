-- Faz 23.5 PR6 — DLR admin endpoints: index seti for delivery log search.
--
-- Codex thread 019e0289 iter-3 AGREE absorb:
--   - Intent-scoped endpoint paginates deliveries by id desc; mevcut
--     idx_delivery_intent (intent_id) include id desc gerek.
--   - Admin-wide search activity ekseninde sıralanır:
--     activity_at = COALESCE(permanent_failure_at, delivered_at,
--                            last_attempt_at, updated_at, created_at).
--     Geç gelen DLR (status=FAILED 6 saat sonra terminal) created_at
--     filtre ile kaybolurdu — activity ekseni race-safe.
--   - Status + activity composite admin search'in en sık filtresi
--     (status=FAILED, last 24h).
--   - intent.org_id + intent_id JOIN çekirdek; admin search delivery
--     row'larını intent.org_id ile bağlar.
--
-- Channel/provider için ek index v1'de YOK; 24h default + 7d max window
-- + status filter index'i seek-narrow yapıyor. Repository IT'de query
-- planı kötü çıkarsa sonradan eklenebilir (Codex önerisi).

-- Intent-scoped endpoint: WHERE intent_id = ? ORDER BY id DESC LIMIT N
CREATE INDEX IF NOT EXISTS idx_delivery_intent_id_desc
    ON notify.notification_delivery (intent_id, id DESC);

-- Admin search JOIN: intent.org_id + intent.intent_id (delivery FK target)
CREATE INDEX IF NOT EXISTS idx_intent_org_intent_id
    ON notify.notification_intent (org_id, intent_id);

-- Admin search activity ekseni (geç-DLR safe)
CREATE INDEX IF NOT EXISTS idx_delivery_activity
    ON notify.notification_delivery (
      (COALESCE(permanent_failure_at, delivered_at, last_attempt_at, updated_at, created_at)) DESC,
      id DESC
    );

-- Status filtreli admin search (status=FAILED en sık)
CREATE INDEX IF NOT EXISTS idx_delivery_status_activity
    ON notify.notification_delivery (
      status,
      (COALESCE(permanent_failure_at, delivered_at, last_attempt_at, updated_at, created_at)) DESC,
      id DESC
    );

COMMENT ON INDEX notify.idx_delivery_intent_id_desc IS
    'Faz 23.5 PR6: intent-scoped delivery log endpoint pagination support.';
COMMENT ON INDEX notify.idx_intent_org_intent_id IS
    'Faz 23.5 PR6: admin delivery search JOIN intent on org_id + intent_id.';
COMMENT ON INDEX notify.idx_delivery_activity IS
    'Faz 23.5 PR6: admin search ORDER BY activity_at DESC; geç-DLR safe.';
COMMENT ON INDEX notify.idx_delivery_status_activity IS
    'Faz 23.5 PR6: admin search status filter + activity sort composite.';
