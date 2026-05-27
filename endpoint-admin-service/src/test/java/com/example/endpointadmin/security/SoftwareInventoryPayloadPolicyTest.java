package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-020I — unit tests for the fail-closed PII / sensitive-field validator
 * that runs before {@code endpoint_command_results} persistence
 * (Codex 019e6ab2 iter-2 acceptance #5).
 */
class SoftwareInventoryPayloadPolicyTest {

    private final SoftwareInventoryPayloadPolicy policy =
            new SoftwareInventoryPayloadPolicy();

    @Test
    void allowsCleanInventoryPayload() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "7-Zip",
                "displayVersion", "24.07",
                "publisher", "Igor Pavlov",
                "installDate", "20260101",
                "estimatedSizeKb", 12000,
                "architecture", "x64",
                "installSource", "HKLM",
                "uninstallStringPresent", true,
                "msiProductCodeHash", "sha256:0123456789abcdef"
        )));

        assertThatCode(() -> policy.validate(details))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLicenseKeyAnywhereInTree() {
        // Synthetic test value — intentionally NOT in a real API-key shape
        // so gitleaks does not flag this test fixture (PR #310 CI absorb).
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "Office",
                "licenseKey", "test-fake-license-marker-no-real-secret",
                "installSource", "HKLM"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("licenseKey");
    }

    @Test
    void rejectsProductKey() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "Office",
                "productKey", "secret",
                "installSource", "HKLM"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productKey");
    }

    @Test
    void rejectsRawUninstallString() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "AcmeApp",
                "uninstallString", "MsiExec.exe /X{12345678-...}",
                "installSource", "HKLM"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uninstallString");
    }

    @Test
    void rejectsRawMsiProductCodeGuidValue() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "AcmeApp",
                "publisher", "Acme",
                "installSource", "HKLM",
                "ourCustomGuidField", "{12345678-90AB-CDEF-1234-567890ABCDEF}"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MSI ProductCode GUID");
    }

    @Test
    void rejectsInvalidMsiProductCodeHashFormat() {
        // Raw 64-hex (not the agent wire sha256:<16hex> format) should be
        // rejected — protects against accidental double-hash or wrong format.
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "AcmeApp",
                "installSource", "HKLM",
                "msiProductCodeHash",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid msiProductCodeHash format");
    }

    @Test
    void rejectsUserProfilePath() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "App",
                "installSource", "HKLM",
                "discoveredAt", "C:\\Users\\alice\\AppData\\Local\\app.exe"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Per-user Windows path");
    }

    @Test
    void rejectsWindowsSidLiteral() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "App",
                "installSource", "HKLM",
                "owner", "S-1-5-21-1234567890-1234567890-1234567890-1001"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows SID literal");
    }

    @Test
    void rejectsBearerTokenKey() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "App",
                "installSource", "HKLM",
                "bearer", "anything"
        )));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bearer");
    }

    @Test
    void rejectsNestedForbiddenKeyInsideArbitrarySubMap() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of(
                "supported", true,
                "extras", Map.of("password", "hunter2")
        ));

        assertThatThrownBy(() -> policy.validate(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void tolerablesNumericAndBooleanValues() {
        Map<String, Object> details = inventoryWith(List.of(Map.of(
                "displayName", "App",
                "installSource", "HKLM",
                "estimatedSizeKb", 100L,
                "uninstallStringPresent", false
        )));

        assertThatCode(() -> policy.validate(details))
                .doesNotThrowAnyException();
    }

    private Map<String, Object> inventoryWith(List<Map<String, Object>> apps) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", apps.size());
        inventory.put("wingetReady", true);
        inventory.put("apps", apps);
        details.put("inventory", inventory);
        return details;
    }
}
