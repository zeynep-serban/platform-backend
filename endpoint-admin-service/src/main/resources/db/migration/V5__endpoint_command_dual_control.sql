-- BE-017 — destructive command dual-control gate.
--
-- Adds a two-person approval gate for destructive endpoint commands. A
-- destructive command is created with approval_status = PENDING and stays
-- invisible to the agent claim path until a SECOND admin approves it. The
-- CommandStatus delivery lifecycle stays orthogonal (Codex 019e50a5 Q2) —
-- approval is tracked on its own column + table.
--
-- Scope (Codex 019e50a5): NO FK / parent-delete change (the V4 audit FK
-- ON DELETE SET NULL caveat is a separate device-decommission task), NO
-- can_signoff OpenFGA relation. This migration only adds the approval
-- column + the decision-record table.
--
-- BE-016 follow-up note: with Flyway gitops-managed (baseline at V4), this
-- is the first migration Flyway applies on the test cluster.

ALTER TABLE endpoint_commands
    ADD COLUMN approval_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED';

ALTER TABLE endpoint_commands
    ADD CONSTRAINT ck_endpoint_commands_approval_status
    CHECK (approval_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED'));

-- Per-device agent-claim partial index: a command is claimable only once its
-- approval gate is cleared. Mirrors the per-device claim-query predicate
-- (findClaimCandidatesForDevice). Deliberately a DISTINCT name from V2's
-- global idx_endpoint_commands_deliverable (status,visible_after_at,priority,
-- issued_at) which serves the batch claim path — the two indexes coexist.
CREATE INDEX idx_endpoint_commands_device_deliverable
    ON endpoint_commands (device_id, priority, issued_at)
    WHERE status = 'QUEUED' AND approval_status IN ('NOT_REQUIRED', 'APPROVED');

-- One terminal approval decision per command (issuer != approver enforced
-- in the application layer).
CREATE TABLE endpoint_command_approvals (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    command_id         UUID NOT NULL,
    issuer_subject     VARCHAR(255) NOT NULL,
    decided_by_subject VARCHAR(255) NOT NULL,
    decision           VARCHAR(16) NOT NULL,
    reason             VARCHAR(512),
    decided_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_endpoint_command_approvals_decision
        CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT uq_endpoint_command_approvals_command UNIQUE (command_id),
    CONSTRAINT fk_endpoint_command_approvals_command
        FOREIGN KEY (command_id) REFERENCES endpoint_commands (id) ON DELETE RESTRICT
);

CREATE INDEX idx_endpoint_command_approvals_tenant
    ON endpoint_command_approvals (tenant_id);
