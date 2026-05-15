package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * R16 PR-C — authz-reference-debt.yaml warn_until expiry enforcement.
 *
 * <p>Codex 019e27f5 REVISE P1 absorb: WARN-first registry'nin warn_until
 * expiry mekanizması. Debt entry warn_until tarihi geçmişse test FAIL eder.
 * Buy entry için CI yeşil olabilmesi için ya implement edilir ya tarih
 * uzatılır (justification + ADR reference ile).
 *
 * <p>Sister to {@link ContractRuleStubDetectorTest} PR-A pattern; aynı kalıcı
 * disiplin: WARN suspend süresi sınırlı.
 */
class AuthzReferenceDebtRegistryTest {

    private static final Path DEBT_REGISTRY = Paths.get(
            "src/main/resources/contract/authz-reference-debt.yaml");

    @Test
    void debtRegistry_shouldExist() {
        assertThat(DEBT_REGISTRY)
                .as("authz-reference-debt.yaml present (PR-C registry)")
                .exists();
    }

    @Test
    void debtRegistry_entries_shouldHaveRequiredFieldsAndValidExpiry() throws IOException {
        List<Map<String, Object>> entries = loadEntries();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<String> invalid = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object ruleId = entry.get("rule_id");
            Object referenceType = entry.get("reference_type");
            Object key = entry.get("key");
            Object warnUntil = entry.get("warn_until");
            Object owner = entry.get("owner");
            Object reason = entry.get("reason");
            Object trackingPr = entry.get("tracking_pr");

            if (ruleId == null || referenceType == null || key == null || warnUntil == null
                    || owner == null || reason == null || trackingPr == null) {
                invalid.add(String.valueOf(ruleId) + "/" + key
                        + " (missing required: rule_id, reference_type, key, warn_until,"
                        + " owner, reason, tracking_pr)");
                continue;
            }
            // ISO YYYY-MM-DD parse + expiry semantic gate (Codex P2)
            String warnUntilStr = warnUntil.toString();
            LocalDate expiryDate;
            try {
                expiryDate = LocalDate.parse(
                        warnUntilStr.substring(0, Math.min(10, warnUntilStr.length())),
                        DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ex) {
                invalid.add(ruleId + "/" + key + " (invalid warn_until format '"
                        + warnUntilStr + "')");
                continue;
            }
            if (expiryDate.isBefore(today)) {
                invalid.add(ruleId + "/" + key + " (warn_until=" + warnUntilStr
                        + " is in the past, today=" + today
                        + "; debt expired — implement the contract OR extend"
                        + " the warn_until with justification + ADR reference)");
            }
            if (owner.toString().isBlank()) {
                invalid.add(ruleId + "/" + key + " (owner is blank)");
            }
            if (reason.toString().isBlank()) {
                invalid.add(ruleId + "/" + key + " (reason is blank)");
            }
        }

        assertThat(invalid)
                .as("authz-reference-debt.yaml entries: rule_id + reference_type + key +"
                        + " warn_until (ISO YYYY-MM-DD not in past) + owner + reason +"
                        + " tracking_pr required. Codex 019e27f5 REVISE P1/P2 absorb.")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadEntries() throws IOException {
        if (!Files.exists(DEBT_REGISTRY)) return List.of();
        Yaml yaml = new Yaml();
        try (var in = Files.newBufferedReader(DEBT_REGISTRY)) {
            Map<String, Object> root = yaml.load(in);
            if (root == null) return List.of();
            Object entries = root.get("authz_reference_debt");
            if (entries instanceof List<?> list) {
                List<Map<String, Object>> typed = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        typed.add((Map<String, Object>) map);
                    }
                }
                return typed;
            }
            return List.of();
        }
    }
}
