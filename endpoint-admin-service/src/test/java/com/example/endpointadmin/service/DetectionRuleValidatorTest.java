package com.example.endpointadmin.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Detection rule polymorphic shape + raw-command boundary tests. WINGET +
 * REGISTRY_UNINSTALL cases mirror the AG-027 agent canonical schema
 * (agent PR #43 / Codex 019e7d82); FILE_* + raw-command rejection retain the
 * BE-020 boundary (Codex 019e6a3e iter-1 RED on {@code EXIT_CODE { command }}).
 */
class DetectionRuleValidatorTest {

    /** Valid MSI product-code GUID {@code {8-4-4-4-12 hex}}. */
    private static final String PRODUCT_CODE = "{23170F69-40C1-2702-2107-000001000000}";

    private final DetectionRuleValidator validator = new DetectionRuleValidator();

    // ── WINGET_PACKAGE ────────────────────────────────────────────────

    @Test
    void normalizesWingetPackageWithPackageId() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "WINGET_PACKAGE");
        raw.put("packageId", "7zip.7zip");
        raw.put("wingetSourceName", "winget");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "WINGET_PACKAGE");
        assertThat(normalized).containsEntry("packageId", "7zip.7zip");
        assertThat(normalized).containsEntry("wingetSourceName", "winget");
        assertThat(normalized).doesNotContainKey("wingetPackageId");
    }

    @Test
    void normalizesWingetPackageFromLegacyWingetPackageId() {
        // Legacy authoring field is accepted on input and stored as the
        // agent-canonical packageId.
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "WINGET_PACKAGE");
        raw.put("wingetPackageId", "7zip.7zip");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("packageId", "7zip.7zip");
        assertThat(normalized).doesNotContainKey("wingetPackageId");
    }

    @Test
    void rejectsWingetPackageWithoutPackageId() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "WINGET_PACKAGE");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packageId");
    }

    // ── REGISTRY_UNINSTALL (agent schema) ─────────────────────────────

    @Test
    void normalizesRegistryUninstallByProductCode() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("productCode", PRODUCT_CODE);

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("type", "REGISTRY_UNINSTALL");
        assertThat(normalized).containsEntry("productCode", PRODUCT_CODE);
        assertThat(normalized).doesNotContainKeys("displayName", "hive",
                "uninstallKeyName", "displayNameRegex");
    }

    @Test
    void rejectsRegistryUninstallWithNonGuidProductCode() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("productCode", "7-Zip"); // not an MSI GUID

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MSI GUID");
    }

    @Test
    void rejectsRegistryUninstallProductCodeWithPathSeparator() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        // A path-separator injection attempt is not a {8-4-4-4-12 hex} GUID.
        raw.put("productCode", "{SOFTWARE\\Microsoft\\Windows}");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MSI GUID");
    }

    @Test
    void normalizesRegistryUninstallByDisplayNameAndPublisher() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "PREFIX");
        raw.put("publisher", "Igor Pavlov");
        raw.put("publisherMatch", "EXACT");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized)
                .containsEntry("type", "REGISTRY_UNINSTALL")
                .containsEntry("displayName", "7-Zip")
                .containsEntry("displayNameMatch", "PREFIX")
                .containsEntry("publisher", "Igor Pavlov")
                .containsEntry("publisherMatch", "EXACT");
        assertThat(normalized).doesNotContainKey("productCode");
    }

    @Test
    void defaultsDisplayNameAndPublisherMatchToExact() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("publisher", "Igor Pavlov");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized)
                .containsEntry("displayNameMatch", "EXACT")
                .containsEntry("publisherMatch", "EXACT");
    }

    @Test
    void lowercaseMatchModeIsUppercasedNotRejected() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "prefix");
        raw.put("publisher", "Igor Pavlov");
        raw.put("publisherMatch", "contains");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized)
                .containsEntry("displayNameMatch", "PREFIX")
                .containsEntry("publisherMatch", "CONTAINS");
    }

    @Test
    void productCodePrecedenceDropsDisplayNameWhenBothPresent() {
        // Mirror the agent (validateRegistryRule): productCode wins; a
        // displayName supplied alongside is ignored/dropped (Codex 019e7dce).
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("productCode", PRODUCT_CODE);
        raw.put("displayName", "7-Zip");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized).containsEntry("productCode", PRODUCT_CODE);
        assertThat(normalized).doesNotContainKeys("displayName", "displayNameMatch");
    }

    @Test
    void rejectsRegistryUninstallWithNeitherSelector() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productCode");
    }

    @Test
    void rejectsRegistryUninstallDisplayNameExceedingUtf8ByteLimit() {
        // 100 × '✓' (U+2713) = 300 UTF-8 bytes (> 256) but only 100 Java chars,
        // so a char-count check would wrongly pass while the agent (Go len() =
        // bytes) rejects. The backend must use UTF-8 byte length (Codex 019e7dce).
        String multibyte = "✓".repeat(100);
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", multibyte);
        raw.put("displayNameMatch", "EXACT");
        raw.put("publisher", "Igor Pavlov");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 bytes");
    }

    @Test
    void acceptsRegistryUninstallGlobWithStarAndQuestion() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip*?");
        raw.put("displayNameMatch", "GLOB");
        raw.put("publisher", "Igor Pavlov");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized)
                .containsEntry("displayName", "7-Zip*?")
                .containsEntry("displayNameMatch", "GLOB");
    }

    @Test
    void rejectsRegistryUninstallGlobWithRegexMetacharacters() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip[0-9]");
        raw.put("displayNameMatch", "GLOB");
        raw.put("publisher", "Igor Pavlov");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only * and ?");
    }

    @Test
    void rejectsRegistryUninstallDisplayNameWithoutPublisher() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "PREFIX");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisher");
    }

    @Test
    void acceptsRegistryUninstallMissingPublisherWithExactAndAllowFlag() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "EXACT");
        raw.put("allowPublisherMissing", true);

        Map<String, Object> normalized = validator.validateAndNormalize(raw);

        assertThat(normalized)
                .containsEntry("displayName", "7-Zip")
                .containsEntry("displayNameMatch", "EXACT")
                .containsEntry("allowPublisherMissing", Boolean.TRUE);
        assertThat(normalized).doesNotContainKeys("publisher", "publisherMatch");
    }

    @Test
    void rejectsRegistryUninstallMissingPublisherWhenMatchIsNotExact() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "PREFIX");
        raw.put("allowPublisherMissing", true); // only valid with EXACT

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisher");
    }

    @Test
    void rejectsRegistryUninstallInvalidPublisherMatch() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("publisher", "Igor Pavlov");
        raw.put("publisherMatch", "PREFIX"); // publisher allows only EXACT/CONTAINS

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherMatch");
    }

    @Test
    void rejectsRegistryUninstallInvalidDisplayNameMatch() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "REGISTRY_UNINSTALL");
        raw.put("displayName", "7-Zip");
        raw.put("displayNameMatch", "REGEX"); // not an allowed mode
        raw.put("publisher", "Igor Pavlov");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayNameMatch");
    }

    // ── FILE_EXISTS / FILE_SHA256 (unchanged catalog rules) ───────────

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

    // ── Path C2 FILE_SHA256 maxHashBytes (Codex 019e893a) ─────────────

    @Test
    void acceptsFileSha256WithMaxHashBytesUnderCap() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "a".repeat(64));
        raw.put("maxHashBytes", 1024L * 1024); // 1 MiB

        Map<String, Object> normalized = validator.validateAndNormalize(raw);
        assertThat(normalized).containsEntry("maxHashBytes", 1024L * 1024);
    }

    @Test
    void rejectsFileSha256MaxHashBytesAboveAgentCap() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "a".repeat(64));
        raw.put("maxHashBytes", DetectionRuleValidator.FILE_MAX_HASH_BYTES_AGENT + 1);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent hard cap");
    }

    @Test
    void rejectsFileSha256MaxHashBytesNegative() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "a".repeat(64));
        raw.put("maxHashBytes", -1L);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    // Codex 019e893a iter-4 P1: maxHashBytes must reject fractional values.

    @Test
    void rejectsFileSha256MaxHashBytesFractional() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "a".repeat(64));
        raw.put("maxHashBytes", 1.5d); // fractional

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
    }

    @Test
    void rejectsFileSha256MaxHashBytesNonNumericString() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_SHA256");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        raw.put("expectedSha256", "a".repeat(64));
        raw.put("maxHashBytes", "1.5"); // numeric string but fractional

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
    }

    // ── Path C2 FILE_VERSION (Codex 019e893a Opsiyon C) ───────────────

    @Test
    void acceptsFileVersionWithLatestPredicate() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "LATEST");
        raw.put("versionPredicate", predicate);

        Map<String, Object> normalized = validator.validateAndNormalize(raw);
        assertThat(normalized).containsEntry("type", "FILE_VERSION");
        assertThat(normalized).containsEntry("absolutePath",
                "C:\\Program Files\\7-Zip\\7z.exe");
        assertThat(normalized).containsEntry("fileVersionField", "FILE_VERSION");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedPredicate = (Map<String, Object>) normalized.get("versionPredicate");
        assertThat(normalizedPredicate).containsEntry("type", "LATEST");
        // spec dropped for LATEST
        assertThat(normalizedPredicate).doesNotContainKey("spec");
    }

    @Test
    void acceptsFileVersionWithExactPredicateAndProductVersion() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "EXACT");
        predicate.put("spec", "24.07");
        raw.put("versionPredicate", predicate);
        raw.put("fileVersionField", "PRODUCT_VERSION");

        Map<String, Object> normalized = validator.validateAndNormalize(raw);
        assertThat(normalized).containsEntry("fileVersionField", "PRODUCT_VERSION");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) normalized.get("versionPredicate");
        assertThat(p).containsEntry("type", "EXACT").containsEntry("spec", "24.07");
    }

    @Test
    void acceptsFileVersionWithRangePredicate() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "RANGE");
        predicate.put("spec", "[24.06,24.08)");
        raw.put("versionPredicate", predicate);

        Map<String, Object> normalized = validator.validateAndNormalize(raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) normalized.get("versionPredicate");
        assertThat(p).containsEntry("type", "RANGE").containsEntry("spec", "[24.06,24.08)");
    }

    @Test
    void rejectsFileVersionWithoutPredicate() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versionPredicate");
    }

    @Test
    void rejectsFileVersionExactMissingSpec() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "EXACT");
        raw.put("versionPredicate", predicate);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXACT requires a non-blank 'spec'");
    }

    @Test
    void rejectsFileVersionRangeMalformedSpec() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "RANGE");
        predicate.put("spec", "24.07-24.08"); // not bracket form
        raw.put("versionPredicate", predicate);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bracket form");
    }

    @Test
    void rejectsFileVersionUnknownPredicateType() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "REGEX");
        predicate.put("spec", ".*");
        raw.put("versionPredicate", predicate);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowlist");
    }

    @Test
    void rejectsFileVersionInvalidFileVersionField() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        raw.put("absolutePath", "C:\\Program Files\\7-Zip\\7z.exe");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "LATEST");
        raw.put("versionPredicate", predicate);
        raw.put("fileVersionField", "Description");

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FILE_VERSION or PRODUCT_VERSION");
    }

    @Test
    void rejectsFileVersionRequiresAbsolutePath() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("type", "FILE_VERSION");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("type", "LATEST");
        raw.put("versionPredicate", predicate);

        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── raw-command boundary (BE-020 RED) ─────────────────────────────

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
        raw.put("packageId", "7zip.7zip");

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
