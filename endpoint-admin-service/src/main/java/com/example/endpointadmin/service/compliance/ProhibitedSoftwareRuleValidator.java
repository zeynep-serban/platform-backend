package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * BE-025 — service-layer validator + normalizer for prohibited-software
 * denylist rules (Faz 22.5).
 *
 * <p>This is the single source of truth for two things, so the runtime
 * matcher, the duplicate pre-check, and the DB CHECK constraints all agree:
 *
 * <ol>
 *   <li><b>Cross-field consistency</b> — {@code matchType ↔ pattern}
 *       presence, blank rejection, and the CONTAINS minimum length. These
 *       mirror the V19 CHECK constraints exactly so the operator gets a
 *       clean 400 instead of a 500 / DB violation. (The DB CHECKs remain the
 *       structural backstop.)</li>
 *   <li><b>Normalization</b> — {@link #normalize(String)} =
 *       {@code lower(trim(...))} with {@code Locale.ROOT}. The matcher and
 *       the dedup key both go through this, matching the DB's
 *       {@code lower(btrim(...))} folding in the unique index.</li>
 * </ol>
 *
 * <p>There is intentionally no regex acceptance anywhere — matching is
 * literal EXACT / bounded CONTAINS only (ReDoS / injection surface).
 */
@Component
public class ProhibitedSoftwareRuleValidator {

    /**
     * Minimum normalized pattern length for {@link
     * ProhibitedSoftwareMatchMode#CONTAINS}. A 1- or 2-character substring
     * would match almost every installed app (a tenant-wide false-positive
     * bomb). Mirrors the {@code ck_..._contains_minlen} DB CHECK.
     */
    public static final int MIN_CONTAINS_LENGTH = 3;

    /**
     * Validate the request's cross-field rules. Throws
     * {@link IllegalArgumentException} (→ 400) on any violation. Does NOT
     * mutate the request; callers normalize on persist via
     * {@link #normalize(String)} / use {@link #trimToNull(String)} for the
     * stored value.
     */
    public void validate(ProhibitedSoftwareRuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Prohibited-software rule is required.");
        }
        ProhibitedSoftwareMatchType matchType = request.matchType();
        ProhibitedSoftwareMatchMode matchMode = request.matchMode();
        if (matchType == null) {
            throw new IllegalArgumentException("matchType is required.");
        }
        if (matchMode == null) {
            throw new IllegalArgumentException("matchMode is required.");
        }

        String name = trimToNull(request.namePattern());
        String publisher = trimToNull(request.publisherPattern());

        // matchType ↔ pattern presence (mirrors ck_..._match_consistency).
        switch (matchType) {
            case NAME -> {
                requirePresent(name, "namePattern", matchType);
                requireAbsent(publisher, "publisherPattern", matchType);
            }
            case PUBLISHER -> {
                requirePresent(publisher, "publisherPattern", matchType);
                requireAbsent(name, "namePattern", matchType);
            }
            case NAME_AND_PUBLISHER -> {
                requirePresent(name, "namePattern", matchType);
                requirePresent(publisher, "publisherPattern", matchType);
            }
        }

        // CONTAINS minimum length on whichever pattern(s) are present
        // (mirrors ck_..._contains_minlen).
        if (matchMode == ProhibitedSoftwareMatchMode.CONTAINS) {
            requireMinContainsLength(name, "namePattern");
            requireMinContainsLength(publisher, "publisherPattern");
        }
    }

    private static void requirePresent(
            String value, String field, ProhibitedSoftwareMatchType matchType) {
        if (value == null) {
            throw new IllegalArgumentException(
                    field + " is required and must be non-blank when matchType="
                            + matchType + ".");
        }
    }

    private static void requireAbsent(
            String value, String field, ProhibitedSoftwareMatchType matchType) {
        if (value != null) {
            throw new IllegalArgumentException(
                    field + " must be absent when matchType=" + matchType + ".");
        }
    }

    private static void requireMinContainsLength(String value, String field) {
        if (value != null && value.length() < MIN_CONTAINS_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must be at least " + MIN_CONTAINS_LENGTH
                            + " characters when matchMode=CONTAINS (a shorter "
                            + "substring would match too broadly).");
        }
    }

    /**
     * Trim and collapse blank to {@code null}. The stored pattern keeps its
     * original case (only normalized at match / dedup time) but is always
     * trimmed so the DB blank CHECK and the normalized dedup key agree.
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Canonical normalization shared by the matcher and the dedup key:
     * {@code lower(trim(...))} with {@code Locale.ROOT}. Returns the empty
     * string for a null/blank input so it folds to the same key the DB's
     * {@code COALESCE(lower(btrim(...)), '')} index expression produces.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
