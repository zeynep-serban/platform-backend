package com.example.endpointadmin.dto.v1.admin;

import java.util.UUID;

/**
 * BE-025 — One prohibited-software finding on the device-facing read
 * surface (Faz 22.5).
 *
 * <p>Redaction boundary: ONLY the rule id + the matched inventory item's
 * {@code displayName} / {@code publisher} / {@code version} appear on the
 * wire — the same field set as the software inventory itself. The rule's
 * {@code notes} / {@code createdBySubject} and any raw path / registry key
 * are deliberately NOT included.
 *
 * @param ruleId            the denylist rule that matched
 * @param matchType         which field(s) the rule matched on (NAME /
 *                          PUBLISHER / NAME_AND_PUBLISHER) — string form so
 *                          the wire contract is stable across enum renames
 * @param matchMode         EXACT / CONTAINS — string form, as above
 * @param matchedName       the installed item's display name (redacted set)
 * @param matchedPublisher  the installed item's publisher (may be null)
 * @param matchedVersion    the installed item's version (may be null)
 */
public record ProhibitedSoftwareFindingResponse(
        UUID ruleId,
        String matchType,
        String matchMode,
        String matchedName,
        String matchedPublisher,
        String matchedVersion) {
}
