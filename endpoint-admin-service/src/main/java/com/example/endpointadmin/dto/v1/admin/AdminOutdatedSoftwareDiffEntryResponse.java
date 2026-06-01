package com.example.endpointadmin.dto.v1.admin;

/**
 * BE-024b — one package-level change between the latest two outdated-
 * software captures for a device (Faz 22.5 P2-A slice-3).
 *
 * <p>Identity is the canonical winget packageId. The wire fields are a
 * strict subset of AG-036 outdated-package facet — {@code packageId},
 * {@code installedVersion}, {@code availableVersion} — no displayName /
 * publisher / install log / uninstall string. The synthetic
 * {@code appKey} identity used by BE-024 deliberately does NOT appear
 * here: outdated-software diff and software-inventory diff are
 * different truth axes (Codex 019e8542 absorb).
 *
 * <p>Change-type precedence:
 * <ul>
 *   <li>{@code ADDED}      — packageId in the latest, absent in previous.</li>
 *   <li>{@code REMOVED}    — packageId in the previous, absent in latest.</li>
 *   <li>{@code VERSION_CHANGED} — same packageId, installedVersion delta.
 *       The entry carries both installed AND available deltas on the
 *       wire, but is NOT also duplicated into availableVersionBumped.</li>
 *   <li>{@code AVAILABLE_VERSION_BUMPED} — same packageId, installed
 *       unchanged, availableVersion delta.</li>
 * </ul>
 */
public record AdminOutdatedSoftwareDiffEntryResponse(
        String packageId,
        String fromInstalledVersion,
        String toInstalledVersion,
        String fromAvailableVersion,
        String toAvailableVersion,
        ChangeType changeType
) {

    public enum ChangeType {
        ADDED,
        REMOVED,
        VERSION_CHANGED,
        AVAILABLE_VERSION_BUMPED
    }

    public static AdminOutdatedSoftwareDiffEntryResponse added(
            String packageId, String toInstalledVersion, String toAvailableVersion) {
        return new AdminOutdatedSoftwareDiffEntryResponse(
                packageId, null, toInstalledVersion, null, toAvailableVersion,
                ChangeType.ADDED);
    }

    public static AdminOutdatedSoftwareDiffEntryResponse removed(
            String packageId, String fromInstalledVersion, String fromAvailableVersion) {
        return new AdminOutdatedSoftwareDiffEntryResponse(
                packageId, fromInstalledVersion, null, fromAvailableVersion, null,
                ChangeType.REMOVED);
    }

    public static AdminOutdatedSoftwareDiffEntryResponse versionChanged(
            String packageId,
            String fromInstalledVersion, String toInstalledVersion,
            String fromAvailableVersion, String toAvailableVersion) {
        return new AdminOutdatedSoftwareDiffEntryResponse(
                packageId, fromInstalledVersion, toInstalledVersion,
                fromAvailableVersion, toAvailableVersion,
                ChangeType.VERSION_CHANGED);
    }

    public static AdminOutdatedSoftwareDiffEntryResponse availableVersionBumped(
            String packageId, String installedVersion,
            String fromAvailableVersion, String toAvailableVersion) {
        return new AdminOutdatedSoftwareDiffEntryResponse(
                packageId, installedVersion, installedVersion,
                fromAvailableVersion, toAvailableVersion,
                ChangeType.AVAILABLE_VERSION_BUMPED);
    }
}
