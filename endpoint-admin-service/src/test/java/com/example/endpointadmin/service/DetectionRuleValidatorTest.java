package com.example.endpointadmin.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-020 — detection rule polymorphic shape + raw-command boundary tests
 * (Codex 019e6a3e iter-1 RED on {@code EXIT_CODE { command }} +
 * iter-2 acceptance #4).
 */
class DetectionRuleValidatorTest {

    private final DetectionRuleValidator validator = new DetectionRuleValidator();

    @Test
    void normalizesValidWingetPackageRule() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "WINGET_PACKAGE");
        raw.put("wingetPackageId", "7zip.7zip");
        raw.put("wingetSourceName", "winget");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "WINGET_PACKAGE");
        assertThat(normalized).containsEntry("wingetPackageId", "7zip.7zip");
        assertThat(normalized).containsEntry("wingetSourceName", "winget");
    }

    @Test
    void rejectsWingetPackageWithoutPackageId() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "WINGET_PACKAGE");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wingetPackageId");
    }

    @Test
    void normalizesValidRegistryUninstallRule() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("hive", "HKLM");
        raw.put("uninstallKeyName", "7-Zip");
        raw.put("displayNameRegex", "^7-Zip.*");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "REGISTRY_UNINSTALL");
        assertThat(normalized).containsEntry("hive", "HKLM");
        assertThat(normalized).containsEntry("uninstallKeyName", "7-Zip");
        assertThat(normalized).containsEntry("displayNameRegex", "^7-Zip.*");
    }

    @Test
    void rejectsRegistryUninstallWithInvalidHive() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("hive", "HKCU");
        raw.put("uninstallKeyName", "7-Zip");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hive");
    }

    @Test
    void rejectsRegistryUninstallKeyWithPathSeparators() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("hive", "HKLM");
        raw.put("uninstallKeyName",
                "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\7-Zip");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path separators");
    }

    @Test
    void normalizesValidFileExistsRule() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_EXISTS");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "FILE_EXISTS");
        assertThat(normalized).containsEntry("absolutePath",
                "C:\\Program Files\\7-Zip\\7z.exe");
    }

    @Test
    void rejectsFileExistsWithUncPath() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_EXISTS");
        raw.put("absolutePath", "\\\\fileserver\\share\\app.exe");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNC");
    }

    @Test
    void normalizesValidFileSha256Rule() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256",
                "AAA111BBB222CCC333DDD444EEE555FFF666777888999000ABC123456789DEF0");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "FILE_SHA256");
        assertThat(normalized.get("expectedSha256")).isInstanceOf(String.class);
        assertThat((String) normalized.get("expectedSha256"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsFileSha256WithBadHashLength() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "AAAA");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64-character");
    }

    @Test
    void rejectsExitCodeRuleAsRawCommandSurface() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "EXIT_CODE");
        raw.put("command", "powershell.exe -c Get-Process");
        raw.put("expectedCode", 0);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MVP allowlist");
    }

    @Test
    void rejectsShellRuleAsRawCommandSurface() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "SHELL");
        raw.put("command", "cmd.exe /c dir");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MVP allowlist");
    }

    @Test
    void rejectsUnknownType() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "MAGIC_PROBE");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MVP allowlist");
    }

    @Test
    void rejectsMissingType() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("wingetPackageId", "7zip.7zip");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> validator.validateAndNormalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}
