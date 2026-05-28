-- Faz 22.3 — Backend mTLS machine-cert self-enrollment (ADR-0029 6-layer
-- architecture, backend layer). Codex plan-time consult thread
-- 019e692b-f023-75a1-a2e9-a915f9cd58ee PARTIAL (sequencing 1+3 now).
--
-- Adds endpoint_machine_certs as the source of truth for AD CS machine-cert
-- identities issued to enrolled endpoints. The cert's SAN URI of the form
-- `adcomputer:{objectGUID}` is the PRIMARY device identity (NOT hostname,
-- NOT machineFingerprint — those are secondary). Idempotent self-enrollment
-- looks up the active row by `san_uri`; if present the device is already
-- enrolled, otherwise a new device + cert pair is created in one transaction.
--
-- Audit model: reuse endpoint_audit_events V4 hash-chain. No new audit
-- table — `MACHINE_CERT_AUTO_ENROLL_SUCCESS` / `MACHINE_CERT_AUTO_ENROLL_FAILED`
-- event types append to the per-tenant chain like every other audit event.
--
-- Append-only / immutable approval rows:
--   * Active cert lifecycle: INSERT once; UPDATE only to mark revoked
--     (revoked_at + revoked_reason). No fields below are intended to mutate
--     after the initial enrollment.
--   * Revoking a cert FREES the partial unique index slot — a new cert with
--     a different san_uri (or even the same san_uri after revocation) can
--     enroll without colliding.
--
-- R24 bounded grace (ADR-0029 §"Backend layer", Codex 019e6dc9 P2-7 absorb):
--   `effective_not_after = cert_not_after + min(24h, age(last_good_crl_check))`
--   where `age(last_good_crl_check) = max(0, now - last_good_crl_check_ts)`.
--   I.e. grace equals the time we have been WITHOUT a fresh CRL, capped at
--   24 hours. Enforced at the SERVICE layer (CRL outage handling); the DB
--   constraint below only enforces `cert_not_after > cert_not_before`
--   because the runtime grace window depends on CRL freshness state that
--   the DB does not own.

CREATE TABLE endpoint_machine_certs (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    san_uri VARCHAR(512) NOT NULL,
    object_guid UUID NOT NULL,
    cert_serial VARCHAR(128) NOT NULL,
    cert_thumbprint VARCHAR(128) NOT NULL,
    cert_issuer VARCHAR(512) NOT NULL,
    cert_subject VARCHAR(512) NOT NULL,
    cert_not_before TIMESTAMPTZ NOT NULL,
    cert_not_after TIMESTAMPTZ NOT NULL,
    machine_fingerprint VARCHAR(512) NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_machine_certs_validity
        CHECK (cert_not_after > cert_not_before),
    CONSTRAINT ck_endpoint_machine_certs_revocation_pair
        CHECK (
            (revoked_at IS NULL AND revoked_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)
        )
);

-- One ACTIVE cert per device. A revoked cert frees the slot so a renewal can
-- enroll a new active row.
CREATE UNIQUE INDEX uq_endpoint_machine_certs_device_active
    ON endpoint_machine_certs (device_id)
    WHERE revoked_at IS NULL;

-- One ACTIVE cert per SAN URI (the canonical adcomputer:{objectGUID} string).
-- Cross-device SAN URI collision is impossible while both certs are active.
CREATE UNIQUE INDEX uq_endpoint_machine_certs_san_uri_active
    ON endpoint_machine_certs (san_uri)
    WHERE revoked_at IS NULL;

-- Forensic / dedupe lookups.
CREATE INDEX idx_endpoint_machine_certs_tenant_thumbprint
    ON endpoint_machine_certs (tenant_id, cert_thumbprint);

CREATE INDEX idx_endpoint_machine_certs_object_guid
    ON endpoint_machine_certs (object_guid);

CREATE INDEX idx_endpoint_machine_certs_tenant_enrolled
    ON endpoint_machine_certs (tenant_id, enrolled_at);
