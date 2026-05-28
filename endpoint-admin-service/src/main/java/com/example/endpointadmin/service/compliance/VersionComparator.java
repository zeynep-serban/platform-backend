package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.CatalogVersionPolicyType;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Catalog version policy comparator — BE-023 (Faz 22.5).
 *
 * <p>Backed by {@link ComparableVersion} (Apache Maven artifact
 * versioning) because Windows / WinGet version strings are not
 * reliably semver (e.g. {@code 24.07}, {@code 1.0.0.123-beta+build},
 * {@code 19H1}). A hand-rolled parser would only cover a subset; the
 * Maven comparator is the established reference for "best-effort
 * comparable" version strings. Codex 019e6bbf iter-2 absorb.
 *
 * <p>Failure paths fall through to {@link Result#UNSUPPORTED}, which
 * drives the {@code VERSION_COMPARE_UNSUPPORTED} reason →
 * {@code UNKNOWN} decision (fail-closed).
 */
public final class VersionComparator {

    public enum Result {
        SATISFIES,
        VIOLATES,
        UNSUPPORTED
    }

    private VersionComparator() {
    }

    /**
     * Compare an observed installed version against a catalog policy.
     *
     * @param policyType      EXACT / MINIMUM / RANGE / LATEST.
     *                        {@code LATEST} returns
     *                        {@link Result#SATISFIES} (no version
     *                        constraint to check).
     * @param policySpec      free-form policy value as authored in the
     *                        catalog row. For EXACT/MINIMUM this is the
     *                        target version; for RANGE the canonical
     *                        form is {@code "[1.0,2.0)"} or similar
     *                        bracket-bound expression.
     * @param installedRaw    the version string observed in the
     *                        inventory item (may be null/blank/
     *                        unparseable).
     */
    public static Result compare(CatalogVersionPolicyType policyType, String policySpec, String installedRaw) {
        if (policyType == null) {
            return Result.UNSUPPORTED;
        }
        if (policyType == CatalogVersionPolicyType.LATEST) {
            return Result.SATISFIES;
        }
        if (installedRaw == null || installedRaw.isBlank()) {
            return Result.UNSUPPORTED;
        }
        if (policySpec == null || policySpec.isBlank()) {
            return Result.UNSUPPORTED;
        }
        try {
            ComparableVersion installed = new ComparableVersion(installedRaw.trim());
            switch (policyType) {
                case EXACT:
                    ComparableVersion exact = new ComparableVersion(policySpec.trim());
                    return installed.compareTo(exact) == 0 ? Result.SATISFIES : Result.VIOLATES;
                case MINIMUM:
                    ComparableVersion minimum = new ComparableVersion(policySpec.trim());
                    return installed.compareTo(minimum) >= 0 ? Result.SATISFIES : Result.VIOLATES;
                case RANGE:
                    return evaluateRange(installed, policySpec.trim());
                default:
                    return Result.UNSUPPORTED;
            }
        } catch (Exception ex) {
            return Result.UNSUPPORTED;
        }
    }

    /**
     * Evaluate a bracketed range expression. Supported forms:
     *
     * <ul>
     *   <li>{@code [a,b]} — inclusive both</li>
     *   <li>{@code [a,b)} — inclusive low, exclusive high</li>
     *   <li>{@code (a,b]} — exclusive low, inclusive high</li>
     *   <li>{@code (a,b)} — exclusive both</li>
     *   <li>{@code [a,)} or {@code (a,)} — unbounded upper</li>
     *   <li>{@code (,b]} or {@code (,b)} — unbounded lower</li>
     * </ul>
     *
     * Anything else returns {@link Result#UNSUPPORTED}.
     */
    private static Result evaluateRange(ComparableVersion installed, String spec) {
        if (spec.length() < 3) {
            return Result.UNSUPPORTED;
        }
        char openBracket = spec.charAt(0);
        char closeBracket = spec.charAt(spec.length() - 1);
        if ((openBracket != '[' && openBracket != '(')
                || (closeBracket != ']' && closeBracket != ')')) {
            return Result.UNSUPPORTED;
        }
        String inner = spec.substring(1, spec.length() - 1);
        int commaIdx = inner.indexOf(',');
        if (commaIdx < 0) {
            return Result.UNSUPPORTED;
        }
        String lowerStr = inner.substring(0, commaIdx).trim();
        String upperStr = inner.substring(commaIdx + 1).trim();

        boolean lowerInclusive = openBracket == '[';
        boolean upperInclusive = closeBracket == ']';

        if (!lowerStr.isEmpty()) {
            ComparableVersion lower;
            try {
                lower = new ComparableVersion(lowerStr);
            } catch (Exception ex) {
                return Result.UNSUPPORTED;
            }
            int cmp = installed.compareTo(lower);
            if (cmp < 0 || (cmp == 0 && !lowerInclusive)) {
                return Result.VIOLATES;
            }
        }
        if (!upperStr.isEmpty()) {
            ComparableVersion upper;
            try {
                upper = new ComparableVersion(upperStr);
            } catch (Exception ex) {
                return Result.UNSUPPORTED;
            }
            int cmp = installed.compareTo(upper);
            if (cmp > 0 || (cmp == 0 && !upperInclusive)) {
                return Result.VIOLATES;
            }
        }
        return Result.SATISFIES;
    }
}
