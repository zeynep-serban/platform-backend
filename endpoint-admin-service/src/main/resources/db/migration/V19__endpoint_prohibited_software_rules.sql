-- BE-025 — Prohibited software detection denylist (Faz 22.5, prohibited
-- software detection). Codex thread 019e7623 plan-time PARTIAL →
-- ready_for_impl (refinements absorbed).
--
-- WHY THIS TABLE EXISTS
-- ---------------------
-- BE-023 compliance enforcement (endpoint_software_compliance_policy_items,
-- V9) is CATALOG-BOUND: every policy row carries a composite FK to an
-- approved-catalog item. A FORBIDDEN policy there therefore only fires for
-- software that ALSO exists in the approved catalog — contradictory for
-- real "prohibited / banned / malware" software, which you would never add
-- to the approved catalog in the first place.
--
-- BE-025 therefore adds a SEPARATE, NON-catalog-bound, tenant-scoped
-- denylist. A rule matches the device's CURRENT installed-software
-- inventory (endpoint_software_inventory_items) directly by display name
-- and/or publisher. EndpointComplianceService loads the enabled rules for
-- the tenant during the same evaluation that already runs the BE-023
-- REQUIRED/FORBIDDEN catalog loop, and a match contributes the
-- `prohibited_app_installed` reason (Severity.UNAUTHORIZED) — same
-- decision bucket as `forbidden_app_installed`. NO new ComplianceDecision
-- enum value: PROHIBITED collapses into UNAUTHORIZED at the decision level;
-- the reason code distinguishes the source.
--
-- STRICT v1 SCOPE
-- ---------------
-- Detection + surface ONLY. NO auto-uninstall, NO remediation / command
-- dispatch, NO outdated-version logic (that is AG-036 / BE-024b), NO bulk
-- rollout. A match produces an alert/compliance state and nothing else.
--
-- MATCHING (NO REGEX)
-- -------------------
-- match_type ∈ {NAME, PUBLISHER, NAME_AND_PUBLISHER} selects which
-- installed field(s) the rule compares against; match_mode ∈ {EXACT,
-- CONTAINS} selects literal equality vs bounded substring. There is NO
-- user-supplied regex (ReDoS / injection surface) and NO glob. The pattern
-- is compared in JAVA (case-insensitive, Locale.ROOT) against the
-- already-sanitized inventory item fields — it NEVER reaches a SQL LIKE
-- clause, so there is no SQL wildcard-injection surface. (The regexp_replace
-- below is a DB-internal NORMALIZER on the operator-authored pattern, not a
-- user-supplied match expression — it never touches inventory data.)
--
-- NORMALIZATION SINGLE SOURCE OF TRUTH (Codex 019e763a REVISE #2)
-- --------------------------------------------------------------
-- The application
-- (ProhibitedSoftwareRuleValidator#trimToNull / #normalize) is the
-- authority: it trims with Java String.trim() (strips EVERY char <= U+0020)
-- and stores the trimmed value, so the stored column never carries edge
-- whitespace. These CHECK / UNIQUE expressions are a pure backstop for a
-- direct (non-application) INSERT, and they trim with the SAME character set
-- as Java String.trim() — the range [\x00-\x20] — instead of the bare
-- btrim() (which strips ONLY U+0020 space, NOT tab/newline/CR and so
-- disagreed with the validator). DEL (U+007F) is intentionally NOT in the
-- set because Java String.trim() keeps it (it is > U+0020). This makes the
-- app validator and the DB backstop agree on every input, so a value that
-- the app accepts can never trip the DB CHECK and a value the app rejects is
-- rejected identically by the DB. A pattern that still reaches the backstop
-- with edge control characters is therefore a genuine non-application bug,
-- and the service maps such a CHECK violation to HTTP 400 (invalid input),
-- distinct from the UNIQUE-dedup 409.
--
-- REDACTION BOUNDARY
-- ------------------
-- The denylist stores only operator-authored patterns + a server-side
-- `notes` field. The notes / created_by_subject are NEVER echoed into
-- compliance evidence or onto the device-facing read surface — only the
-- rule id + the redacted matched inventory fields (displayName / publisher
-- / version, the same boundary as the inventory itself) appear there. No
-- raw path / registry key / uninstall string is involved at all (the
-- denylist does not touch those columns).
--
-- MIGRATION SEQUENCE GUARD: V18
-- (endpoint_software_inventory_state_history, BE-024) was the last applied
-- migration on origin/main. V19 claims this slot exclusively for BE-025.
-- This is purely additive — no existing table / column / constraint is
-- altered.
--
-- IDENTIFIER BYTE BUDGET: the longest name below
-- ("ck_endpoint_prohibited_software_rules_name_pattern_blank") is 56 bytes,
-- under PostgreSQL's 63-byte identifier limit, so PG stores every name
-- verbatim and the migration / entity @Index / PG integration-test names
-- all agree.

CREATE TABLE endpoint_prohibited_software_rules (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    -- Which installed field(s) the rule matches. Enum stored as text;
    -- the allowed set is pinned by ck_..._match_consistency below.
    match_type                  VARCHAR(32)     NOT NULL,
    -- Literal EXACT vs bounded CONTAINS comparison (no regex / glob).
    match_mode                  VARCHAR(16)     NOT NULL,
    -- Display-name pattern; set iff match_type ∈ {NAME, NAME_AND_PUBLISHER}.
    name_pattern                VARCHAR(256),
    -- Publisher pattern; set iff match_type ∈ {PUBLISHER, NAME_AND_PUBLISHER}.
    publisher_pattern           VARCHAR(256),
    enabled                     BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Server-side operator note; never surfaced in evidence / read API.
    notes                       VARCHAR(512),
    created_by_subject          VARCHAR(255)    NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL,
    last_updated_by_subject     VARCHAR(255)    NOT NULL,
    last_updated_at             TIMESTAMPTZ     NOT NULL,
    version                     BIGINT          NOT NULL,

    CONSTRAINT pk_endpoint_prohibited_software_rules PRIMARY KEY (id),

    -- match_mode must be one of the two literal modes.
    CONSTRAINT ck_endpoint_prohibited_software_rules_match_mode
        CHECK (match_mode IN ('EXACT', 'CONTAINS')),

    -- match_type ↔ pattern consistency (Codex 019e7623 (c) absorb — "at
    -- least one" is too loose). Each branch pins BOTH the allowed match_type
    -- literal AND exactly which pattern columns are NON-NULL:
    --   NAME               → name_pattern set,  publisher_pattern NULL
    --   PUBLISHER          → publisher set,     name_pattern NULL
    --   NAME_AND_PUBLISHER → both set
    -- A blank ('' / whitespace-only) pattern is separately rejected below,
    -- so "set" here means a genuine non-blank value.
    CONSTRAINT ck_endpoint_prohibited_software_rules_match_consistency
        CHECK (
            (match_type = 'NAME'
                 AND name_pattern IS NOT NULL
                 AND publisher_pattern IS NULL)
            OR (match_type = 'PUBLISHER'
                 AND publisher_pattern IS NOT NULL
                 AND name_pattern IS NULL)
            OR (match_type = 'NAME_AND_PUBLISHER'
                 AND name_pattern IS NOT NULL
                 AND publisher_pattern IS NOT NULL)
        ),

    -- A present name_pattern must not be blank / whitespace-only. The DB
    -- normalization (regexp_replace over [\x00-\x20]) mirrors the
    -- service-layer Java String.trim() EXACTLY — including tab / newline / CR
    -- and other control chars <= U+0020 — so the two never disagree.
    CONSTRAINT ck_endpoint_prohibited_software_rules_name_pattern_blank
        CHECK (name_pattern IS NULL
            OR regexp_replace(name_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g') <> ''),

    -- Same for publisher_pattern.
    CONSTRAINT ck_endpoint_prohibited_software_rules_pub_pattern_blank
        CHECK (publisher_pattern IS NULL
            OR regexp_replace(publisher_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g') <> ''),

    -- CONTAINS minimum normalized length (Codex 019e7623 (c) absorb): a 1-
    -- or 2-char substring is a tenant-wide false-positive bomb. Each present
    -- pattern's trimmed length must be >= 3 when the mode is CONTAINS. EXACT
    -- patterns are exempt (an exact short name is legitimate). The trim here
    -- is the same [\x00-\x20] range as Java String.trim() so "ab\t" (trimmed
    -- length 2) is rejected identically by the validator and the DB.
    CONSTRAINT ck_endpoint_prohibited_software_rules_contains_minlen
        CHECK (
            match_mode <> 'CONTAINS'
            OR (
                (name_pattern IS NULL
                    OR length(regexp_replace(name_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g')) >= 3)
                AND (publisher_pattern IS NULL
                    OR length(regexp_replace(publisher_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g')) >= 3)
            )
        )
);

-- Hot path for the evaluator: enabled rules per tenant. A partial index on
-- enabled=TRUE keeps the evaluator scan tenant-narrow and skips disabled
-- rules entirely.
CREATE INDEX idx_endpoint_prohibited_software_rules_tenant_enabled
    ON endpoint_prohibited_software_rules (tenant_id, enabled);

-- Duplicate-rule guard (Codex 019e7623 (c) absorb). Without this the same
-- (tenant, match_type, match_mode, patterns) rule could be inserted twice
-- and double-count a finding / bloat the evidence. A functional UNIQUE
-- index over the CASE-NORMALIZED patterns enforces one logical rule per
-- tenant. COALESCE(..,'') folds the NULL pattern column to '' so the unique
-- key is well-defined for NAME-only / PUBLISHER-only rules (a bare UNIQUE
-- would treat NULLs as distinct and miss the duplicate). The service also
-- pre-checks for a clean 409, but this is the structural backstop.
--
-- The fold here is lower(<trim>) where <trim> is the SAME [\x00-\x20] range
-- normalization as the service (ProhibitedSoftwareRuleValidator#normalize =
-- lower(Java String.trim(...))), so the index and the application agree on
-- which two rules are "the same" — including across tab/newline-padded
-- variants, not just space-padded ones (the old bare btrim() only folded
-- spaces).
CREATE UNIQUE INDEX uq_endpoint_prohibited_software_rules_tenant_dedup
    ON endpoint_prohibited_software_rules (
        tenant_id,
        match_type,
        match_mode,
        COALESCE(lower(regexp_replace(name_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g')), ''),
        COALESCE(lower(regexp_replace(publisher_pattern, '^[\x00-\x20]+|[\x00-\x20]+$', '', 'g')), '')
    );
