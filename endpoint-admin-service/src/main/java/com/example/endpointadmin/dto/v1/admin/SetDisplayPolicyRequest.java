package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.DisplayPolicyOperation;

/**
 * #508 Endpoint Display Policy desired-state request (dedicated path only).
 *
 * <p>A full desired-state snapshot, not a patch. {@code operation=ENFORCE}
 * carries the screensaver + wallpaper sub-objects; {@code operation=CLEAR}
 * carries neither (the backend-managed keys are removed). Validation is
 * fail-closed in {@code DisplayPolicyValidator}; the generated command is always
 * maker-checker and a non-blank {@code reason} is required.
 *
 * <p>Slice-1/2a contract: the wallpaper IMAGE binary is NOT uploaded here —
 * {@code assetRef}/{@code assetSha256}/{@code contentType} are metadata only;
 * the object-store upload + claim-time download credential land in a later
 * slice.
 */
public record SetDisplayPolicyRequest(
        DisplayPolicyOperation operation,
        String reason,
        Screensaver screensaver,
        Wallpaper wallpaper) {

    /** Screensaver desired-state (HKLM Group-Policy Control Panel\Desktop). */
    public record Screensaver(
            Boolean enabled,
            Integer timeoutSeconds,
            Boolean secureOnResume,
            String scrPath) {
    }

    /** Wallpaper desired-state (HKLM ...\Policies\System). */
    public record Wallpaper(
            Boolean enabled,
            String style,
            Boolean userCannotChange,
            String assetRef,
            String assetSha256,
            String contentType) {
    }
}
