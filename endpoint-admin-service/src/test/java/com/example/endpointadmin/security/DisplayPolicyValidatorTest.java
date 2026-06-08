package com.example.endpointadmin.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest.Screensaver;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest.Wallpaper;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import org.junit.jupiter.api.Test;

/**
 * #508 slice-2a — fail-closed DisplayPolicyValidator unit tests (no DB).
 */
class DisplayPolicyValidatorTest {

    private static final String SCR = "C:\\Windows\\System32\\scrnsave.scr";

    private static SetDisplayPolicyRequest enforce(Screensaver s, Wallpaper w) {
        return new SetDisplayPolicyRequest(DisplayPolicyOperation.ENFORCE, "kiosk lockdown", s, w);
    }

    // ---- happy paths ----

    @Test
    void validEnforceWithScreensaverAndWallpaperPasses() {
        Screensaver s = new Screensaver(true, 600, true, SCR);
        Wallpaper w = new Wallpaper(true, "FILL", true, "wallpaper-assets/k.png",
                "a".repeat(64), "image/png");
        assertThatCode(() -> DisplayPolicyValidator.validate(enforce(s, w)))
                .doesNotThrowAnyException();
    }

    @Test
    void clearWithoutSnapshotPasses() {
        var req = new SetDisplayPolicyRequest(DisplayPolicyOperation.CLEAR, "unmanage", null, null);
        assertThatCode(() -> DisplayPolicyValidator.validate(req)).doesNotThrowAnyException();
    }

    @Test
    void scrPathAllowlistIsCaseInsensitive() {
        assertThatCode(() -> DisplayPolicyValidator.validateScrPath("c:\\windows\\system32\\Mystify.scr"))
                .doesNotThrowAnyException();
    }

    @Test
    void timeoutBoundariesPass() {
        assertThatCode(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, 60, false, SCR), null))).doesNotThrowAnyException();
        assertThatCode(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, 86_400, false, SCR), null))).doesNotThrowAnyException();
    }

    // ---- operation / reason / snapshot consistency ----

    @Test
    void nullOperationRejected() {
        var req = new SetDisplayPolicyRequest(null, "r", null, null);
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operation");
    }

    @Test
    void blankReasonRejected() {
        var req = new SetDisplayPolicyRequest(DisplayPolicyOperation.CLEAR, "  ", null, null);
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
    }

    @Test
    void clearWithSnapshotRejected() {
        var req = new SetDisplayPolicyRequest(DisplayPolicyOperation.CLEAR, "r",
                new Screensaver(true, 600, true, SCR), null);
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CLEAR");
    }

    @Test
    void emptyEnforceRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(enforce(null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }

    // ---- screensaver ----

    @Test
    void enabledScreensaverRequiresTimeout() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, null, true, SCR), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeoutSeconds");
    }

    @Test
    void enabledScreensaverRequiresScrPath() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, 600, true, null), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scrPath");
    }

    @Test
    void timeoutOutOfRangeRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, 59, true, SCR), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(new Screensaver(true, 86_401, true, SCR), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scrPathUncRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath("\\\\evil\\share\\x.scr"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UNC");
    }

    @Test
    void scrPathForwardSlashRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath("c:/windows/system32/scrnsave.scr"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forward slash");
    }

    @Test
    void scrPathEnvExpansionRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath("%SystemRoot%\\System32\\scrnsave.scr"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("environment-variable");
    }

    @Test
    void scrPathTraversalRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath(
                "c:\\windows\\system32\\..\\..\\users\\evil\\x.scr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scrPathAlternateDataStreamRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath(
                "c:\\windows\\system32\\scrnsave.scr:evil"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Alternate Data Stream");
    }

    @Test
    void scrPathArbitraryButWellFormedRejected() {
        // a perfectly Windows-shaped path that is simply NOT a built-in screensaver
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath(
                "c:\\program files\\evil\\payload.scr"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("built-in");
    }

    // ---- wallpaper ----

    @Test
    void enabledWallpaperRequiresStyleAndAssetRef() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(null, new Wallpaper(true, "FILL", true, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assetRef");
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(null, new Wallpaper(true, null, true, "a/b.png", null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("style");
    }

    @Test
    void invalidStyleRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateStyle("TILE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CENTER");
    }

    @Test
    void styleCaseInsensitive() {
        assertThatCode(() -> DisplayPolicyValidator.validateStyle("fill")).doesNotThrowAnyException();
    }

    @Test
    void badAssetSha256Rejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(null, new Wallpaper(true, "FILL", true, "a/b.png", "NOTHEX", null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Sha256");
    }

    @Test
    void disallowedContentTypeRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(null, new Wallpaper(true, "FILL", true, "a/b.svg",
                        "a".repeat(64), "image/svg+xml"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contentType");
    }

    // ---- Codex 019ea8be post-impl REVISE: canonical-form / DB-contract guards ----

    @Test
    void scrPathLeadingTrailingWhitespaceRejected() {
        assertThatThrownBy(() -> DisplayPolicyValidator.validateScrPath(" " + SCR + " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
    }

    @Test
    void parseStyleReturnsCanonicalEnum() {
        org.assertj.core.api.Assertions.assertThat(DisplayPolicyValidator.parseStyle("fill"))
                .isEqualTo(com.example.endpointadmin.model.WallpaperStyle.FILL);
    }

    @Test
    void blankAssetRefPresentRejected() {
        // wallpaper not enabled, but a blank assetRef is still present → reject
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(
                enforce(null, new Wallpaper(false, null, null, "   ", null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assetRef");
    }

    @Test
    void reasonTooLongRejected() {
        var req = new SetDisplayPolicyRequest(DisplayPolicyOperation.CLEAR, "x".repeat(513), null, null);
        assertThatThrownBy(() -> DisplayPolicyValidator.validate(req))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("512");
    }
}
