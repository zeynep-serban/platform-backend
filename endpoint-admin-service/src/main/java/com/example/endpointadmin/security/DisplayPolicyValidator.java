package com.example.endpointadmin.security;

import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.WallpaperStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed validator for #508 Endpoint Display Policy desired-state requests
 * (Codex 019ea8be: ".scrPath validator must land before any dispatcher persists
 * policy rows"). All checks throw {@link IllegalArgumentException}; the service
 * layer maps that to 4xx. This is slice-2a (pure validation, no DB) — the
 * dispatch service / REST / persistence build on it in slice-2b.
 *
 * <p><b>Screensaver .scr</b> is the highest-risk field: a {@code .scr} is an
 * executable class, so an arbitrary path would turn a cosmetic policy into an
 * endpoint execution/persistence surface. We therefore restrict it to an
 * <b>exact allowlist of built-in System32 screensavers</b> (Codex's preferred
 * "explicit built-in enum" option) — zero path-injection surface. UNC / URL /
 * relative / env-expansion / traversal / forward-slash / ADS forms are all
 * implicitly rejected because they cannot equal an allowlisted path; explicit
 * pre-checks are kept for clearer operator errors.
 */
public final class DisplayPolicyValidator {

    /** Built-in Windows 10/11 System32 screensavers (canonical, lowercased). */
    private static final Set<String> ALLOWED_SCR_LOWER = Set.of(
            "c:\\windows\\system32\\scrnsave.scr",        // blank / secure-lock
            "c:\\windows\\system32\\mystify.scr",
            "c:\\windows\\system32\\ribbons.scr",
            "c:\\windows\\system32\\bubbles.scr",
            "c:\\windows\\system32\\photoscreensaver.scr",
            "c:\\windows\\system32\\sstext3d.scr");

    private static final int MIN_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 86_400;

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** Allowed wallpaper image content types (slice-1/2a metadata only). */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/bmp");

    private static final int MAX_ASSET_REF_LEN = 512;

    private static final int MAX_REASON_LEN = 512;

    private DisplayPolicyValidator() {
    }

    /** Validate a full desired-state request fail-closed. */
    public static void validate(SetDisplayPolicyRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Display policy request is required.");
        }
        if (req.operation() == null) {
            throw new IllegalArgumentException(
                    "operation is required (ENFORCE or CLEAR).");
        }
        if (isBlank(req.reason())) {
            throw new IllegalArgumentException("reason is required.");
        }
        if (req.reason().length() > MAX_REASON_LEN) {
            throw new IllegalArgumentException(
                    "reason must be at most " + MAX_REASON_LEN + " chars.");
        }

        if (req.operation() == DisplayPolicyOperation.CLEAR) {
            // CLEAR removes managed keys; it must not carry a desired-state.
            if (req.screensaver() != null || req.wallpaper() != null) {
                throw new IllegalArgumentException(
                        "CLEAR must not carry a screensaver or wallpaper snapshot.");
            }
            return;
        }

        // ENFORCE: at least one managed surface, and each present one valid.
        boolean any = false;
        if (req.screensaver() != null) {
            validateScreensaver(req.screensaver());
            any = hasMeaningfulScreensaver(req.screensaver());
        }
        if (req.wallpaper() != null) {
            validateWallpaper(req.wallpaper());
            any = any || hasMeaningfulWallpaper(req.wallpaper());
        }
        if (!any) {
            throw new IllegalArgumentException(
                    "ENFORCE must carry at least one screensaver or wallpaper value.");
        }
    }

    private static void validateScreensaver(SetDisplayPolicyRequest.Screensaver s) {
        if (Boolean.TRUE.equals(s.enabled())) {
            if (s.timeoutSeconds() == null) {
                throw new IllegalArgumentException(
                        "screensaver.timeoutSeconds is required when the screensaver is enabled.");
            }
            validateTimeout(s.timeoutSeconds());
            validateScrPath(s.scrPath());
        } else {
            // disabled / unset screensaver: a timeout, if present, must still be sane;
            // an scrPath, if present, must still be allowlisted (fail closed).
            if (s.timeoutSeconds() != null) {
                validateTimeout(s.timeoutSeconds());
            }
            if (s.scrPath() != null) {
                validateScrPath(s.scrPath());
            }
        }
    }

    private static void validateTimeout(int timeoutSeconds) {
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "screensaver.timeoutSeconds must be between " + MIN_TIMEOUT_SECONDS
                            + " and " + MAX_TIMEOUT_SECONDS + ".");
        }
    }

    /** Fail-closed: the .scr must be an exact allowlisted built-in System32 path. */
    public static void validateScrPath(String scrPath) {
        if (isBlank(scrPath)) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath is required when the screensaver is enabled.");
        }
        // exact-allowlist contract: no leading/trailing whitespace so the
        // validated value IS the canonical allowlisted path (slice-2b persists
        // it verbatim).
        if (!scrPath.equals(scrPath.strip())) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must not have leading or trailing whitespace.");
        }
        String trimmed = scrPath.trim();
        if (trimmed.startsWith("\\\\")) {
            throw new IllegalArgumentException("screensaver.scrPath must not be a UNC path.");
        }
        if (trimmed.contains("/")) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must not contain forward slashes.");
        }
        if (trimmed.contains("%")) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must not contain environment-variable expansion.");
        }
        if (trimmed.contains("..") || hasDotOrTildeSegment(trimmed)) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must not contain relative or short-name segments.");
        }
        if (trimmed.length() > 2 && trimmed.substring(2).indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must not contain Alternate Data Stream syntax.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCR_LOWER.contains(lower)) {
            throw new IllegalArgumentException(
                    "screensaver.scrPath must be a built-in System32 screensaver; "
                            + "allowed: " + sortedAllowed() + ".");
        }
    }

    private static void validateWallpaper(SetDisplayPolicyRequest.Wallpaper w) {
        if (Boolean.TRUE.equals(w.enabled())) {
            validateStyle(w.style());
            if (isBlank(w.assetRef())) {
                throw new IllegalArgumentException(
                        "wallpaper.assetRef is required when the wallpaper is enabled.");
            }
        } else if (w.style() != null) {
            validateStyle(w.style());
        }
        if (w.assetRef() != null) {
            // present assetRef must be non-blank (a blank value would otherwise
            // count as a "meaningful" ENFORCE field yet carry nothing).
            if (isBlank(w.assetRef())) {
                throw new IllegalArgumentException("wallpaper.assetRef must not be blank.");
            }
            if (w.assetRef().length() > MAX_ASSET_REF_LEN) {
                throw new IllegalArgumentException(
                        "wallpaper.assetRef must be at most " + MAX_ASSET_REF_LEN + " chars.");
            }
        }
        if (w.assetSha256() != null && !SHA256.matcher(w.assetSha256()).matches()) {
            throw new IllegalArgumentException(
                    "wallpaper.assetSha256 must be 64-char lowercase hex.");
        }
        if (w.contentType() != null
                && !ALLOWED_CONTENT_TYPES.contains(w.contentType().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "wallpaper.contentType must be one of " + ALLOWED_CONTENT_TYPES + ".");
        }
    }

    /** Fail-closed: the style must be a known {@link WallpaperStyle}. */
    public static void validateStyle(String style) {
        parseStyle(style);
    }

    /**
     * Fail-closed canonical parse: returns the {@link WallpaperStyle} for a
     * case-insensitive name, else throws. slice-2b persists {@code .name()} so
     * the stored value matches the V58 uppercase style CHECK domain.
     */
    public static WallpaperStyle parseStyle(String style) {
        if (isBlank(style)) {
            throw new IllegalArgumentException("wallpaper.style is required.");
        }
        try {
            return WallpaperStyle.valueOf(style.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "wallpaper.style must be one of CENTER, STRETCH, FIT, FILL, SPAN.");
        }
    }

    private static boolean hasMeaningfulScreensaver(SetDisplayPolicyRequest.Screensaver s) {
        return s.enabled() != null || s.timeoutSeconds() != null
                || s.secureOnResume() != null || s.scrPath() != null;
    }

    private static boolean hasMeaningfulWallpaper(SetDisplayPolicyRequest.Wallpaper w) {
        return w.enabled() != null || w.style() != null || w.userCannotChange() != null
                || w.assetRef() != null || w.assetSha256() != null || w.contentType() != null;
    }

    private static boolean hasDotOrTildeSegment(String path) {
        for (String segment : path.split("\\\\")) {
            if (".".equals(segment) || segment.contains("~")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sortedAllowed() {
        return ALLOWED_SCR_LOWER.stream().sorted().toList();
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
