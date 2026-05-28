-- Wave-12 PR-5 — policy-change approval workflow back-end.
--
-- Mirrors the platform-web design-system ApprovalRequest contract for
-- type=policy_change plus PolicyApprovalDomainExtras. The history table
-- holds append-only DecisionRecord entries discriminated by `kind`.
--
-- Notes on shape:
-- * currentApprovers is stored as a JSON array on the parent row — it is
--   small (a handful of actors) and is rewritten atomically by delegate.
-- * Decision-kind-specific columns (delegate_*, statement, accepted_at,
--   reason) are nullable; the service layer enforces required-by-kind.
-- * The 4-eyes guard (proposer cannot APPROVE own request) is enforced
--   in the application layer and surfaces as HTTP 403 proposer_self.
-- * No FK to a policies table exists in this service — `target` carries
--   the policyId verbatim and is opaque to this service.

CREATE TABLE policy_change_approvals (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    title              VARCHAR(255) NOT NULL,
    target             VARCHAR(255) NOT NULL,
    proposer_subject   VARCHAR(255) NOT NULL,
    proposer_name      VARCHAR(255) NOT NULL,
    proposer_role      VARCHAR(64)  NOT NULL,
    reason             VARCHAR(2048) NOT NULL,
    evidence_refs      JSONB,
    change_kind        VARCHAR(16)  NOT NULL,
    risk_tier          VARCHAR(16)  NOT NULL,
    before_state       JSONB,
    after_state        JSONB,
    deadline           TIMESTAMPTZ,
    status             VARCHAR(32)  NOT NULL,
    current_approvers  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    row_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_policy_change_approvals_change_kind
        CHECK (change_kind IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_policy_change_approvals_risk_tier
        CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_policy_change_approvals_status
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED'))
);

CREATE INDEX idx_policy_change_approvals_tenant_status
    ON policy_change_approvals (tenant_id, status);

CREATE INDEX idx_policy_change_approvals_tenant_target
    ON policy_change_approvals (tenant_id, target);

CREATE INDEX idx_policy_change_approvals_tenant_proposer
    ON policy_change_approvals (tenant_id, proposer_subject);

CREATE TABLE policy_change_approval_decisions (
    id                 UUID PRIMARY KEY,
    approval_id        UUID NOT NULL,
    sequence           INTEGER NOT NULL,
    kind               VARCHAR(32) NOT NULL,
    actor_subject      VARCHAR(255) NOT NULL,
    actor_name         VARCHAR(255) NOT NULL,
    actor_role         VARCHAR(64)  NOT NULL,
    delegate_subject   VARCHAR(255),
    delegate_name      VARCHAR(255),
    delegate_role      VARCHAR(64),
    reason             VARCHAR(2048),
    statement          VARCHAR(4096),
    accepted_at        TIMESTAMPTZ,
    decided_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_policy_change_approval_decisions_kind
        CHECK (kind IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES', 'DELEGATE', 'ATTEST')),
    CONSTRAINT uq_policy_change_approval_decisions_seq
        UNIQUE (approval_id, sequence),
    CONSTRAINT fk_policy_change_approval_decisions_approval
        FOREIGN KEY (approval_id) REFERENCES policy_change_approvals (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_policy_change_approval_decisions_approval
    ON policy_change_approval_decisions (approval_id, sequence);
