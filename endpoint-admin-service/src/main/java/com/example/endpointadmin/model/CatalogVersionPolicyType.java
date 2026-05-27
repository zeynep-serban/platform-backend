package com.example.endpointadmin.model;

/**
 * Version policy mode for an {@link EndpointSoftwareCatalogItem}
 * (BE-020, Faz 22.5.3).
 *
 * <p>Semver-agnostic on purpose: WinGet and MSI version strings are not
 * guaranteed to be semver-compliant. BE-020 only validates that
 * {@code version_policy_value} is present when the policy requires one and is
 * a reasonable string; the actual compare / matching evaluator is BE-023
 * (software compliance evaluator).
 *
 * <p>Value semantics (enforced by the validator, not the enum):
 * <ul>
 *   <li>{@link #LATEST} — {@code value} is {@code null} or omitted; provider's
 *       latest version is acceptable.</li>
 *   <li>{@link #EXACT} — {@code value} is a single version string
 *       (e.g. {@code "19.00"}). String comparison only at BE-020; semver-aware
 *       comparison is BE-023's job. Pin invariant: {@code EXACT} combined with
 *       a {@code sha256} + {@code provenance} on the catalog item provides the
 *       supply-chain "pinned" guarantee — no separate {@code PINNED} enum
 *       value is needed.</li>
 *   <li>{@link #MINIMUM} — {@code value} is a lower-bound version string;
 *       BE-023 evaluates the comparison.</li>
 *   <li>{@link #RANGE} — {@code value} is a custom range expression
 *       (e.g. {@code ">=19.00,<20.00"}); BE-023 parses + evaluates.</li>
 * </ul>
 */
public enum CatalogVersionPolicyType {
    LATEST,
    EXACT,
    MINIMUM,
    RANGE
}
