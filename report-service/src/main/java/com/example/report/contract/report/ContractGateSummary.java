package com.example.report.contract.report;

import com.example.report.contract.exceptions.ContractExceptionEntry;
import com.example.report.contract.exceptions.ExceptionsRegistry.SuppressionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Phase 2 Program 1e — Detailed contract gate result for sticky PR comments
 * + machine-readable artifacts.
 *
 * <p>Codex iter-7 §1e-AGREE absorb (thread 019e0119): surfaces raw violations,
 * post-suppression filtered violations, and explicit suppression events
 * separately. Drives both the JSON artifact (machine-readable) and Markdown
 * sticky comment (PR reviewer view).
 *
 * <p>Suppression events come from {@code ExceptionsRegistry.applyDetailed()};
 * raw-minus-filtered diff is NOT used (identity-equality pitfall when the
 * same violation message recurs across reports).
 */
public record ContractGateSummary(
        int reportCount,
        List<ContractViolation> rawViolations,
        List<ContractViolation> filteredViolations,
        List<SuppressionEvent> suppressionEvents,
        List<ContractExceptionEntry> exceptionInventory,
        Instant generatedAt
) {

    public ContractGateSummary {
        rawViolations = rawViolations == null ? List.of() : List.copyOf(rawViolations);
        filteredViolations = filteredViolations == null ? List.of() : List.copyOf(filteredViolations);
        suppressionEvents = suppressionEvents == null ? List.of() : List.copyOf(suppressionEvents);
        exceptionInventory = exceptionInventory == null ? List.of() : List.copyOf(exceptionInventory);
    }

    /** Filtered violations with FAIL severity, sorted deterministically. */
    public List<ContractViolation> unsuppressedFailures() {
        return filteredViolations.stream()
                .filter(v -> v.severity() == ContractViolation.Severity.FAIL)
                .sorted(VIOLATION_ORDER)
                .toList();
    }

    /** Deterministic violation ordering: ruleId, then reportKey, then field. */
    private static final Comparator<ContractViolation> VIOLATION_ORDER =
            Comparator.comparing((ContractViolation v) -> v.ruleId() == null ? "" : v.ruleId())
                    .thenComparing(v -> v.reportKey() == null ? "" : v.reportKey())
                    .thenComparing(v -> v.field() == null ? "" : v.field());

    /** Filtered FAIL violations with meta-rule namespace (REPORT_* / EXCEPTION_* / RULE_EXECUTION_ERROR). */
    public List<ContractViolation> metaFailures() {
        return unsuppressedFailures().stream()
                .filter(ContractGateSummary::isMetaRule)
                .toList();
    }

    /** Per-report unsuppressed failures (parameterized test consumer). */
    public List<ContractViolation> unsuppressedFailuresFor(String reportKey) {
        return unsuppressedFailures().stream()
                .filter(v -> reportKey != null && reportKey.equals(v.reportKey()))
                .toList();
    }

    /** Suppression count grouped by ruleId, sorted by ruleId for deterministic Markdown order. */
    public Map<String, Long> suppressedByRule() {
        return suppressionEvents.stream()
                .collect(Collectors.groupingBy(
                        e -> e.violation().ruleId(),
                        TreeMap::new,
                        Collectors.counting()));
    }

    /**
     * Sorted exception inventory by entry id for deterministic Markdown
     * rendering (Codex iter-8 §1e-AGREE absorb: ConcurrentHashMap.values()
     * order is non-stable; sticky comment churn guard).
     */
    public List<ContractExceptionEntry> exceptionInventorySorted() {
        return exceptionInventory.stream()
                .sorted(Comparator.comparing(e -> e.id() == null ? "" : e.id()))
                .toList();
    }

    /** Per-entry expiry countdown in days (positive = future, negative = past). */
    public Map<String, Long> exceptionExpiryDays(Clock clock) {
        Instant now = clock.instant();
        Map<String, Long> days = new LinkedHashMap<>();
        for (ContractExceptionEntry entry : exceptionInventory) {
            if (entry.expiresAt() != null) {
                days.put(entry.id(), Duration.between(now, entry.expiresAt()).toDays());
            }
        }
        return days;
    }

    public boolean isGreen() {
        return unsuppressedFailures().isEmpty();
    }

    /** Render JSON artifact for downstream automation. */
    public String toJson(ObjectMapper mapper) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("reportCount", reportCount);
        output.put("generatedAt", generatedAt.toString());
        output.put("isGreen", isGreen());
        output.put("rawViolationCount", rawViolations.size());
        output.put("filteredViolationCount", filteredViolations.size());
        output.put("unsuppressedFailureCount", unsuppressedFailures().size());
        output.put("metaFailureCount", metaFailures().size());
        output.put("suppressedByRule", suppressedByRule());
        output.put("unsuppressedFailures", unsuppressedFailures().stream()
                .map(ContractGateSummary::violationToMap).toList());
        output.put("suppressionEvents", suppressionEvents.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("exceptionId", e.exceptionId());
                    m.put("ruleId", e.violation().ruleId());
                    m.put("reportKey", e.violation().reportKey());
                    return m;
                }).toList());
        output.put("exceptionInventory", exceptionInventory.stream()
                .map(ContractGateSummary::entryToMap).toList());
        try {
            return mapper.copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(output);
        } catch (Exception e) {
            throw new RuntimeException("Failed to render gate summary JSON: " + e.getMessage(), e);
        }
    }

    /** Render Markdown for PR sticky comment (Codex iter-7 absorb: blockers first). */
    public String toMarkdown(Clock clock) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Report Contract Gate\n\n");
        sb.append(isGreen() ? ":white_check_mark: **GREEN**" : ":x: **RED**")
                .append(" — `").append(unsuppressedFailures().size())
                .append("` unsuppressed failure(s) across `")
                .append(reportCount).append("` reports.\n\n");

        // Codex iter-7 absorb: unsuppressed failures FIRST (reviewer should see blockers).
        if (!unsuppressedFailures().isEmpty()) {
            sb.append("## :x: Unsuppressed Failures\n\n");
            sb.append("| Rule | Report | Field | Message |\n");
            sb.append("|---|---|---|---|\n");
            for (ContractViolation v : unsuppressedFailures()) {
                sb.append("| `").append(v.ruleId()).append("`")
                        .append(" | `").append(orEmpty(v.reportKey())).append("`")
                        .append(" | `").append(orEmpty(v.field())).append("`")
                        .append(" | ").append(escapePipes(orEmpty(v.message())))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        Map<String, Long> suppressedByRule = suppressedByRule();
        if (!suppressedByRule.isEmpty()) {
            sb.append("## :white_check_mark: Suppressed (governance debt)\n\n");
            sb.append("| Rule | Suppressed |\n|---|---|\n");
            for (Map.Entry<String, Long> e : suppressedByRule.entrySet()) {
                sb.append("| `").append(e.getKey()).append("` | ")
                        .append(e.getValue()).append(" |\n");
            }
            sb.append("\n");
        }

        if (!exceptionInventory.isEmpty()) {
            Map<String, Long> expiryDays = exceptionExpiryDays(clock);
            sb.append("## :warning: Active Exception Inventory (90-day horizon)\n\n");
            sb.append("| Entry | Rule(s) | Report | Days to expiry | Reason |\n");
            sb.append("|---|---|---|---|---|\n");
            for (ContractExceptionEntry entry : exceptionInventorySorted()) {
                Long days = expiryDays.get(entry.id());
                String dayCell = days == null
                        ? "—"
                        : (days < 0 ? "**EXPIRED " + Math.abs(days) + "d**" : days + "d");
                sb.append("| `").append(entry.id()).append("`")
                        .append(" | ").append(String.join(",", entry.ruleIds()))
                        .append(" | `").append(orEmpty(entry.reportKey())).append("`")
                        .append(" | ").append(dayCell)
                        .append(" | ").append(escapePipes(orEmpty(entry.reason())))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("Generated: `").append(generatedAt).append("`. ");
        sb.append("Source: `report-service/target/report-contract-gate/report.json`.\n");
        return sb.toString();
    }

    private static boolean isMetaRule(ContractViolation v) {
        if (v == null || v.ruleId() == null) {
            return false;
        }
        String rid = v.ruleId();
        return rid.startsWith("REPORT_")
                || rid.startsWith("EXCEPTION_")
                || (v.message() != null && v.message().startsWith("RULE_EXECUTION_ERROR"));
    }

    private static Map<String, Object> violationToMap(ContractViolation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", v.ruleId());
        m.put("severity", v.severity().name());
        m.put("reportKey", v.reportKey());
        m.put("field", v.field());
        m.put("message", v.message());
        return m;
    }

    private static Map<String, Object> entryToMap(ContractExceptionEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ruleIds", e.ruleIds());
        m.put("reportKey", e.reportKey());
        m.put("reason", e.reason());
        m.put("owner", e.owner());
        m.put("expiresAt", e.expiresAt() == null ? null : e.expiresAt().toString());
        return m;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String escapePipes(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|");
    }
}
