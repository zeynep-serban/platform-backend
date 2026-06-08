package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;

import java.time.Instant;
import java.util.UUID;

/**
 * #508 slice-2b — GET/PUT/DELETE response for the endpoint display-policy
 * surface.
 *
 * <p>Exposes BOTH the approved {@code current} desired-state (or {@code null}
 * if none has ever been approved) AND the {@code openProposal} (a PENDING/
 * APPROVED-but-undispatched maker-checker proposal) so an operator can see e.g.
 * "active ENFORCE + a pending CLEAR awaiting approval". {@code current} reflects
 * approved truth only — a pending proposal lives solely in {@code openProposal}
 * until a second admin approves it.
 */
public record AdminDisplayPolicyResponse(
        UUID deviceId,
        String operation,
        Screensaver screensaver,
        Wallpaper wallpaper,
        String policyHashSha256,
        Instant clearedAt,
        String clearedBySubject,
        String lastEnforcementStatus,
        Instant lastEnforcedAt,
        UUID currentRevisionId,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant lastUpdatedAt,
        OpenProposal openProposal) {

    public record Screensaver(
            Boolean enabled,
            Integer timeoutSeconds,
            Boolean secureOnResume,
            String scrPath) {
    }

    public record Wallpaper(
            Boolean enabled,
            String style,
            Boolean userCannotChange,
            String assetRef,
            String assetSha256,
            String contentType) {
    }

    /** A maker-checker proposal not yet enacted (PENDING or APPROVED-undispatched). */
    public record OpenProposal(
            UUID revisionId,
            UUID commandId,
            String operation,
            String policyHashSha256,
            String approvalStatus,
            String commandStatus,
            String createdBySubject,
            Instant createdAt) {
    }

    /**
     * Build a response. Any of {@code current} / {@code openRevision} may be
     * {@code null}; the caller guarantees at least one is present (else 404).
     */
    public static AdminDisplayPolicyResponse of(UUID deviceId,
                                                EndpointDisplayPolicy current,
                                                EndpointDisplayPolicyRevision openRevision,
                                                EndpointCommand openCommand) {
        OpenProposal proposal = null;
        if (openRevision != null) {
            proposal = new OpenProposal(
                    openRevision.getId(),
                    openRevision.getCommandId(),
                    openRevision.getOperation() == null ? null : openRevision.getOperation().name(),
                    openRevision.getPolicyHashSha256(),
                    openCommand == null || openCommand.getApprovalStatus() == null
                            ? null : openCommand.getApprovalStatus().name(),
                    openCommand == null || openCommand.getStatus() == null
                            ? null : openCommand.getStatus().name(),
                    openRevision.getCreatedBySubject(),
                    openRevision.getCreatedAt());
        }

        if (current == null) {
            return new AdminDisplayPolicyResponse(
                    deviceId, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, proposal);
        }

        Screensaver screensaver = new Screensaver(
                current.getScreensaverEnabled(),
                current.getScreensaverTimeoutSeconds(),
                current.getScreensaverSecure(),
                current.getScreensaverScrPath());
        Wallpaper wallpaper = new Wallpaper(
                current.getWallpaperEnabled(),
                current.getWallpaperStyle() == null ? null : current.getWallpaperStyle().name(),
                current.getWallpaperUserCannotChange(),
                current.getWallpaperAssetRef(),
                current.getWallpaperAssetSha256(),
                current.getWallpaperContentType());

        return new AdminDisplayPolicyResponse(
                deviceId,
                current.getOperation() == null ? null : current.getOperation().name(),
                screensaver,
                wallpaper,
                current.getPolicyHashSha256(),
                current.getClearedAt(),
                current.getClearedBySubject(),
                current.getLastEnforcementStatus(),
                current.getLastEnforcedAt(),
                current.getCurrentRevisionId(),
                current.getCreatedBySubject(),
                current.getCreatedAt(),
                current.getLastUpdatedBySubject(),
                current.getLastUpdatedAt(),
                proposal);
    }
}
