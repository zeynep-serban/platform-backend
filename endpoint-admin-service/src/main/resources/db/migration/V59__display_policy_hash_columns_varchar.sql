-- V59 — #508 slice-2b: align the display-policy hash columns with VARCHAR(64).
--
-- V58 declared `policy_hash_sha256` and `wallpaper_asset_sha256` as CHAR(64)
-- (bpchar) on both endpoint_display_policy_revisions and
-- endpoint_display_policies. The slice-2b JPA entities
-- (EndpointDisplayPolicyRevision / EndpointDisplayPolicy) map these as plain
-- `String` (Hibernate VARCHAR), so `spring.jpa.hibernate.ddl-auto=validate`
-- fails with: "wrong column type ... found [bpchar (CHAR)], but expecting
-- [varchar(64)]". This is the SAME class of bug + the SAME fix as
-- V14 (BE-022, endpoint_hardware_inventory.payload_hash_sha256 CHAR->VARCHAR).
--
-- CHAR->VARCHAR is a safe widening: a SHA-256 hex digest is always exactly
-- 64 chars, so CHAR(64) never blank-pads and the cast preserves the value
-- verbatim. The V58 CHECK constraints (`... ~ '^[0-9a-f]{64}$'`) are unaffected
-- (regex semantics are identical on varchar) and remain the real guard. These
-- tables carry no rows before the slice-2b dispatch surface ships, so this is a
-- zero-data-risk type change.

ALTER TABLE endpoint_display_policy_revisions
    ALTER COLUMN policy_hash_sha256 TYPE VARCHAR(64),
    ALTER COLUMN wallpaper_asset_sha256 TYPE VARCHAR(64);

ALTER TABLE endpoint_display_policies
    ALTER COLUMN policy_hash_sha256 TYPE VARCHAR(64),
    ALTER COLUMN wallpaper_asset_sha256 TYPE VARCHAR(64);
