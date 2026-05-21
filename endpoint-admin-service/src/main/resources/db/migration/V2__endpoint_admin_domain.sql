CREATE TABLE endpoint_devices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    os_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    os_version VARCHAR(255),
    agent_version VARCHAR(128),
    machine_fingerprint VARCHAR(512),
    domain_name VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ENROLLMENT',
    last_seen_at TIMESTAMPTZ,
    enrolled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_devices_os_type
        CHECK (os_type IN ('WINDOWS','MACOS','LINUX','UNKNOWN')),
    CONSTRAINT ck_endpoint_devices_status
        CHECK (status IN ('PENDING_ENROLLMENT','ONLINE','STALE','OFFLINE','DECOMMISSIONED')),
    CONSTRAINT uq_endpoint_devices_tenant_hostname UNIQUE (tenant_id, hostname),
    CONSTRAINT uq_endpoint_devices_tenant_fingerprint UNIQUE (tenant_id, machine_fingerprint)
);

CREATE INDEX idx_endpoint_devices_tenant_status
    ON endpoint_devices (tenant_id, status);
CREATE INDEX idx_endpoint_devices_last_seen
    ON endpoint_devices (last_seen_at);
CREATE INDEX idx_endpoint_devices_domain
    ON endpoint_devices (domain_name);

CREATE TABLE endpoint_device_credentials (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    credential_key_id VARCHAR(128) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    previous_encrypted_secret TEXT,
    encryption_key_version VARCHAR(64),
    rotation_grace_until TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_endpoint_device_credentials_device UNIQUE (device_id),
    CONSTRAINT uq_endpoint_device_credentials_key UNIQUE (credential_key_id)
);

CREATE INDEX idx_endpoint_device_credentials_active
    ON endpoint_device_credentials (active);
CREATE INDEX idx_endpoint_device_credentials_grace
    ON endpoint_device_credentials (rotation_grace_until);

CREATE TABLE endpoint_enrollments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    enrollment_token_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    requested_by_subject VARCHAR(255) NOT NULL,
    note VARCHAR(512),
    device_id UUID REFERENCES endpoint_devices (id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_enrollments_status
        CHECK (status IN ('PENDING','CONSUMED','EXPIRED','REVOKED')),
    CONSTRAINT uq_endpoint_enrollments_token_hash UNIQUE (enrollment_token_hash)
);

CREATE INDEX idx_endpoint_enrollments_tenant_status
    ON endpoint_enrollments (tenant_id, status);
CREATE INDEX idx_endpoint_enrollments_expires
    ON endpoint_enrollments (expires_at);

CREATE TABLE endpoint_heartbeats (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    agent_version VARCHAR(128),
    os_version VARCHAR(255),
    ip_address VARCHAR(64),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_endpoint_heartbeats_device_received
    ON endpoint_heartbeats (device_id, received_at);
CREATE INDEX idx_endpoint_heartbeats_tenant_received
    ON endpoint_heartbeats (tenant_id, received_at);

CREATE TABLE endpoint_commands (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    command_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 100,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    visible_after_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    issued_by_subject VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    acked_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    last_error VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_commands_type
        CHECK (command_type IN (
            'COLLECT_INVENTORY',
            'LOCK_USER_LOGIN',
            'UNLOCK_USER_LOGIN',
            'CHANGE_LOCAL_PASSWORD',
            'SMB_LIST_ALLOWED_PATH',
            'SMB_READ_FILE_METADATA',
            'SMB_DOWNLOAD_FILE',
            'SMB_UPLOAD_FILE',
            'ROTATE_CREDENTIAL'
        )),
    CONSTRAINT ck_endpoint_commands_status
        CHECK (status IN ('QUEUED','DELIVERED','ACKED','RUNNING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_endpoint_commands_attempts
        CHECK (attempt_count >= 0 AND max_attempts >= 0),
    CONSTRAINT ck_endpoint_commands_priority
        CHECK (priority >= 0),
    CONSTRAINT uq_endpoint_commands_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_endpoint_commands_device_status
    ON endpoint_commands (device_id, status);
CREATE INDEX idx_endpoint_commands_tenant_status_visible
    ON endpoint_commands (tenant_id, status, visible_after_at);
CREATE INDEX idx_endpoint_commands_status_lock
    ON endpoint_commands (status, locked_until);
CREATE INDEX idx_endpoint_commands_issued_at
    ON endpoint_commands (tenant_id, issued_at);
CREATE INDEX idx_endpoint_commands_deliverable
    ON endpoint_commands (status, visible_after_at, priority, issued_at)
    WHERE status = 'QUEUED';

CREATE TABLE endpoint_command_results (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL REFERENCES endpoint_commands (id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    result_status VARCHAR(32) NOT NULL,
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    exit_code INTEGER,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_endpoint_command_results_status
        CHECK (result_status IN ('SUCCEEDED','FAILED','PARTIAL','UNSUPPORTED')),
    CONSTRAINT uq_endpoint_command_results_command UNIQUE (command_id)
);

CREATE INDEX idx_endpoint_command_results_device_reported
    ON endpoint_command_results (device_id, reported_at);
CREATE INDEX idx_endpoint_command_results_tenant_reported
    ON endpoint_command_results (tenant_id, reported_at);

CREATE TABLE endpoint_request_nonces (
    id UUID PRIMARY KEY,
    credential_id UUID NOT NULL REFERENCES endpoint_device_credentials (id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    nonce VARCHAR(255) NOT NULL,
    request_timestamp TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    request_hash VARCHAR(128),
    CONSTRAINT uq_endpoint_request_nonces_credential_nonce UNIQUE (credential_id, nonce)
);

CREATE INDEX idx_endpoint_request_nonces_expires
    ON endpoint_request_nonces (expires_at);
CREATE INDEX idx_endpoint_request_nonces_device_used
    ON endpoint_request_nonces (device_id, used_at);

CREATE TABLE endpoint_audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID REFERENCES endpoint_devices (id) ON DELETE SET NULL,
    command_id UUID REFERENCES endpoint_commands (id) ON DELETE SET NULL,
    event_type VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    performed_by_subject VARCHAR(255),
    correlation_id VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    before_state JSONB,
    after_state JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_endpoint_audit_events_tenant_occurred
    ON endpoint_audit_events (tenant_id, occurred_at);
CREATE INDEX idx_endpoint_audit_events_device_occurred
    ON endpoint_audit_events (device_id, occurred_at);
CREATE INDEX idx_endpoint_audit_events_command
    ON endpoint_audit_events (command_id);
CREATE INDEX idx_endpoint_audit_events_type
    ON endpoint_audit_events (event_type);
