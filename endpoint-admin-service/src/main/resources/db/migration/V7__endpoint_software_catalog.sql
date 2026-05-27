-- BE-020 — Approved Software Catalog (Faz 22.5.3).
--
-- Tenant-scoped catalog of approved software items for the Endpoint Admin /
-- Endpoint-Enes deployment hat. This migration adds the catalog table only;
-- install command flow, agent code, compliance evaluator and inventory ingest
-- are out of scope (BE-020I, BE-021, BE-021A, BE-023 future). NO OpenFGA model /
-- tuple changes; existing `module:endpoint-admin` `can_view`/`can_manage`
-- relations are reused at the @RequireModule annotation layer.
--
-- Status lifecycle: DRAFT -> APPROVED -> REVOKED. Maker-checker invariant:
-- approved_by_subject != created_by_subject (enforced at DB CHECK + service
-- layer; rejected attempts emit an
-- ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER audit event
-- using the BE-014A `noRollbackFor` durable-audit pattern so the reject row
-- survives transaction rollback).
--
-- Provider / source / trust split (Codex 019e6a3e iter-2 AGREE):
--   provider          : WINGET (MVP allowlist; MSI/EXE future)
--   source_type       : WINGET | INTERNAL_ARTIFACT | VENDOR_SIGNED_ARTIFACT
--   source_trust      : WINGET_COMMUNITY_REVIEWED | MICROSOFT_STORE |
--                       VENDOR_SIGNED_HASH_PINNED | INTERNAL_SIGNED
-- BE-020 MVP service-layer compatibility matrix: provider=WINGET implies
-- source_type=WINGET AND source_trust IN
-- (WINGET_COMMUNITY_REVIEWED, MICROSOFT_STORE). Other combinations remain
-- columns-valid but service-rejected; future providers (MSI/EXE) widen the
-- matrix without a schema change.
--
-- detection_rule JSONB polymorphism (discriminator: `type`). MVP allowlist:
-- WINGET_PACKAGE | REGISTRY_UNINSTALL | FILE_EXISTS | FILE_SHA256. Raw command
-- / shell variants are REJECTED at the service-layer validator (Codex
-- 019e6a3e iter-1 RED on `EXIT_CODE { command }`); the DB CHECK only enforces
-- shallow shape (`detection_rule ? 'type'`).
--
-- version_policy is semver-agnostic. WinGet/MSI version strings are not
-- guaranteed semver. BE-020 only validates shape/presence; the actual
-- compare/compliance evaluator is BE-023.
--
-- Codex plan-time consensus: thread 019e6a3e iter-2 AGREE. Tracking issue:
-- platform-backend #305; cross-repo refs gitops #1083 / #1086 / #1088 / #1090.
--
-- Migration sequence guard: at branch creation tracked migrations were V1-V5.
-- A V6 file (`V6__endpoint_identity_compliance_index.sql`) was found UNTRACKED
-- in the worktree (BE-015 admin identity compliance API prototype; separate
-- session ownership). To avoid a Flyway version collision with that in-flight
-- prototype, BE-020 conservatively claims V7. If BE-015 ships under a
-- different number, this V7 number does not need to renumber.

CREATE TABLE endpoint_software_catalog_items (
    id                       UUID            NOT NULL,
    tenant_id                UUID            NOT NULL,
    catalog_item_id          VARCHAR(128)    NOT NULL,
    status                   VARCHAR(16)     NOT NULL,
    provider                 VARCHAR(16)     NOT NULL,
    source_type              VARCHAR(32)     NOT NULL,
    source_name              VARCHAR(64)     NOT NULL,
    source_trust             VARCHAR(48)     NOT NULL,
    package_id               VARCHAR(128)    NOT NULL,
    display_name             VARCHAR(256)    NOT NULL,
    publisher                VARCHAR(128)    NOT NULL,
    version_policy_type      VARCHAR(16)     NOT NULL,
    version_policy_value     VARCHAR(64),
    installer_type           VARCHAR(16),
    silent_args_policy       VARCHAR(32),
    sha256                   VARCHAR(64),
    provenance               VARCHAR(256),
    detection_rule           JSONB           NOT NULL,
    risk_tier                VARCHAR(8)      NOT NULL,
    enabled                  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by_subject       VARCHAR(255)    NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL,
    last_updated_by_subject  VARCHAR(255)    NOT NULL,
    last_updated_at          TIMESTAMPTZ     NOT NULL,
    approved_by_subject      VARCHAR(255),
    approved_at              TIMESTAMPTZ,
    revoked_by_subject       VARCHAR(255),
    revoked_at               TIMESTAMPTZ,
    revocation_reason        VARCHAR(512),
    version                  BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_software_catalog_items PRIMARY KEY (id),

    CONSTRAINT uq_endpoint_software_catalog_items_tenant_catalog_item
        UNIQUE (tenant_id, catalog_item_id),

    CONSTRAINT ck_endpoint_software_catalog_items_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'REVOKED')),

    CONSTRAINT ck_endpoint_software_catalog_items_provider
        CHECK (provider IN ('WINGET')),

    CONSTRAINT ck_endpoint_software_catalog_items_source_type
        CHECK (source_type IN ('WINGET', 'INTERNAL_ARTIFACT', 'VENDOR_SIGNED_ARTIFACT')),

    CONSTRAINT ck_endpoint_software_catalog_items_source_trust
        CHECK (source_trust IN (
            'WINGET_COMMUNITY_REVIEWED',
            'MICROSOFT_STORE',
            'VENDOR_SIGNED_HASH_PINNED',
            'INTERNAL_SIGNED'
        )),

    CONSTRAINT ck_endpoint_software_catalog_items_version_policy_type
        CHECK (version_policy_type IN ('LATEST', 'EXACT', 'MINIMUM', 'RANGE')),

    CONSTRAINT ck_endpoint_software_catalog_items_installer_type
        CHECK (installer_type IS NULL
            OR installer_type IN ('WINGET_SILENT', 'MSI_SILENT', 'EXE_SILENT')),

    -- Silent args is an allowlisted policy keyword, never a free-text
    -- command-line string. Codex 019e6a3e post-impl PARTIAL absorb: keep
    -- the surface narrow so AG-027 cannot inherit a command-shaped value.
    CONSTRAINT ck_endpoint_software_catalog_items_silent_args_policy
        CHECK (silent_args_policy IS NULL
            OR silent_args_policy IN ('DEFAULT', 'VENDOR_RECOMMENDED')),

    CONSTRAINT ck_endpoint_software_catalog_items_risk_tier
        CHECK (risk_tier IN ('LOW', 'MED', 'HIGH')),

    CONSTRAINT ck_endpoint_software_catalog_items_detection_rule_shape
        CHECK (jsonb_typeof(detection_rule) = 'object'
            AND detection_rule ? 'type'),

    CONSTRAINT ck_endpoint_software_catalog_items_maker_checker
        CHECK (approved_by_subject IS NULL
            OR approved_by_subject <> created_by_subject),

    CONSTRAINT ck_endpoint_software_catalog_items_approval_pair
        CHECK ((approved_by_subject IS NULL AND approved_at IS NULL)
            OR (approved_by_subject IS NOT NULL AND approved_at IS NOT NULL)),

    CONSTRAINT ck_endpoint_software_catalog_items_revocation_pair
        CHECK ((revoked_by_subject IS NULL AND revoked_at IS NULL)
            OR (revoked_by_subject IS NOT NULL AND revoked_at IS NOT NULL))
);

-- Per-tenant browse / list (status filter; enabled-only fast path for the
-- future AG-027 / BE-021A install preflight lookup).
CREATE INDEX idx_endpoint_software_catalog_items_tenant_status_enabled
    ON endpoint_software_catalog_items (tenant_id, status, enabled);

-- Per-tenant lookup by package id (provider scoped; supports duplicate package
-- ids across providers in a future widening of the allowlist).
CREATE INDEX idx_endpoint_software_catalog_items_tenant_provider_package
    ON endpoint_software_catalog_items (tenant_id, provider, package_id);
