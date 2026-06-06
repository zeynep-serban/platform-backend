-- V51 — Faz 22.5.8 BE-026 deployment rings / device tags foundation.
--
-- BOUNDARY: Adds device-scoped rollout metadata only. It does NOT implement
-- maintenance windows, throttling, bundles, policy fan-out, or automatic
-- dispatch. Those are BE-027..BE-029. This slice gives later rollout controls
-- a stable org-visible device ring/tag surface and a dispatch eligibility
-- guard for explicit install requests.
--
-- WHY ON endpoint_devices: rollout eligibility is a property of the target
-- endpoint, not a command. Commands can carry a requiredDeploymentRing snapshot
-- for audit, but the authoritative mutable assignment lives on the device.
--
-- LOCK BUDGET: additive columns with defaults + CHECK + indexes. endpoint
-- devices is small in current test/prod presence; Flyway runs at bootstrap.

ALTER TABLE endpoint_devices
    ADD COLUMN IF NOT EXISTS deployment_ring VARCHAR(32) NOT NULL DEFAULT 'PILOT';

ALTER TABLE endpoint_devices
    ADD COLUMN IF NOT EXISTS device_tags JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_deployment_ring_check
        CHECK (deployment_ring IN ('PILOT', 'IT', 'DEPARTMENT', 'ALL'));

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_device_tags_json_array
        CHECK (jsonb_typeof(device_tags) = 'array');

CREATE INDEX IF NOT EXISTS idx_endpoint_devices_org_deployment_ring
    ON endpoint_devices(org_id, deployment_ring);

CREATE INDEX IF NOT EXISTS idx_endpoint_devices_device_tags_gin
    ON endpoint_devices USING GIN (device_tags);
