-- BE — Endpoint Agent Self-Diagnostics (Faz 22.5, AG-038-be diagnostics ingest).
-- Mirrors the AG-037 V22 hotfix-posture composite-FK precedent:
--   (a) child probe_errors table with tenant_id + composite-FK to root
--       (snapshot_id, tenant_id) -> (id, tenant_id), enforcing tenant-scoped
--       referential integrity at the DB layer (Codex 019e82d7 iter-1 P1);
--   (b) flat lastError triad scalars on the snapshot root (no 1:0..1 child
--       table to avoid @OneToOne hazards — same trade-off as V22's flat
--       agent-health scalars);
--   (c) BOTH a partial UNIQUE on source_command_result_id AND a full UNIQUE
--       on (tenant_id, device_id, payload_hash_sha256) so the canonical-form
--       payload-hash idempotency contract is enforced by the DB at the same
--       physical layer as the source_command_result_id idempotency; both are
--       caught race-clean by the service's targetless
--       INSERT ... ON CONFLICT DO NOTHING write path with sequential winner
--       re-lookup (Codex 019e82d7 iter-2 AGREE — same as V22 pattern).
--
-- Wire contract: platform-agent docs/COMMAND-CONTRACT.md §17 (AG-038 PR #39
-- merged 2026-05-30 commit 67bd4ba). The AG-038 diagnostics probe block is
-- carried under the COLLECT_INVENTORY result at details.inventory.diagnostics
-- (schemaVersion=1). Source of truth = platform-agent
-- internal/inventory/diagnostics.go DiagnosticsResult.
--
-- The probe is read-only (agent config + DNS lookup + TLS handshake for
-- reachability) — it NEVER mutates agent or backend state. Backend ingest is
-- a persist/query path; it MUST NOT trigger any agent-side mutation from this
-- payload.
--
-- REDACTION BOUNDARY (security invariant — machine-enforced at the agent +
-- the backend DiagnosticsPayloadPolicy):
-- the wire shape carries NO credential / token / raw API URL / host. Only:
--   - agentVersion (semver-like or "unknown")
--   - configHash (sha256(agentVersion+apiURL) one-way; never reversed)
--   - lastPollLatencyMs (int)
--   - backendDNSReachable / backendTLSValid (bool tri-state-ish: bool +
--     probeErrors[] for context)
--   - lastError {occurredAt, code, summary} optional, summary bounded
--     ≤200 chars CRLF-stripped + tab/control-char-stripped (policy strict)
--   - probeErrors[] each {code, summary?} where code is bounded enum
--     `^[A-Z][A-Z0-9_]{2,64}$` and summary same bounded-text discipline
--   - probeDurationMs int (INCLUDED in canonical-form hash per Codex iter-3 P1 #4)
--
-- The DiagnosticsPayloadPolicy fail-closed REJECTS forbidden keys (raw
-- apiURL, host, credentialId, token, apiKey, bearer, authorization, cookie,
-- session, secret, password) BEFORE the parent command-result row is
-- persisted.
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) and
-- probe_complete=false (any probeError or no-evidence run) are persisted AS
-- evidence (the agent still emits canonical metadata so the backend records
-- "probe not supported / incomplete here" instead of treating absence as a
-- failed ingest). Consumers MUST NOT render an incomplete probe as "agent
-- healthy".
--
-- CANONICAL-FORM PAYLOAD HASH SCOPE (matches policy projection, Codex
-- 019e82d7 iter-3 P1 #4 revise):
--   INCLUDED in hash bytes (every persistable field):
--     schemaVersion, supported, probeComplete, agentVersion, configHash
--     (as-is, lowercase hex), lastPollLatencyMs, probeDurationMs,
--     backendDNSReachable, backendTLSValid, lastError (full triad with
--     UTC-normalized occurredAt), probeErrors (ordered list with codes +
--     summaries).
--   EXCLUDED from hash bytes: none. Each fresh observation appends a
--   new snapshot and /latest reflects the most recent measured latency
--   and duration. Iter-2's "exclude timing" heuristic was reversed in
--   iter-3 P1 #4 because it caused /latest staleness on
--   state-A→B→A and latency-only-change cases.
-- Pure-dedupe / retry-idempotency: identical canonical bytes (same
-- latency + duration + every other field) map to the existing snapshot
-- via the pre-probe + (tenant, device, hash) UNIQUE.
--
-- COLLECTED_AT IS SERVER-CONTROLLED (Codex 019e82d7 iter-1 #4): the
-- snapshot collected_at column is populated from
-- EndpointCommandResult.reportedAt at ingest, NOT from any payload field. The
-- agent wire shape has no payload-level `collectedAt` (intentional). A
-- payload key named `collectedAt` would be REJECTED by the strict-allowlist
-- policy (no silent acceptance of unknown keys).

-- NOTE: No explicit BEGIN/COMMIT — Flyway wraps each migration in its own
-- transaction. Explicit BEGIN inside a Flyway script triggers
-- "transaction in progress" warning and leaves nested txn state inconsistent.

-- ---------------------------------------------------------------------------
-- SNAPSHOT ROOT TABLE
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_diagnostics_snapshots (
    id                          UUID                     NOT NULL,
    tenant_id                   UUID                     NOT NULL,
    device_id                   UUID                     NOT NULL,
    source_command_result_id    UUID                     NULL,
    schema_version              INTEGER                  NOT NULL,
    supported                   BOOLEAN                  NOT NULL,
    probe_complete              BOOLEAN                  NOT NULL,
    -- live agent self-report (redacted; configHash is one-way)
    agent_version               VARCHAR(64)              NOT NULL,
    config_hash                 VARCHAR(64)              NOT NULL,
    -- operational metric (persisted; INCLUDED in canonical-form hash so
    -- a fresh observation with different latency appends a new snapshot
    -- per Codex iter-3 P1 #4 revise)
    last_poll_latency_ms        INTEGER                  NOT NULL,
    -- backend reachability snapshot
    backend_dns_reachable       BOOLEAN                  NOT NULL,
    backend_tls_valid           BOOLEAN                  NOT NULL,
    -- lastError triad (flat scalars; nullable as a triad)
    last_error_occurred_at      TIMESTAMP WITH TIME ZONE NULL,
    last_error_code             VARCHAR(64)              NULL,
    last_error_summary          VARCHAR(200)             NULL,
    -- probe execution timing (persisted, INCLUDED in canonical-form hash
    -- so a fresh observation with different duration appends a new
    -- snapshot per Codex iter-3 P1 #4 revise)
    probe_duration_ms           INTEGER                  NOT NULL,
    -- canonical-form payload hash (lowercase hex)
    payload_hash_sha256         VARCHAR(64)              NOT NULL,
    -- server-controlled timestamps (NOT from payload)
    collected_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT diag_snap_pk PRIMARY KEY (id),

    -- composite-UNIQUE for tenant-scoped FK from child probe_errors table
    -- (Codex 019e82d7 iter-1 #1)
    CONSTRAINT diag_snap_id_tenant_uq UNIQUE (id, tenant_id),

    -- ----- schema validation -----
    CONSTRAINT diag_snap_schema_version_ck
        CHECK (schema_version = 1),

    -- agentVersion: semver-ish (optional pre-release / build metadata) or
    -- "unknown" sentinel — bounded length cap (Codex 019e82d7 iter-1 #5
    -- relaxed acceptance).
    CONSTRAINT diag_snap_agent_version_re
        CHECK (
            agent_version = 'unknown'
            OR agent_version ~ '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?(\+[A-Za-z0-9.-]+)?$'
        ),

    -- configHash: lowercase hex sha256 OR explicit "unknown" sentinel
    -- (uppercase NOT silently normalized; policy REJECT — Codex 019e82d7
    -- iter-2 #6).
    CONSTRAINT diag_snap_config_hash_re
        CHECK (
            config_hash = 'unknown'
            OR config_hash ~ '^[0-9a-f]{64}$'
        ),

    -- lastPollLatencyMs / probeDurationMs: non-negative ints.
    CONSTRAINT diag_snap_last_poll_latency_ck
        CHECK (last_poll_latency_ms >= 0),
    CONSTRAINT diag_snap_probe_duration_ck
        CHECK (probe_duration_ms >= 0),

    -- lastError triad: all-null or all-present (Codex 019e82d7 iter-1 #2,
    -- iter-2 #3 — conditional + bounded discipline).
    CONSTRAINT diag_snap_last_error_triad_ck
        CHECK (
            (last_error_occurred_at IS NULL
              AND last_error_code IS NULL
              AND last_error_summary IS NULL)
            OR
            (last_error_occurred_at IS NOT NULL
              AND last_error_code IS NOT NULL
              AND last_error_summary IS NOT NULL)
        ),

    -- lastError.code bounded enum.
    CONSTRAINT diag_snap_last_error_code_re
        CHECK (
            last_error_code IS NULL
            OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,64}$'
        ),

    -- lastError.summary bounded length + CR/LF reject (conditional form so
    -- NULL doesn't accidentally evaluate to UNKNOWN — Codex 019e82d7 iter-2
    -- #3).
    CONSTRAINT diag_snap_last_error_summary_len_ck
        CHECK (
            last_error_summary IS NULL
            OR (char_length(last_error_summary) BETWEEN 1 AND 200)
        ),
    CONSTRAINT diag_snap_last_error_summary_no_crlf_ck
        CHECK (
            last_error_summary IS NULL
            OR last_error_summary !~ '[\r\n]'
        ),

    -- canonical-form payload hash: lowercase hex sha256 only.
    CONSTRAINT diag_snap_payload_hash_re
        CHECK (payload_hash_sha256 ~ '^[0-9a-f]{64}$'),

    -- Codex 019e82d7 iter-3 P1 #3 + iter-4 P2 absorb: parity with V20
    -- outdated-software + V22 hotfix-posture FK shape — root tablo
    -- `endpoint_devices` ve `endpoint_command_results` referansları ile
    -- tenant-scoped referential integrity korunur (orphan snapshot +
    -- dangling source_command_result_id engellenir). Device delete
    -- cascades to diagnostics history (V20/V22 parity); command-result
    -- delete sets source pointer NULL (history-preserving).
    CONSTRAINT diag_snap_device_fk
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT diag_snap_source_cmd_fk
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id)
        ON DELETE SET NULL
);

-- partial UNIQUE on source_command_result_id (one snapshot per command result,
-- NULL allowed for back-fill ingests).
CREATE UNIQUE INDEX diag_snap_source_cmd_partial_uq
    ON endpoint_diagnostics_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- full UNIQUE on (tenant_id, device_id, payload_hash_sha256) for canonical-
-- form idempotency.
CREATE UNIQUE INDEX diag_snap_tenant_device_hash_uq
    ON endpoint_diagnostics_snapshots
       (tenant_id, device_id, payload_hash_sha256);

-- per-device timeline lookup (latest + history paging).
CREATE INDEX diag_snap_tenant_device_collected_at_ix
    ON endpoint_diagnostics_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- CHILD: PROBE ERRORS
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_diagnostics_probe_errors (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    summary         VARCHAR(200) NULL,

    CONSTRAINT diag_pe_pk PRIMARY KEY (id),
    CONSTRAINT diag_pe_snapshot_ordinal_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT diag_pe_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_diagnostics_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT diag_pe_row_ordinal_ck CHECK (row_ordinal >= 0),

    CONSTRAINT diag_pe_code_re
        CHECK (code ~ '^[A-Z][A-Z0-9_]{2,64}$'),

    CONSTRAINT diag_pe_summary_len_ck
        CHECK (
            summary IS NULL
            OR (char_length(summary) BETWEEN 1 AND 200)
        ),
    CONSTRAINT diag_pe_summary_no_crlf_ck
        CHECK (
            summary IS NULL
            OR summary !~ '[\r\n]'
        )
);

CREATE INDEX diag_pe_tenant_snapshot_ix
    ON endpoint_diagnostics_probe_errors
       (tenant_id, snapshot_id, row_ordinal);
