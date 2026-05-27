package com.example.endpointadmin.security;

import java.util.List;
import java.util.Locale;

/**
 * Safety validator for Windows absolute paths consumed by BE-020 detection
 * rules ({@code FILE_EXISTS} / {@code FILE_SHA256}).
 *
 * <p>The agent eventually evaluates these paths on Windows endpoints, so the
 * backend cannot rely on JVM {@code java.nio.file.Path} semantics (Linux
 * containers will treat back-slash paths as opaque strings). All checks are
 * done on the raw string + a normalized lowercase form.
 *
 * <p>Reject classes (Codex 019e6a3e iter-2 acceptance #5):
 * <ul>
 *   <li>UNC paths ({@code \\server\share\...})</li>
 *   <li>Environment-variable expansion ({@code %ProgramFiles%\...})</li>
 *   <li>Parent traversal segments ({@code ..})</li>
 *   <li>8.3 short-name alias indicators (segment with {@code ~})</li>
 *   <li>Forward-slash path separators (Windows path normalization bypass)</li>
 *   <li>Anything outside the allowlist of normalized drive prefixes</li>
 * </ul>
 *
 * <p>Allowlist (case-insensitive, after normalization):
 * {@code c:\program files\}, {@code c:\program files (x86)\}.
 *
 * <p>This is intentionally conservative for BE-020 MVP. Adding new allowlist
 * prefixes (e.g. an internal artifact root) is a deliberate widening that
 * requires a follow-up review and an updated runbook.
 */
public final class WindowsPathSafetyValidator {

    private static final List<String> ALLOWED_PREFIXES_LOWER = List.of(
            "c:\\program files\\",
            "c:\\program files (x86)\\"
    );

    private WindowsPathSafetyValidator() {
    }

    public static void validate(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException(
                    "Detection rule path is required.");
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Detection rule path must not be blank.");
        }
        if (trimmed.startsWith("\\\\")) {
            throw new IllegalArgumentException(
                    "Detection rule path must not be a UNC path.");
        }
        if (trimmed.contains("%")) {
            throw new IllegalArgumentException(
                    "Detection rule path must not contain environment-variable "
                            + "expansion (%...%).");
        }
        if (trimmed.contains("/")) {
            throw new IllegalArgumentException(
                    "Detection rule path must not contain forward slashes; "
                            + "use Windows-style back-slashes.");
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException(
                    "Detection rule path must not contain parent traversal "
                            + "segments ('..').");
        }
        if (containsShortNameAlias(trimmed)) {
            throw new IllegalArgumentException(
                    "Detection rule path must not contain 8.3 short-name "
                            + "alias segments ('~').");
        }
        String normalizedLower = trimmed.toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_PREFIXES_LOWER.stream()
                .anyMatch(normalizedLower::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Detection rule path must start with an allowlisted "
                            + "Windows prefix (e.g. 'C:\\Program Files\\' or "
                            + "'C:\\Program Files (x86)\\').");
        }
    }

    private static boolean containsShortNameAlias(String value) {
        for (String segment : value.split("\\\\")) {
            if (segment.contains("~")) {
                return true;
            }
        }
        return false;
    }
}
