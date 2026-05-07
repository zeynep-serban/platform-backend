package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RC-007 — columns[].field projections must exist in sourceQuery SELECT (heuristic).
 *
 * <p>Severity: WARN — bounded heuristic (Codex iter-1 §4 absorb). Severity hep
 * WARN; FAIL'e yükseltme YASAK.
 *
 * <p>Heuristic patterns checked:
 * <ul>
 *   <li>{@code AS [aliasName]} (T-SQL bracketed alias)</li>
 *   <li>{@code AS aliasName} (unquoted alias)</li>
 *   <li>raw column token (whole-word match in SELECT clause)</li>
 *   <li>{@code SELECT *} → all checks skipped (best-effort acknowledged)</li>
 * </ul>
 *
 * <p>Parse edilemeyen sourceQuery (SELECT *, complex CTE, dynamic SQL) için
 * {@code WARN(rc007_unparsed_query)} — CI fail etmez.
 */
public final class RC007ColumnFieldExistsInSourceQuery implements ContractRule {

    private static final Pattern SELECT_STAR =
            Pattern.compile("\\bSELECT\\s+\\*\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public String ruleId() {
        return "RC-007";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def.sourceQuery() == null || def.sourceQuery().isBlank()) {
            return List.of();
        }
        if (def.columns() == null || def.columns().isEmpty()) {
            return List.of();
        }

        String src = def.sourceQuery();

        // SELECT * → skip — caller relies on runtime column resolution
        if (SELECT_STAR.matcher(src).find()) {
            return List.of();
        }

        List<ContractViolation> violations = new ArrayList<>();
        for (ColumnDefinition col : def.columns()) {
            if (col.field() == null || col.field().isBlank()) {
                continue;
            }
            if (!fieldAppearsInQuery(src, col.field())) {
                violations.add(ContractViolation.warn(
                        ruleId(), def.key(), "columns[" + col.field() + "]",
                        "Heuristic: column field '" + col.field()
                                + "' not found in sourceQuery SELECT — best-effort scan; "
                                + "actual presence may be obscured by aliases/CTEs"));
            }
        }
        return violations;
    }

    private boolean fieldAppearsInQuery(String src, String field) {
        // Try `AS [field]` bracket alias (T-SQL canonical)
        if (src.matches("(?si).*\\bAS\\s+\\[" + Pattern.quote(field) + "\\].*")) {
            return true;
        }
        // Try `AS field` unquoted alias
        if (src.matches("(?si).*\\bAS\\s+" + Pattern.quote(field) + "\\b.*")) {
            return true;
        }
        // Raw column token — whole-word match
        if (src.matches("(?si).*\\b" + Pattern.quote(field) + "\\b.*")) {
            return true;
        }
        return false;
    }
}
