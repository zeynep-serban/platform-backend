-- V52 — Faz 22.5.8 BE-029 approved package bundles control-plane.
--
-- BOUNDARY: Adds bundle definitions only. A bundle is an ordered set of
-- existing approved catalog items. This migration does NOT add install
-- dispatch, policy fan-out, raw package IDs, arbitrary URLs, or shell/script
-- execution. BE-026/BE-027/BE-028 provide ring/window/throttle primitives;
-- this slice adds the bundle primitive they can reference later.
--
-- ORG CONTRACT: mirrors the V47 catalog hub. Both tables keep tenant_id for
-- read paths and carry org_id for future org-composite FKs. endpoint_org_id_
-- compat trigger fills org_id=tenant_id; CHECK constraints validate the match.

CREATE TABLE endpoint_software_bundles (
    id                       UUID            NOT NULL,
    tenant_id                UUID            NOT NULL,
    org_id                   UUID,
    bundle_id                VARCHAR(128)    NOT NULL,
    display_name             VARCHAR(256)    NOT NULL,
    description              VARCHAR(1024),
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

    CONSTRAINT pk_endpoint_software_bundles PRIMARY KEY (id),
    CONSTRAINT endpoint_software_bundles_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT uq_endpoint_software_bundles_org_bundle UNIQUE (org_id, bundle_id),
    CONSTRAINT ck_endpoint_software_bundles_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'REVOKED')),
    CONSTRAINT endpoint_software_bundles_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_software_bundles_org_id_not_null
        CHECK (org_id IS NOT NULL),
    CONSTRAINT ck_endpoint_software_bundles_maker_checker
        CHECK (approved_by_subject IS NULL
            OR approved_by_subject <> created_by_subject),
    CONSTRAINT ck_endpoint_software_bundles_approval_pair
        CHECK ((approved_by_subject IS NULL AND approved_at IS NULL)
            OR (approved_by_subject IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_endpoint_software_bundles_revocation_pair
        CHECK ((revoked_by_subject IS NULL AND revoked_at IS NULL)
            OR (revoked_by_subject IS NOT NULL AND revoked_at IS NOT NULL))
);

DROP TRIGGER IF EXISTS endpoint_software_bundles_org_id_compat ON endpoint_software_bundles;
CREATE TRIGGER endpoint_software_bundles_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_software_bundles
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX idx_endpoint_software_bundles_tenant_status_enabled
    ON endpoint_software_bundles (tenant_id, status, enabled);

CREATE TABLE endpoint_software_bundle_items (
    id                 UUID         NOT NULL,
    tenant_id          UUID         NOT NULL,
    org_id             UUID,
    bundle_id          UUID         NOT NULL,
    catalog_item_id    UUID         NOT NULL,
    item_order         INTEGER      NOT NULL,
    required           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    last_updated_at    TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_software_bundle_items PRIMARY KEY (id),
    CONSTRAINT endpoint_software_bundle_items_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_software_bundle_items_org_id_not_null
        CHECK (org_id IS NOT NULL),
    CONSTRAINT ck_endpoint_software_bundle_items_order_non_negative
        CHECK (item_order >= 0),
    CONSTRAINT uq_endpoint_software_bundle_items_bundle_order
        UNIQUE (bundle_id, item_order),
    CONSTRAINT uq_endpoint_software_bundle_items_bundle_catalog
        UNIQUE (bundle_id, catalog_item_id),
    CONSTRAINT fk_endpoint_software_bundle_items_bundle
        FOREIGN KEY (bundle_id, org_id)
        REFERENCES endpoint_software_bundles (id, org_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_endpoint_software_bundle_items_catalog
        FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id)
        ON DELETE RESTRICT
);

DROP TRIGGER IF EXISTS endpoint_software_bundle_items_org_id_compat ON endpoint_software_bundle_items;
CREATE TRIGGER endpoint_software_bundle_items_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_software_bundle_items
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX idx_endpoint_software_bundle_items_tenant_bundle
    ON endpoint_software_bundle_items (tenant_id, bundle_id, item_order);
