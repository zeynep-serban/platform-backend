package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-020 — Windows absolute-path safety guard for detection rule
 * {@code FILE_EXISTS} / {@code FILE_SHA256} payloads (Codex 019e6a3e iter-2
 * acceptance #5).
 */
class WindowsPathSafetyValidatorTest {

    @Test
    void acceptsAllowedProgramFilesPrefix() {
        assertThatCode(() -> WindowsPathSafetyValidator
                .validate("C:\\Program Files\\7-Zip\\7z.exe"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAllowedProgramFilesX86PrefixCaseInsensitive() {
        assertThatCode(() -> WindowsPathSafetyValidator
                .validate("c:\\Program FILES (x86)\\7-Zip\\7z.exe"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUncPath() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("\\\\fileserver\\share\\app.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNC");
    }

    @Test
    void rejectsEnvironmentVariableExpansion() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("%ProgramFiles%\\7-Zip\\7z.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment-variable");
    }

    @Test
    void rejectsForwardSlashPath() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("C:/Program Files/7-Zip/7z.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward slashes");
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("C:\\Program Files\\..\\Windows\\System32\\cmd.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent traversal");
    }

    @Test
    void rejectsEightDotThreeShortNameAlias() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("C:\\PROGRA~1\\7-Zip\\7z.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("short-name");
    }

    @Test
    void rejectsPathOutsideAllowlist() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator
                .validate("C:\\Windows\\System32\\cmd.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlisted Windows prefix");
    }

    @Test
    void rejectsBlankPath() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsNullPath() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    // ── Path C2 (Codex 019e893a iter-4) agent C1 mirror guards ─────────

    @Test
    void rejectsAlternateDataStream() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate(
                "C:\\Program Files\\7-Zip\\7z.exe:hiddenstream"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alternate Data Stream");
    }

    @Test
    void rejectsExplicitAdsDataStream() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate(
                "C:\\Program Files\\7-Zip\\7z.exe::$DATA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alternate Data Stream");
    }

    @Test
    void rejectsCurrentDirSegment() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate(
                "C:\\Program Files\\.\\7-Zip\\7z.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current-directory");
    }

    @Test
    void rejectsControlCharacter() {
        assertThatThrownBy(() -> WindowsPathSafetyValidator.validate(
                "C:\\Program Files\\7-Zip\r\\7z.exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }
}
