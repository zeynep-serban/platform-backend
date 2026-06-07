CREATE TABLE IF NOT EXISTS endpoint_command_secrets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    secret_name VARCHAR(64) NOT NULL,
    encrypted_secret TEXT,
    encryption_key_version VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    cleared_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_endpoint_command_secrets_command UNIQUE (command_id),
    CONSTRAINT fk_endpoint_command_secrets_command
        FOREIGN KEY (command_id) REFERENCES endpoint_commands(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_endpoint_command_secrets_tenant_expires
    ON endpoint_command_secrets(tenant_id, expires_at);

CREATE INDEX IF NOT EXISTS idx_endpoint_command_secrets_cleared
    ON endpoint_command_secrets(cleared_at);

COMMENT ON TABLE endpoint_command_secrets IS
    'AG-042: encrypted one-time command secrets for dedicated local password delivery. Raw secrets never live in endpoint_commands.payload.';
