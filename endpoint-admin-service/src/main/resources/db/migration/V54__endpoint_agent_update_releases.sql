-- V54 — BE-031 / AG-029 signed agent update release catalog.
--
-- BOUNDARY: release metadata/control-plane only. This migration does NOT add
-- UPDATE_AGENT dispatch, device targeting, raw URL execution, rollout fan-out
-- or agent-side trust bypass. Future self-update dispatch must resolve an
-- APPROVED+enabled row from this table and the agent must still verify the
-- binary hash/signature locally.
--
-- ORG CONTRACT: mirrors V47/V52 control-plane hubs. Reads stay tenant-keyed;
-- org_id is carried for future org-composite references and filled by the
-- endpoint_org_id_compat_fill trigger.

CREATE TABLE endpoint_agent_update_releases (
    id                       UUID            NOT NULL,
    tenant_id                UUID            NOT NULL,
    org_id                   UUID,
    release_id               VARCHAR(128)    NOT NULL,
    channel                  VARCHAR(16)     NOT NULL,
    target_version           VARCHAR(64)     NOT NULL,
    binary_url               VARCHAR(2048)   NOT NULL,
    manifest_url             VARCHAR(2048),
    sha256                   VARCHAR(64)     NOT NULL,
    sha512                   VARCHAR(128),
    signer_thumbprint        VARCHAR(64)     NOT NULL,
    signing_tier             VARCHAR(32)     NOT NULL,
    max_bytes                BIGINT          NOT NULL,
    release_notes            VARCHAR(2048),
    status                   VARCHAR(16)     NOT NULL,
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

    CONSTRAINT pk_endpoint_agent_update_releases PRIMARY KEY (id),
    CONSTRAINT endpoint_agent_update_releases_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT uq_endpoint_agent_update_releases_org_release
        UNIQUE (org_id, release_id),
    CONSTRAINT ck_endpoint_agent_update_releases_channel
        CHECK (channel IN ('STAGING', 'PILOT', 'STABLE')),
    CONSTRAINT ck_endpoint_agent_update_releases_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'REVOKED')),
    CONSTRAINT ck_endpoint_agent_update_releases_signing_tier
        CHECK (signing_tier IN ('TRUSTED_SIGNED', 'LAB_ONLY_EVIDENCE')),
    CONSTRAINT ck_endpoint_agent_update_releases_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_endpoint_agent_update_releases_sha512
        CHECK (sha512 IS NULL OR sha512 ~ '^[0-9a-f]{128}$'),
    CONSTRAINT ck_endpoint_agent_update_releases_thumbprint
        CHECK (signer_thumbprint ~ '^[0-9A-F]{40}$'
            OR signer_thumbprint ~ '^[0-9A-F]{64}$'),
    CONSTRAINT ck_endpoint_agent_update_releases_max_bytes
        CHECK (max_bytes > 0 AND max_bytes <= 524288000),
    CONSTRAINT endpoint_agent_update_releases_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_agent_update_releases_org_id_not_null
        CHECK (org_id IS NOT NULL),
    CONSTRAINT ck_endpoint_agent_update_releases_maker_checker
        CHECK (approved_by_subject IS NULL
            OR approved_by_subject <> created_by_subject),
    CONSTRAINT ck_endpoint_agent_update_releases_approval_pair
        CHECK ((approved_by_subject IS NULL AND approved_at IS NULL)
            OR (approved_by_subject IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_endpoint_agent_update_releases_revocation_pair
        CHECK ((revoked_by_subject IS NULL AND revoked_at IS NULL)
            OR (revoked_by_subject IS NOT NULL AND revoked_at IS NOT NULL))
);

DROP TRIGGER IF EXISTS endpoint_agent_update_releases_org_id_compat
    ON endpoint_agent_update_releases;
CREATE TRIGGER endpoint_agent_update_releases_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_agent_update_releases
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX idx_endpoint_agent_update_releases_tenant_status_enabled
    ON endpoint_agent_update_releases (tenant_id, status, enabled);

CREATE INDEX idx_endpoint_agent_update_releases_tenant_channel
    ON endpoint_agent_update_releases (tenant_id, channel, status);

COMMENT ON TABLE endpoint_agent_update_releases IS
    'BE-031: signed EndpointAgent self-update release catalog metadata only; no UPDATE_AGENT dispatch or rollout fan-out.';
