-- Faz 23.2 PR-A — production security foundation (Codex 019dfae5 absorb).
--
-- Bu migration:
--   1. provider_config: org_id column + multi-tenant unique tuple (Codex Q4 REVISE)
--   2. webhook_hmac_key: kid-aware HMAC key registry (active + next; rotation)
--   3. notification_intent: idempotent retry helper view (yorum)

-- ============================================================================
-- 1) provider_config org_id (Codex Q4 REVISE absorb)
-- ============================================================================
-- Mevcut V1 schema'da org_id YOK; multi-tenant matrix için org-aware tuple.
-- Default '*' tüm org'lar için ortak config; org-specific override mümkün.
ALTER TABLE notify.provider_config
    ADD COLUMN org_id VARCHAR(64) NOT NULL DEFAULT '*';

-- Eski unique (provider_key, environment, version) yetersiz — org_id eklendi.
ALTER TABLE notify.provider_config
    DROP CONSTRAINT uq_provider_version;

ALTER TABLE notify.provider_config
    ADD CONSTRAINT uq_provider_org_version
        UNIQUE (org_id, provider_key, environment, version);

-- Eski active idx yetersiz — org_id eklendi
DROP INDEX IF EXISTS notify.idx_provider_active;

CREATE UNIQUE INDEX idx_provider_active
    ON notify.provider_config (org_id, provider_key, environment)
    WHERE active = TRUE;

CREATE INDEX idx_provider_org_channel_priority
    ON notify.provider_config (org_id, channel, environment, priority)
    WHERE active = TRUE;

COMMENT ON COLUMN notify.provider_config.org_id IS
    'Multi-tenant org_id (Codex 019dfae5 PR-A Q4 absorb). Default "*" tüm orgs için '
    'ortak config; org-specific override için ayrı row. Failover: priority ASC içinde '
    'aynı (org_id, channel, environment) → en düşük priority active=TRUE seçilir.';

-- ============================================================================
-- 2) webhook_hmac_key (Codex 019dfae5 Q3 PARTIAL absorb)
-- ============================================================================
-- Kid-aware HMAC key registry. Active + next keys; receivers verify with
-- known kid. Rotation: yeni key insert → next; eski key active → retire.
CREATE TABLE notify.webhook_hmac_key (
    id BIGSERIAL PRIMARY KEY,
    kid VARCHAR(32) NOT NULL,           -- key id (UUID veya datestamp; receiver header'da görür)
    secret_ref VARCHAR(255) NOT NULL,   -- Vault path: kv/platform/notify/webhook/<kid>
    status VARCHAR(16) NOT NULL,        -- 'ACTIVE' | 'NEXT' | 'RETIRED' (Codex iter-1 P0: uppercase, JPA @Enumerated(STRING) compatible)
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_webhook_hmac_kid UNIQUE (kid),
    CONSTRAINT chk_webhook_hmac_status CHECK (status IN ('ACTIVE', 'NEXT', 'RETIRED'))
);

-- Sadece BIR active key olabilir
CREATE UNIQUE INDEX idx_webhook_hmac_active
    ON notify.webhook_hmac_key ((1))
    WHERE status = 'ACTIVE';

-- Sadece BIR next key olabilir (rotation hazırlık)
CREATE UNIQUE INDEX idx_webhook_hmac_next
    ON notify.webhook_hmac_key ((1))
    WHERE status = 'NEXT';

COMMENT ON TABLE notify.webhook_hmac_key IS
    'Webhook HMAC signing key registry (Codex 019dfae5 PR-A Q3 absorb). '
    'Header: t=<ts>,kid=<active>,v1=<sig>. Rotation pattern: '
    '1) yeni key insert status=next + secret_ref Vault'
    '; 2) status=next → active (eski active → retired); '
    '3) consumer migrate window (default 7 day); 4) retired key delete (or keep for audit).';

-- ============================================================================
-- 3) provider_config seed pattern reminder (operator runbook)
-- ============================================================================
-- Kabul kriteri sırası:
-- 1. Insert: org='*', provider_key='smtp-default', channel='email', env='prod',
--    version=1, config={...}, credential_ref='vault:kv/platform-prod/notify/smtp',
--    active=TRUE, priority=100
-- 2. Failover: insert org='*', provider_key='smtp-fallback', priority=200, active=TRUE
-- 3. Override: org='banka-x', provider_key='smtp-banka', priority=50, active=TRUE
--    (banka-x için priority=50 daha düşük → ilk seçilir)
