-- BE — Endpoint Application Control (WDAC + AppLocker) Snapshot
-- (Faz 22.5, AG-041-be ingest). Mirrors V24 AG-039-be services / V25 AG-040-be
-- startup-exposure 2-table layout: snapshot root + probe_errors child.
-- AG-041 has NO per-rule list (HARD BOUNDARY in agent contract — no rule
-- names / publishers / GUIDs / hashes / file paths), so there is no
-- per-entry child table — all wire scalars are flat on the root.
--
-- Wire contract: platform-agent docs/COMMAND-CONTRACT.md §20 (AG-041 PR
-- #49 MERGED 2026-06-01). The AG-041 appControl block is carried under
-- the COLLECT_INVENTORY result at details.inventory.appControl
-- (schemaVersion=1).
-- Source of truth = platform-agent internal/inventory/app_control.go
-- AppControlResult.
--
-- v1 stable wire shape (20 top-level keys, Codex 019e840e plan iter-2
-- AGREE absorb):
--   schemaVersion, supported, probeComplete,
--   wdacQueryable, appLockerQueryable, wdacMode,
--   wdacBootEnforcementPresent, wdacActiveCipPolicyCount,
--   wdacLegacySipolicyPresent, wdacMultiPolicyMode,
--   appLockerExeRule, appLockerDllRule, appLockerScriptRule,
--   appLockerMsiRule, appLockerAppxRule,
--   appLockerAppIdSvcState, appLockerAppIdSvcStartup,
--   appLockerAppIdSvcPresent,
--   probeDurationMs, probeErrors
--
-- REDACTION BOUNDARY (security invariant — DO NOT widen):
--   the persisted scalars contain ONLY enum values and bounded counts.
--   The probe_errors child carries Code + optional Source (3-value
--   wdac|appLocker|filesystem enum) + optional Summary (bounded ≤200
--   chars, CRLF + control-char REJECT, value-level URL/Bearer/IP/token
--   denylist policy-side via SUMMARY_VALUE_DENYLIST_RE reuse).
--   The wire shape NEVER carries: policyName / policyId / policyGuid /
--   policyHash / ruleName / ruleId / publisher / signerThumbprint /
--   commandLine / processName / exePath / filePath / eventLog / kbId.
--   AppLocker rule-list bodies are NEVER persisted (HARD BOUNDARY).
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) +
-- probe_complete=false (any decision-critical facet read failure) are
-- persisted AS evidence. Consumers MUST NOT render an incomplete probe
-- as "no application control" or as "enforcement enabled".
--
-- NULLABLE EVIDENCE COLUMNS (Codex 019e840e plan iter-1 must_fix #5 +
-- iter-2 #6 absorb): wdac_boot_enforcement_present /
-- wdac_active_cip_policy_count / wdac_legacy_sipolicy_present /
-- wdac_multi_policy_mode / app_locker_app_id_svc_present are NULLABLE
-- in the wire contract — `null` semantically means "not queryable /
-- not yet observed / untrustworthy" and is DISTINCT from `false`. We
-- mirror that in SQL with NULL columns. Sentinel values (-1, "UNSET")
-- are FORBIDDEN because they collide with valid observations.
--
-- CANONICAL-FORM PAYLOAD HASH SCOPE (matches policy projection):
--   INCLUDED: schemaVersion, supported, probeComplete, wdacQueryable,
--             appLockerQueryable, wdacMode, all 4 wdac evidence
--             nullable values (null preserved literal), all 5 appLocker
--             rule enums, all 3 appLocker AppIDSvc fields (state, startup,
--             present including null), probeDurationMs, probeErrors
--             (ordered list with code + source + summary).
--   EXCLUDED: none. Each fresh observation appends a new snapshot and
--             /latest reflects the most recent measured state.
--
-- COLLECTED_AT IS SERVER-CONTROLLED: from EndpointCommandResult.reportedAt
-- at ingest. Payload-level collectedAt would be REJECTED by strict-allowlist.

-- ---------------------------------------------------------------------------
-- SNAPSHOT ROOT TABLE
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_app_control_snapshots (
    id                                   UUID                     NOT NULL,
    tenant_id                            UUID                     NOT NULL,
    device_id                            UUID                     NOT NULL,
    source_command_result_id             UUID                     NULL,
    schema_version                       INTEGER                  NOT NULL,
    supported                            BOOLEAN                  NOT NULL,
    probe_complete                       BOOLEAN                  NOT NULL,

    -- Facet queryability (Codex iter-1 P0 #3 stable keys).
    wdac_queryable                       BOOLEAN                  NOT NULL,
    app_locker_queryable                 BOOLEAN                  NOT NULL,

    -- WDAC operational decision (Codex iter-1 P0 #2: UNKNOWN dominant).
    wdac_mode                            VARCHAR(16)              NOT NULL,

    -- WDAC evidence (NULLABLE — "not queryable / not yet observed" is
    -- distinct from `false`).
    wdac_boot_enforcement_present        BOOLEAN                  NULL,
    wdac_active_cip_policy_count         INTEGER                  NULL,
    wdac_legacy_sipolicy_present         BOOLEAN                  NULL,
    wdac_multi_policy_mode               BOOLEAN                  NULL,

    -- AppLocker per-rule-collection enforcement (5 collections).
    app_locker_exe_rule                  VARCHAR(16)              NOT NULL,
    app_locker_dll_rule                  VARCHAR(16)              NOT NULL,
    app_locker_script_rule               VARCHAR(16)              NOT NULL,
    app_locker_msi_rule                  VARCHAR(16)              NOT NULL,
    app_locker_appx_rule                 VARCHAR(16)              NOT NULL,

    -- AppIDSvc (the SCM service AppLocker depends on). The enum surface
    -- is the SAME as V24 endpoint_services state / startup_mode (Codex
    -- 019e840e plan iter-2 absorb #4: superset, NOT a narrower 4-value
    -- variant — agent emits AUTO_DELAYED for AppIDSvc startup mode and
    -- DISABLED for state).
    app_locker_app_id_svc_state          VARCHAR(16)              NOT NULL,
    app_locker_app_id_svc_startup        VARCHAR(16)              NOT NULL,
    app_locker_app_id_svc_present        BOOLEAN                  NULL,

    probe_duration_ms                    INTEGER                  NOT NULL,
    payload_hash_sha256                  VARCHAR(64)              NOT NULL,
    collected_at                         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT ac_snap_pk PRIMARY KEY (id),
    CONSTRAINT ac_snap_id_tenant_uq UNIQUE (id, tenant_id),

    CONSTRAINT ac_snap_schema_version_ck CHECK (schema_version = 1),
    CONSTRAINT ac_snap_probe_duration_ck
        CHECK (probe_duration_ms >= 0 AND probe_duration_ms <= 120000),
    CONSTRAINT ac_snap_payload_hash_re
        CHECK (payload_hash_sha256 ~ '^[0-9a-f]{64}$'),

    -- WDAC mode bounded 4-value enum (Codex 019e840e plan iter-1 P0 #2).
    CONSTRAINT ac_snap_wdac_mode_ck
        CHECK (wdac_mode IN ('OFF', 'AUDIT', 'ENFORCE', 'UNKNOWN')),

    -- WDAC nullable count non-negative when present.
    CONSTRAINT ac_snap_wdac_cip_count_ck
        CHECK (wdac_active_cip_policy_count IS NULL
               OR wdac_active_cip_policy_count >= 0),

    -- AppLocker per-collection enforcement bounded 4-value enum.
    CONSTRAINT ac_snap_appl_exe_ck
        CHECK (app_locker_exe_rule IN ('NOT_CONFIGURED', 'AUDIT_ONLY', 'ENFORCE', 'UNKNOWN')),
    CONSTRAINT ac_snap_appl_dll_ck
        CHECK (app_locker_dll_rule IN ('NOT_CONFIGURED', 'AUDIT_ONLY', 'ENFORCE', 'UNKNOWN')),
    CONSTRAINT ac_snap_appl_script_ck
        CHECK (app_locker_script_rule IN ('NOT_CONFIGURED', 'AUDIT_ONLY', 'ENFORCE', 'UNKNOWN')),
    CONSTRAINT ac_snap_appl_msi_ck
        CHECK (app_locker_msi_rule IN ('NOT_CONFIGURED', 'AUDIT_ONLY', 'ENFORCE', 'UNKNOWN')),
    CONSTRAINT ac_snap_appl_appx_ck
        CHECK (app_locker_appx_rule IN ('NOT_CONFIGURED', 'AUDIT_ONLY', 'ENFORCE', 'UNKNOWN')),

    -- AppIDSvc enum surfaces — mirror V24 endpoint_services CHECK
    -- constraints EXACTLY (Codex iter-2 absorb #4: superset preserved).
    --   service state: RUNNING / STOPPED / DISABLED / UNKNOWN (4 values)
    --   startup mode: AUTO / AUTO_DELAYED / MANUAL / DISABLED / UNKNOWN (5 values)
    CONSTRAINT ac_snap_appid_state_ck
        CHECK (app_locker_app_id_svc_state IN ('RUNNING', 'STOPPED', 'DISABLED', 'UNKNOWN')),
    CONSTRAINT ac_snap_appid_startup_ck
        CHECK (app_locker_app_id_svc_startup IN ('AUTO', 'AUTO_DELAYED', 'MANUAL', 'DISABLED', 'UNKNOWN')),

    -- probeComplete invariant (Codex 019e840e plan iter-2 absorb #3):
    -- probeComplete=true REQUIRES supported=true AND wdac_queryable=true
    -- AND app_locker_queryable=true. This is the contract-compliant
    -- IMPLICATION the agent actually computes (see
    -- platform-agent/internal/inventory/app_control_windows.go:122).
    -- A facet not queryable means the probe DID NOT attempt everything;
    -- probeComplete=true with any queryable=false would be a contract
    -- breach.
    CONSTRAINT ac_snap_probe_complete_implication_ck
        CHECK (probe_complete = false
               OR (supported = true
                   AND wdac_queryable = true
                   AND app_locker_queryable = true)),

    CONSTRAINT ac_snap_device_fk
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT ac_snap_source_cmd_fk
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id)
        ON DELETE SET NULL
);

CREATE UNIQUE INDEX ac_snap_source_cmd_partial_uq
    ON endpoint_app_control_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

CREATE UNIQUE INDEX ac_snap_tenant_device_hash_uq
    ON endpoint_app_control_snapshots (tenant_id, device_id, payload_hash_sha256);

CREATE INDEX ac_snap_tenant_device_collected_at_ix
    ON endpoint_app_control_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- Fleet-query index: "which devices have WDAC ENFORCE / AUDIT" — useful
-- for compliance dashboards.
CREATE INDEX ac_snap_tenant_wdac_mode_ix
    ON endpoint_app_control_snapshots (tenant_id, wdac_mode);

-- ---------------------------------------------------------------------------
-- CHILD: PROBE ERRORS
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_app_control_probe_errors (
    id              UUID         NOT NULL,
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    source          VARCHAR(16)  NULL,
    summary         VARCHAR(200) NULL,

    CONSTRAINT ac_pe_pk PRIMARY KEY (id),
    CONSTRAINT ac_pe_snap_ord_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT ac_pe_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_app_control_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT ac_pe_row_ordinal_ck CHECK (row_ordinal >= 0),

    -- ProbeError code bounded 8-value enum (Codex 019e840e plan iter-2
    -- absorb #9 — matches platform-agent app_control.go AppControlErr*
    -- constants exactly).
    CONSTRAINT ac_pe_code_ck
        CHECK (code IN ('NO_EVIDENCE',
                        'REGISTRY_DENIED',
                        'FILESYSTEM_DENIED',
                        'CIP_POLICIES_DIR_UNREADABLE',
                        'APPLOCKER_KEY_UNREADABLE',
                        'APP_ID_SVC_QUERY_FAILED',
                        'WDAC_SCALAR_UNREADABLE',
                        'PROBE_ERRORS_TRUNCATED')),

    -- source bounded 3-value enum (Codex iter-2 absorb #10 — lowercase,
    -- matches platform-agent AppControlProbeErrorSource constants).
    CONSTRAINT ac_pe_source_allowlist_ck
        CHECK (source IS NULL
               OR source IN ('wdac', 'appLocker', 'filesystem')),

    -- Summary bounded length + CR/LF reject (DB secondary guard; policy
    -- primary handles value-level denylist + tab/control-char).
    CONSTRAINT ac_pe_summary_len_ck
        CHECK (summary IS NULL
               OR (char_length(summary) BETWEEN 1 AND 200)),
    CONSTRAINT ac_pe_summary_no_crlf_ck
        CHECK (summary IS NULL
               OR summary !~ '[\r\n]')
);

CREATE INDEX ac_pe_tenant_snapshot_ix
    ON endpoint_app_control_probe_errors (tenant_id, snapshot_id, row_ordinal);
