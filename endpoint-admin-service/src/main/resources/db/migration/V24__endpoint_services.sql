-- BE — Endpoint Critical Services Inventory (Faz 22.5, AG-039-be ingest).
-- Mirrors V23 AG-038-be diagnostics composite-FK pattern + extends to 3-table
-- (snapshot + entries + probe_errors) since "where state=STOPPED" queries are
-- the primary use-case (Codex 019e8302 iter-2 #5 absorb).
--
-- Wire contract: platform-agent docs/COMMAND-CONTRACT.md §18 (AG-039 PR #47
-- merged 2026-06-01). The AG-039 services probe block is carried under the
-- COLLECT_INVENTORY result at details.inventory.services (schemaVersion=1).
-- Source of truth = platform-agent internal/inventory/services.go
-- ServicesResult.
--
-- v1 canonical service allowlist (hard-coded; Codex 019e8302 iter-2 #1):
-- WinDefend, wuauserv, BITS, EventLog, EndpointAgent, MpsSvc. TermService
-- deferred to AG-040 (RDP/exposure scope).
--
-- REDACTION BOUNDARY (security invariant — DO NOT widen):
-- the per-entry wire shape is EXACTLY {name, present, state, startupMode};
-- ServicesProbeError carries Code + optional ServiceName (allowlist-only)
-- + optional Summary (bounded ≤200 chars, CRLF + control-char REJECT,
-- value-level URL/Bearer/IP/token denylist policy-side).
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) +
-- probeComplete=false (any SCM/registry probe error or partial list) are
-- persisted AS evidence. Consumers MUST NOT render an incomplete probe as
-- "all services healthy".
--
-- CANONICAL-FORM PAYLOAD HASH SCOPE (matches policy projection):
--   INCLUDED: schemaVersion, supported, probeComplete, services (full
--             ordered list with all 4 fields per entry), probeErrors
--             (ordered list with code + serviceName + summary), probeDurationMs.
--   EXCLUDED: none. Each fresh observation appends a new snapshot and
--             /latest reflects the most recent measured state.
--
-- COLLECTED_AT IS SERVER-CONTROLLED: from EndpointCommandResult.reportedAt
-- at ingest. Payload-level collectedAt would be REJECTED by strict-allowlist.

-- ---------------------------------------------------------------------------
-- SNAPSHOT ROOT TABLE
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_services_snapshots (
    id                          UUID                     NOT NULL,
    tenant_id                   UUID                     NOT NULL,
    device_id                   UUID                     NOT NULL,
    source_command_result_id    UUID                     NULL,
    schema_version              INTEGER                  NOT NULL,
    supported                   BOOLEAN                  NOT NULL,
    probe_complete              BOOLEAN                  NOT NULL,
    probe_duration_ms           INTEGER                  NOT NULL,
    payload_hash_sha256         VARCHAR(64)              NOT NULL,
    collected_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT svcs_snap_pk PRIMARY KEY (id),
    CONSTRAINT svcs_snap_id_tenant_uq UNIQUE (id, tenant_id),

    CONSTRAINT svcs_snap_schema_version_ck CHECK (schema_version = 1),
    CONSTRAINT svcs_snap_probe_duration_ck
        CHECK (probe_duration_ms >= 0 AND probe_duration_ms <= 120000),
    CONSTRAINT svcs_snap_payload_hash_re
        CHECK (payload_hash_sha256 ~ '^[0-9a-f]{64}$'),

    CONSTRAINT svcs_snap_device_fk
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT svcs_snap_source_cmd_fk
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id)
        ON DELETE SET NULL
);

CREATE UNIQUE INDEX svcs_snap_source_cmd_partial_uq
    ON endpoint_services_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

CREATE UNIQUE INDEX svcs_snap_tenant_device_hash_uq
    ON endpoint_services_snapshots (tenant_id, device_id, payload_hash_sha256);

CREATE INDEX svcs_snap_tenant_device_collected_at_ix
    ON endpoint_services_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- CHILD: SERVICE ENTRIES (allowlisted services)
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_services_entries (
    id              UUID         NOT NULL,
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    present         BOOLEAN      NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    startup_mode    VARCHAR(16)  NOT NULL,

    CONSTRAINT svcs_ent_pk PRIMARY KEY (id),
    CONSTRAINT svcs_ent_snap_name_uq UNIQUE (snapshot_id, name),
    CONSTRAINT svcs_ent_snap_ord_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT svcs_ent_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_services_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT svcs_ent_row_ordinal_ck CHECK (row_ordinal >= 0),

    -- Hard-coded canonical service allowlist (Codex 019e8302 iter-2 #1).
    CONSTRAINT svcs_ent_name_allowlist_ck
        CHECK (name IN ('WinDefend', 'wuauserv', 'BITS', 'EventLog',
                        'EndpointAgent', 'MpsSvc')),

    -- ServiceState wire enum.
    CONSTRAINT svcs_ent_state_ck
        CHECK (state IN ('RUNNING', 'STOPPED', 'DISABLED', 'UNKNOWN')),

    -- StartupMode wire enum (Codex iter-2 #3: AUTO_DELAYED separate).
    CONSTRAINT svcs_ent_startup_mode_ck
        CHECK (startup_mode IN ('AUTO', 'AUTO_DELAYED', 'MANUAL',
                                'DISABLED', 'UNKNOWN'))
);

CREATE INDEX svcs_ent_tenant_name_state_ix
    ON endpoint_services_entries (tenant_id, name, state);

CREATE INDEX svcs_ent_tenant_snapshot_ix
    ON endpoint_services_entries (tenant_id, snapshot_id, row_ordinal);

-- ---------------------------------------------------------------------------
-- CHILD: PROBE ERRORS
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_services_probe_errors (
    id              UUID         NOT NULL,
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    service_name    VARCHAR(64)  NULL,
    summary         VARCHAR(200) NULL,

    CONSTRAINT svcs_pe_pk PRIMARY KEY (id),
    CONSTRAINT svcs_pe_snap_ord_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT svcs_pe_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_services_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT svcs_pe_row_ordinal_ck CHECK (row_ordinal >= 0),

    -- ProbeError code bounded enum (Codex iter-1 #4: enum, not regex).
    CONSTRAINT svcs_pe_code_ck
        CHECK (code IN ('UNSUPPORTED_PLATFORM', 'SCM_UNAVAILABLE',
                        'SERVICE_NOT_FOUND', 'SERVICE_QUERY_FAILED',
                        'REGISTRY_QUERY_FAILED', 'NO_EVIDENCE')),

    -- service_name allowlist-only when present (Codex iter-1 #5).
    CONSTRAINT svcs_pe_service_name_allowlist_ck
        CHECK (
            service_name IS NULL
            OR service_name IN ('WinDefend', 'wuauserv', 'BITS', 'EventLog',
                                'EndpointAgent', 'MpsSvc')
        ),

    -- Summary bounded length + CR/LF reject (DB secondary guard;
    -- policy primary handles value-level denylist + tab/control-char).
    CONSTRAINT svcs_pe_summary_len_ck
        CHECK (
            summary IS NULL
            OR (char_length(summary) BETWEEN 1 AND 200)
        ),
    CONSTRAINT svcs_pe_summary_no_crlf_ck
        CHECK (
            summary IS NULL
            OR summary !~ '[\r\n]'
        )
);

CREATE INDEX svcs_pe_tenant_snapshot_ix
    ON endpoint_services_probe_errors (tenant_id, snapshot_id, row_ordinal);
