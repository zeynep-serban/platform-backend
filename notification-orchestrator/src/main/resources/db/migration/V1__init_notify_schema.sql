-- Faz 23.1 Kernel V1 schema (ADR-0013 D39 + D46 #1 must-have).
-- Codex thread 019df86f post-impl Q1 absorb (schema tablo count ≥8) +
-- event-contract.md §4 PostgreSQL Schema spec.

-- 9 tablo: notification_intent + notification_delivery + notification_template +
-- subscriber_preference + provider_config + provider_config_history + audit_event +
-- dead_letter + idempotency_key.

CREATE SCHEMA IF NOT EXISTS notify;

-- ============================================================================
-- notification_intent — gelen intent
-- ============================================================================
CREATE TABLE notify.notification_intent (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL UNIQUE,
    correlation_id VARCHAR(128),
    org_id VARCHAR(64) NOT NULL,
    topic_key VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    data_classification VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    template_id VARCHAR(128) NOT NULL,
    template_version INT,
    locale VARCHAR(16) NOT NULL,
    channels TEXT[] NOT NULL,
    channel_routing JSONB,
    scheduled_at TIMESTAMPTZ,
    expire_at TIMESTAMPTZ,
    metadata JSONB,
    preference_override JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_intent_status_scheduled ON notify.notification_intent (status, scheduled_at);
CREATE INDEX idx_intent_org_topic ON notify.notification_intent (org_id, topic_key);
CREATE INDEX idx_intent_correlation ON notify.notification_intent (correlation_id);

-- ============================================================================
-- idempotency_key — 24h TTL window (Codex 019df86f post-impl Q1 #3 fix)
-- ============================================================================
CREATE TABLE notify.idempotency_key (
    id BIGSERIAL PRIMARY KEY,
    org_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    intent_id VARCHAR(64) NOT NULL REFERENCES notify.notification_intent(intent_id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_idem_expires ON notify.idempotency_key (expires_at);
CREATE UNIQUE INDEX idx_idem_active ON notify.idempotency_key (org_id, idempotency_key)
    WHERE expires_at > NOW();

-- ============================================================================
-- notification_delivery — her kanal için delivery attempt
-- ============================================================================
CREATE TABLE notify.notification_delivery (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL REFERENCES notify.notification_intent(intent_id),
    channel VARCHAR(32) NOT NULL,
    recipient_type VARCHAR(16) NOT NULL,
    recipient_id VARCHAR(128),
    recipient_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_msg_id VARCHAR(255),
    status VARCHAR(40) NOT NULL,
    attempt_count INT DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    failure_reason TEXT,
    next_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_delivery_intent ON notify.notification_delivery (intent_id);
CREATE INDEX idx_delivery_status_retry ON notify.notification_delivery (status, next_retry_at);
CREATE INDEX idx_delivery_recipient_hash ON notify.notification_delivery (recipient_hash);

-- ============================================================================
-- notification_template — versionable, immutable per version
-- ============================================================================
CREATE TABLE notify.notification_template (
    id BIGSERIAL PRIMARY KEY,
    template_id VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    subject TEXT,
    body_html TEXT,
    body_text TEXT,
    external_allowed BOOLEAN DEFAULT FALSE,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by VARCHAR(128),
    CONSTRAINT uq_template_version_locale UNIQUE (template_id, version, locale)
);

CREATE INDEX idx_template_active ON notify.notification_template (template_id, locale)
    WHERE active = TRUE;

-- Version immutability (D46 #9 must-have)
CREATE OR REPLACE RULE template_no_update AS ON UPDATE TO notify.notification_template
    DO INSTEAD NOTHING;

-- ============================================================================
-- subscriber_preference — per-user per-channel + per-topic preference
-- ============================================================================
CREATE TABLE notify.subscriber_preference (
    id BIGSERIAL PRIMARY KEY,
    subscriber_id VARCHAR(128) NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    topic_key VARCHAR(128),
    channel VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_hours JSONB,
    frequency_limit_per_day INT,
    bypass_for_critical BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_pref_lookup ON notify.subscriber_preference (subscriber_id, org_id);
CREATE UNIQUE INDEX uq_pref_subscriber_topic_channel ON notify.subscriber_preference
    (subscriber_id, COALESCE(topic_key, ''), COALESCE(channel, ''));

-- ============================================================================
-- provider_config — env-specific provider config (test/prod ayrı)
-- ============================================================================
CREATE TABLE notify.provider_config (
    id BIGSERIAL PRIMARY KEY,
    provider_key VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    config JSONB NOT NULL,
    credential_ref VARCHAR(255),
    active BOOLEAN DEFAULT FALSE,
    priority INT DEFAULT 100,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT uq_provider_version UNIQUE (provider_key, environment, version)
);

CREATE UNIQUE INDEX idx_provider_active ON notify.provider_config (provider_key, environment)
    WHERE active = TRUE;
CREATE INDEX idx_provider_priority ON notify.provider_config (channel, environment, active, priority);

-- ============================================================================
-- provider_config_history — D30-NOTIFY rollback için append-only
-- ============================================================================
CREATE TABLE notify.provider_config_history (
    id BIGSERIAL PRIMARY KEY,
    provider_config_id BIGINT NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    config JSONB NOT NULL,
    credential_ref VARCHAR(255),
    activated_at TIMESTAMPTZ,
    deactivated_at TIMESTAMPTZ,
    deactivation_reason TEXT
);

CREATE INDEX idx_provider_history_lookup ON notify.provider_config_history
    (provider_key, environment, deactivated_at);

CREATE OR REPLACE RULE provider_history_no_update AS ON UPDATE TO notify.provider_config_history
    DO INSTEAD NOTHING;
CREATE OR REPLACE RULE provider_history_no_delete AS ON DELETE TO notify.provider_config_history
    DO INSTEAD NOTHING;

-- ============================================================================
-- audit_event — PII-redacted append-only audit (D46 #7)
-- ============================================================================
CREATE TABLE notify.audit_event (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL,
    delivery_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    topic_key VARCHAR(128) NOT NULL,
    recipient_hash VARCHAR(64),
    channel VARCHAR(32),
    template_id VARCHAR(128),
    template_version INT,
    details JSONB,
    correlation_id VARCHAR(128),
    occurred_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_org_topic ON notify.audit_event (org_id, topic_key, occurred_at);
CREATE INDEX idx_audit_recipient ON notify.audit_event (recipient_hash, occurred_at);
CREATE INDEX idx_audit_correlation ON notify.audit_event (correlation_id);
CREATE INDEX idx_audit_occurred ON notify.audit_event (occurred_at);

-- Append-only enforcement (D46 #10 must-have)
CREATE OR REPLACE RULE audit_event_no_update AS ON UPDATE TO notify.audit_event
    DO INSTEAD NOTHING;
CREATE OR REPLACE RULE audit_event_no_delete AS ON DELETE TO notify.audit_event
    DO INSTEAD NOTHING;

-- ============================================================================
-- dead_letter — max retry sonrası DLQ (D46 #4)
-- ============================================================================
CREATE TABLE notify.dead_letter (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL,
    delivery_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    recipient_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(64),
    attempt_count INT NOT NULL,
    last_failure_reason TEXT,
    last_failure_at TIMESTAMPTZ NOT NULL,
    moved_to_dlq_at TIMESTAMPTZ DEFAULT NOW(),
    replayed BOOLEAN DEFAULT FALSE,
    replayed_at TIMESTAMPTZ,
    replayed_by VARCHAR(128)
);

CREATE INDEX idx_dlq_unreplayed ON notify.dead_letter (replayed, moved_to_dlq_at)
    WHERE replayed = FALSE;
