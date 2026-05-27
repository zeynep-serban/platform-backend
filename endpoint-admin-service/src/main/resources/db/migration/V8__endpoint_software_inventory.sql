-- BE-020I — Software Inventory Ingest / Query (Faz 22.5.3A).
--
-- Stores the canonical read model that the agent `COLLECT_INVENTORY` command
-- result's software payload produces. The agent (AG-025 / AG-026) collects
-- installed software from HKLM + HKLM `WOW6432Node` (HKCU explicitly out of
-- scope) and ships it through the existing
-- `POST /api/v1/agent/commands/{commandId}/result` endpoint. BE-020I plugs the
-- canonical materialization in just before that result is persisted so the
-- snapshot + items rows are tenant-scoped, indexed and queryable from the
-- admin REST surface + (future) BE-023 compliance evaluator.
--
-- Two-table shape (Codex 019e6ab2 iter-2 AGREE):
--   * endpoint_software_inventory_snapshots: per-device latest canonical
--     summary row. UNIQUE(tenant_id, device_id) — every device has at most
--     one canonical snapshot; agent push paths upsert it.
--   * endpoint_software_inventory_items: per-app rows that hang off the
--     snapshot. Full `apps[]` replacement deletes the prior items + inserts
--     the new set inside the same ingest transaction.
--
-- Summary-only ingest semantics (Codex 019e6ab2 iter-2 acceptance):
--   The agent's default `COLLECT_INVENTORY` result is summary-only —
--   `includeSoftware=true` is required to ship the full `apps[]` array. A
--   summary-only ingest MUST NOT wipe a prior full snapshot. The service
--   layer enforces this by only running the items DELETE+INSERT when the
--   `apps` key is present in the payload (or the payload explicitly states
--   `includeSoftware=true && appCount==0`).
--
-- PII / sensitive-field policy (Codex 019e6ab2 iter-2 acceptance):
--   The endpoint-admin-service local
--   `SoftwareInventoryPayloadPolicy` validator runs BEFORE
--   `endpoint_command_results` is persisted. Forbidden fields
--   (licenseKey, productKey, raw uninstallString, raw MSI ProductCode
--   GUID, `C:\Users\...` paths, SID, bearer/JWT, token/password) are
--   fail-closed rejects. `msi_product_code_hash` accepts only the
--   `sha256:<16hex>` agent wire format.
--
-- Audit (BE-016 hash-chain reuse):
--   ENDPOINT_SOFTWARE_INVENTORY_INGESTED (first snapshot),
--   ENDPOINT_SOFTWARE_INVENTORY_REPLACED (full apps replacement),
--   ENDPOINT_SOFTWARE_INVENTORY_SUMMARY_UPDATED (summary-only). Metadata is
--   never the raw apps list / probe error text.
--
-- Migration sequence guard: tracked V1-V7 at branch creation.
-- V6 `endpoint_identity_compliance_index.sql` is a BE-015 prototype file
-- found untracked in the worktree (separate session ownership). BE-020I
-- claims V8 to avoid collision with that in-flight prototype. The
-- `BE-020 V7 catalog` migration is already merged on origin/main.
--
-- Codex plan-time consensus: thread 019e6ab2 iter-2 AGREE.
-- Tracking issue: platform-backend #309; cross-repo refs gitops
-- #1083 / #1086 / #1088 / #1090.

CREATE TABLE endpoint_software_inventory_snapshots (
    id                                  UUID            NOT NULL,
    tenant_id                           UUID            NOT NULL,
    device_id                           UUID            NOT NULL,
    latest_summary_command_result_id    UUID,
    latest_full_command_result_id       UUID,
    schema_version                      INTEGER         NOT NULL,
    supported                           BOOLEAN         NOT NULL,
    app_count                           INTEGER,
    apps_stored_count                   INTEGER,
    winget_ready                        BOOLEAN,
    winget_version                      VARCHAR(64),
    total_size_kb                       BIGINT,
    truncated                           BOOLEAN         NOT NULL DEFAULT FALSE,
    probe_errors                        JSONB,
    summary_collected_at                TIMESTAMPTZ,
    apps_collected_at                   TIMESTAMPTZ,
    apps_available                      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at                          TIMESTAMPTZ     NOT NULL,
    updated_at                          TIMESTAMPTZ     NOT NULL,
    version                             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_software_inventory_snapshots PRIMARY KEY (id),

    CONSTRAINT uq_endpoint_software_inventory_snapshots_tenant_device
        UNIQUE (tenant_id, device_id),

    CONSTRAINT fk_endpoint_software_inventory_snapshots_device
        FOREIGN KEY (device_id)
        REFERENCES endpoint_devices (id) ON DELETE CASCADE,

    CONSTRAINT fk_endpoint_software_inventory_snapshots_summary_result
        FOREIGN KEY (latest_summary_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL,

    CONSTRAINT fk_endpoint_software_inventory_snapshots_full_result
        FOREIGN KEY (latest_full_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL,

    CONSTRAINT ck_endpoint_software_inventory_snapshots_apps_available_pair
        CHECK ((apps_available = TRUE  AND apps_collected_at IS NOT NULL)
            OR (apps_available = FALSE))
);

-- Tenant-scoped browse + fast lookup for fleet-wide views with apps_available
-- filter (the future WEB-011 / BE-023 entry points only care about the
-- snapshots that actually shipped a full app set).
CREATE INDEX idx_endpoint_software_inventory_snapshots_tenant_apps_available
    ON endpoint_software_inventory_snapshots (tenant_id, apps_available);

-- Per-device fast lookup (the device-detail admin endpoint is hot path).
CREATE INDEX idx_endpoint_software_inventory_snapshots_device
    ON endpoint_software_inventory_snapshots (device_id);

CREATE TABLE endpoint_software_inventory_items (
    id                          UUID            NOT NULL,
    snapshot_id                 UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    device_id                   UUID            NOT NULL,
    display_name                VARCHAR(256)    NOT NULL,
    display_version             VARCHAR(128),
    publisher                   VARCHAR(256),
    install_date                VARCHAR(32),
    estimated_size_kb           BIGINT,
    architecture                VARCHAR(16),
    install_source              VARCHAR(32)     NOT NULL,
    uninstall_string_present    BOOLEAN         NOT NULL DEFAULT FALSE,
    msi_product_code_hash       VARCHAR(64),
    raw_item                    JSONB,
    created_at                  TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_software_inventory_items PRIMARY KEY (id),

    CONSTRAINT fk_endpoint_software_inventory_items_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES endpoint_software_inventory_snapshots (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_endpoint_software_inventory_items_install_source
        CHECK (install_source IN ('HKLM', 'HKLM_WOW6432'))
);

-- Tenant + device fast lookup for the device-detail admin endpoint.
CREATE INDEX idx_endpoint_software_inventory_items_tenant_device
    ON endpoint_software_inventory_items (tenant_id, device_id);

-- Fleet-wide softwareName filter (case-insensitive lookup uses LOWER() in
-- the query; the lowercased index on display_name is enough for MVP).
CREATE INDEX idx_endpoint_software_inventory_items_tenant_display_name_lower
    ON endpoint_software_inventory_items (tenant_id, LOWER(display_name));

-- Publisher filter (admin UI commonly groups by vendor).
CREATE INDEX idx_endpoint_software_inventory_items_tenant_publisher
    ON endpoint_software_inventory_items (tenant_id, publisher);
