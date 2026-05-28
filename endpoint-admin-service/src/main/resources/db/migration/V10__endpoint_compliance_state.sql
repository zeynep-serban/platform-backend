-- BE-023 — Compliance State Evaluator (Faz 22.5) — append-only history +
-- latest-pointer hybrid backed by a separate tenant-scoped policy intent
-- table. Builds on V7 (catalog), V8 (inventory snapshots/items) and V9
-- (winget_egress columns) without breaking any existing contract.
--
-- Why three new tables (Codex thread 019e6bbf iter-3 AGREE):
--
--   * `endpoint_software_compliance_policy_items` — policy intent is a
--     separate concern from catalog metadata: the catalog records *what
--     software is approved*; the policy records *how that approval
--     translates to per-device enforcement* (REQUIRED to be installed,
--     ALLOWED but optional, FORBIDDEN must not be installed). Putting
--     enforcement on `endpoint_software_catalog_items` would conflate
--     these and force a row rewrite for every policy flip; a sibling
--     table also leaves a clean seam for a future device-group /
--     organisational-unit policy layer.
--
--   * `endpoint_compliance_evaluations` — append-only audit / history.
--     Every run of `EndpointComplianceService.evaluate(...)` writes one
--     row. Reads use the latest pointer for the hot path; the history
--     table answers "what was this device's compliance two weeks ago"
--     and "exactly which catalog + policy snapshot produced this
--     decision" via `catalog_policy_hash`.
--
--   * `endpoint_device_compliance_states` — latest-pointer read model
--     (one row per (tenant_id, device_id)). Hot-path GET reads a single
--     row, list endpoints filter on `decision`. No second optimistic
--     `@Version` concurrency layer; serialisation is provided by the
--     transactional `pg_try_advisory_xact_lock` the service acquires
--     before each evaluation. Out-of-order or concurrent writes are
--     refused at the lock (admin POST gets 409; event-driven trigger
--     silently skips because the next inventory commit will fire
--     another evaluation).
--
-- Why composite FK on policy -> catalog (Codex iter-3 critical_finding
-- #2 absorb): without DB-level tenant integrity, a service bug could
-- insert a policy row in tenant A pointing at a catalog row owned by
-- tenant B. The composite foreign key
-- `(catalog_item_id, tenant_id) -> endpoint_software_catalog_items(id,
-- tenant_id)` makes that physically impossible — the DB rejects the
-- INSERT/UPDATE before any service code runs. This is BE-023's "machine
-- enforced over self-attestation" parity with the BE-021A
-- `WinGetEgressPayloadPolicy` design line.
--
-- Decision values in `decision`:
--   COMPLIANT      — every REQUIRED policy item satisfied, no FORBIDDEN
--                    evidence, telemetry healthy (no UNKNOWN-driving
--                    reason).
--   NON_COMPLIANT  — at least one REQUIRED policy item missing /
--                    outdated, telemetry healthy, no FORBIDDEN evidence.
--   UNAUTHORIZED   — at least one FORBIDDEN policy item evidenced as
--                    installed on the device (preserved under HARD-stale
--                    inventory; staleness surfaces as a warning so the
--                    finding is not silently masked).
--   UNKNOWN        — telemetry insufficient (inventory missing/
--                    hard-stale/truncated/apps unavailable, version
--                    comparator fail-closed, policy catalog gap, empty
--                    policy, egress missing/unsupported/schema-bad) and
--                    no positive FORBIDDEN evidence to surface.
--
-- v1 scope explicit limitations (documented in PR body and service
-- Javadoc): UNAUTHORIZED is produced *only* by FORBIDDEN_APP_INSTALLED;
-- generic "installed software not in approved catalog" detection
-- (UNAPPROVED_APP_DETECTED) is deferred to BE-024 alongside an explicit
-- machine-readable `audited_scope_matcher` DSL — heuristic
-- displayName-contains matching is not safe enough to surface a
-- compliance verdict. Scheduled stale sweep also deferred to BE-024;
-- v1 surfaces per-stream staleness at every GET so clients can
-- re-trigger an evaluation proactively.
--
-- Migration sequence guard: V9 (BE-021A) is the last applied migration
-- on origin/main. V10 claims this slot exclusively for BE-023.
--
-- Codex plan-time consensus: thread 019e6bbf iter-3 AGREE.

-- ---------------------------------------------------------------------
-- 1. Composite-FK enabler on the existing catalog table.
-- The catalog already has `UNIQUE (tenant_id, catalog_item_id)`; we add
-- `UNIQUE (id, tenant_id)` exclusively so a composite FK can reference
-- both columns together. No row rewrite, no new column, no functional
-- change to BE-020 semantics.
-- ---------------------------------------------------------------------
ALTER TABLE endpoint_software_catalog_items
    ADD CONSTRAINT uq_endpoint_software_catalog_items_id_tenant
        UNIQUE (id, tenant_id);

-- ---------------------------------------------------------------------
-- 2. Policy intent table — per-tenant, per-catalog-item enforcement
-- mode. Default mode is ALLOWED so adding the table to an existing
-- tenant changes nothing until the operator explicitly seeds REQUIRED
-- or FORBIDDEN rows.
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_software_compliance_policy_items (
    id                       UUID            NOT NULL,
    tenant_id                UUID            NOT NULL,
    catalog_item_id          UUID            NOT NULL,
    enforcement_mode         VARCHAR(16)     NOT NULL,
    enabled                  BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by_subject       VARCHAR(255)    NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL,
    last_updated_by_subject  VARCHAR(255)    NOT NULL,
    last_updated_at          TIMESTAMPTZ     NOT NULL,
    version                  BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_software_compliance_policy_items
        PRIMARY KEY (id),

    -- Composite FK enforces tenant isolation at the database layer.
    CONSTRAINT fk_endpoint_software_compliance_policy_items_catalog
        FOREIGN KEY (catalog_item_id, tenant_id)
        REFERENCES endpoint_software_catalog_items (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_endpoint_software_compliance_policy_items_mode
        CHECK (enforcement_mode IN ('REQUIRED', 'ALLOWED', 'FORBIDDEN')),

    CONSTRAINT uq_endpoint_software_compliance_policy_items_tenant_catalog
        UNIQUE (tenant_id, catalog_item_id)
);

-- Hot path: "give me all enabled REQUIRED/FORBIDDEN policy rows for
-- tenant T" — used by the evaluator at every run.
CREATE INDEX idx_endpoint_software_compliance_policy_items_tenant_enabled_mode
    ON endpoint_software_compliance_policy_items (tenant_id, enabled, enforcement_mode);

-- ---------------------------------------------------------------------
-- 3. Append-only evaluation history. One row per evaluator run.
-- `evidence` holds the deterministic snapshot the decision was made
-- against (per-stream collectedAt, command result ids, matched-app
-- summaries); `catalog_policy_hash` lets a future audit reconstruct the
-- exact catalog/policy set without re-reading historical rows.
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_compliance_evaluations (
    id                              UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_id                       UUID            NOT NULL,
    evaluated_at                    TIMESTAMPTZ     NOT NULL,
    decision                        VARCHAR(16)     NOT NULL,
    reasons                         JSONB           NOT NULL,
    blocking_reasons                JSONB           NOT NULL,
    warnings                        JSONB           NOT NULL,
    evidence                        JSONB           NOT NULL,
    catalog_policy_hash             VARCHAR(64)     NOT NULL,
    inventory_snapshot_id           UUID,
    inventory_snapshot_row_version  BIGINT,
    catalog_row_version_max         BIGINT,
    policy_row_version_max          BIGINT,
    created_at                      TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_compliance_evaluations
        PRIMARY KEY (id),

    CONSTRAINT fk_endpoint_compliance_evaluations_device
        FOREIGN KEY (device_id)
        REFERENCES endpoint_devices (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_endpoint_compliance_evaluations_decision
        CHECK (decision IN ('COMPLIANT', 'NON_COMPLIANT', 'UNAUTHORIZED', 'UNKNOWN')),

    CONSTRAINT ck_endpoint_compliance_evaluations_reasons_shape
        CHECK (jsonb_typeof(reasons) = 'array'),

    CONSTRAINT ck_endpoint_compliance_evaluations_blocking_shape
        CHECK (jsonb_typeof(blocking_reasons) = 'array'),

    CONSTRAINT ck_endpoint_compliance_evaluations_warnings_shape
        CHECK (jsonb_typeof(warnings) = 'array'),

    CONSTRAINT ck_endpoint_compliance_evaluations_evidence_shape
        CHECK (jsonb_typeof(evidence) = 'object')
);

-- Latest-per-device descending lookup (history pagination).
CREATE INDEX idx_endpoint_compliance_evaluations_tenant_device_time
    ON endpoint_compliance_evaluations (tenant_id, device_id, evaluated_at DESC);

-- Cross-device decision-filter list (future BE-024 reporting + admin UI).
CREATE INDEX idx_endpoint_compliance_evaluations_tenant_decision_time
    ON endpoint_compliance_evaluations (tenant_id, decision, evaluated_at DESC);

-- ---------------------------------------------------------------------
-- 4. Latest-pointer read model. UPDATE only — the row is created the
-- first time a device is evaluated and replaced in place on every
-- subsequent evaluation. Cross-device list endpoint reads from here.
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_device_compliance_states (
    tenant_id              UUID            NOT NULL,
    device_id              UUID            NOT NULL,
    latest_evaluation_id   UUID            NOT NULL,
    decision               VARCHAR(16)     NOT NULL,
    evaluated_at           TIMESTAMPTZ     NOT NULL,
    updated_at             TIMESTAMPTZ     NOT NULL,
    version                BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_device_compliance_states
        PRIMARY KEY (tenant_id, device_id),

    CONSTRAINT fk_endpoint_device_compliance_states_device
        FOREIGN KEY (device_id)
        REFERENCES endpoint_devices (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_endpoint_device_compliance_states_evaluation
        FOREIGN KEY (latest_evaluation_id)
        REFERENCES endpoint_compliance_evaluations (id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_endpoint_device_compliance_states_decision
        CHECK (decision IN ('COMPLIANT', 'NON_COMPLIANT', 'UNAUTHORIZED', 'UNKNOWN'))
);

-- Cross-device list endpoint:
-- `GET /api/v1/admin/compliance/devices?decision=NON_COMPLIANT`
CREATE INDEX idx_endpoint_device_compliance_states_tenant_decision_time
    ON endpoint_device_compliance_states (tenant_id, decision, evaluated_at DESC);
