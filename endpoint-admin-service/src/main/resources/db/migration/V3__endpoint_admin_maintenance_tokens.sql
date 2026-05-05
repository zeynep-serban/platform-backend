CREATE TABLE endpoint_maintenance_tokens (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(512),
    issued_by_subject VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_by_agent_version VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_maintenance_tokens_action
        CHECK (action IN ('STOP_AGENT','UNINSTALL_AGENT')),
    CONSTRAINT ck_endpoint_maintenance_tokens_status
        CHECK (status IN ('PENDING','CONSUMED','EXPIRED','REVOKED')),
    CONSTRAINT uq_endpoint_maintenance_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_endpoint_maintenance_tokens_tenant_device_status
    ON endpoint_maintenance_tokens (tenant_id, device_id, status);
CREATE INDEX idx_endpoint_maintenance_tokens_expires
    ON endpoint_maintenance_tokens (expires_at);
