-- Faz 23.7 M7 T4.2 PR-W2.5+W2.6 — push channel BLOCKED_NO_PUSH_ENDPOINT
-- status (Codex 019e4a3d iter-3 P1 absorb).
--
-- Problem: PR-W2.5 iter-2'de NotificationDelivery.Status enum'a
-- BLOCKED_NO_PUSH_ENDPOINT eklendi (push-only intent + 0 endpoint
-- zombie state önleme için marker target pattern). Ancak DB tarafı
-- (V17 + V11) yeni status'u tanımıyor:
--   - notification_delivery_status_check CHECK constraint listesinde yok
--   - notification_delivery_state_audit() trigger terminal + allowed
--     transition listelerinde yok
-- Bu nedenle DeliveryEligibilityService → DeliveryDispatchService
-- persistBlockedDelivery() runtime'da check_violation alıyordu.
--
-- Fix:
--   1) CHECK constraint genişletme — BLOCKED_NO_PUSH_ENDPOINT eklendi
--   2) Trigger CREATE OR REPLACE — terminal immutability + PENDING/RETRY
--      allowed transition listelerinde BLOCKED_NO_PUSH_ENDPOINT
--   3) ACCEPTED → BLOCKED_NO_PUSH_ENDPOINT yine yasak (no-endpoint
--      guard send öncesi tetiklenir; ACCEPTED durumunda zaten provider'a
--      gitmiş demektir, geriye dönüş yok).

-- 1) Status CHECK constraint genişletme
ALTER TABLE notify.notification_delivery
    DROP CONSTRAINT IF EXISTS notification_delivery_status_check;

ALTER TABLE notify.notification_delivery
    ADD CONSTRAINT notification_delivery_status_check
    CHECK (status IN (
        'PENDING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED', 'RETRY',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION',
        'BLOCKED_NO_PUSH_ENDPOINT'
    ));

-- 2) State machine trigger refresh — V17 invariants korunuyor, yeni
-- terminal status eklendi.
CREATE OR REPLACE FUNCTION notify.notification_delivery_state_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;

    -- Terminal state immutability (V11 + V17 + V20 cumulative)
    IF OLD.status IN ('DELIVERED', 'FAILED', 'BOUNCED',
                      'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
                      'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
                      'BLOCKED_BY_SUPPRESSION',
                      'BLOCKED_NO_PUSH_ENDPOINT')
       AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION
            'notification_delivery: % is terminal; transition to % rejected',
            OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    -- Forward-only: explicit allowed transitions from non-terminal
    IF OLD.status = 'PENDING' AND NEW.status NOT IN
       ('PENDING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED', 'RETRY',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION',
        'BLOCKED_NO_PUSH_ENDPOINT') THEN
        RAISE EXCEPTION 'notification_delivery: PENDING -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'RETRY' AND NEW.status NOT IN
       ('RETRY', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION',
        'BLOCKED_NO_PUSH_ENDPOINT') THEN
        RAISE EXCEPTION 'notification_delivery: RETRY -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    -- ACCEPTED → BLOCKED_NO_PUSH_ENDPOINT YASAK kalır: send öncesi guard
    -- (DeliveryEligibilityService Guard 0a) marker target için tetiklenir;
    -- ACCEPTED durumunda zaten adapter çağrılmış demektir, retrospektif
    -- BLOCKED set'i mantıksız.
    IF OLD.status = 'ACCEPTED' AND NEW.status NOT IN
       ('ACCEPTED', 'DELIVERED', 'FAILED') THEN
        RAISE EXCEPTION 'notification_delivery: ACCEPTED -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    -- Field cleanup on legal transitions (V11 invariants preserved)
    IF OLD.status = 'RETRY' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
        NEW.claim_token = NULL;
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'DELIVERED' THEN
        IF NEW.delivered_at IS NULL THEN
            NEW.delivered_at = NOW();
        END IF;
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'FAILED' THEN
        IF NEW.permanent_failure_at IS NULL THEN
            NEW.permanent_failure_at = NOW();
        END IF;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON CONSTRAINT notification_delivery_status_check ON notify.notification_delivery IS
    'V20 (Faz 23.7 M7 T4.2 PR-W2.5+W2.6): added BLOCKED_NO_PUSH_ENDPOINT terminal status. Push channel + 0 active endpoint marker target Guard 0a yakalanır, terminal blocked delivery row + audit event.';
