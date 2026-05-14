package com.example.report.contract.schema;

import com.example.report.contract.schema.TableRef.SchemaKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 Program 11.2a — Lightweight T-SQL table-reference scanner
 * (Codex {@code 019e258f} iter-20 absorb).
 *
 * <h2>Why regex over AST</h2>
 * Repo-owned {@code sourceQuery} surface is controlled (every entry passes
 * through {@link com.example.report.contract.ContractValidator}). T-SQL
 * dialect oddities (bracketed identifiers, {@code WITH (NOLOCK)},
 * {@code TOP}, {@code {schema}} render-time placeholders,
 * {@code {tenantSetupProcessCatRelation}} per-tenant expansion) make a
 * full AST library more friction than value. A small scanner with masked
 * literals + string masking + token-based FROM/JOIN/APPLY recognition is
 * pragmatic.
 *
 * <h2>Defensive masking</h2>
 * SQL string literals (single-quoted) and line / block comments are
 * masked before the regex pass so that {@code WHEN 'FROM [X]' THEN ...}
 * does not surface a phantom table ref. Bracketed identifiers
 * ({@code [{schema}].[CARI_ROWS]}) are preserved; unbracketed two-part
 * names ({@code workcube_mikrolink.COMPANY}) are also supported.
 *
 * <h2>Unsupported targets fail-closed</h2>
 * {@code OPENQUERY(...)}, table variables ({@code @t}), temp tables
 * ({@code #t}), and 3/4-part names ({@code [server].[db].[schema].[t]})
 * are reported as {@link SchemaKind#UNKNOWN} so the caller can fail-closed
 * rather than silently bypass the allowlist.
 *
 * <p>Codex iter-20 explicit: "unsupported {@code FROM OPENQUERY(...)},
 * temp table, variable table, 3/4-part name gibi pattern'ler sessiz
 * geçmemeli; fail-closed 'unsupported table target' üretmeli."
 */
public final class WorkcubeSqlTableRefScanner {

    private WorkcubeSqlTableRefScanner() {
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("N?'([^']|'')*'");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * Bracketed two-part: {@code [schema].[TABLE]} or {@code [{schema}].[TABLE]}.
     */
    private static final Pattern BRACKETED_TWO_PART = Pattern.compile(
            "\\[(?<schema>[\\w{}]+)\\]\\.\\[(?<table>[A-Za-z_][\\w]*)\\]"
    );

    /**
     * Unbracketed two-part: {@code schema.TABLE} after FROM/JOIN/APPLY.
     * The leading keyword anchor avoids picking up arbitrary identifiers.
     */
    private static final Pattern UNBRACKETED_TWO_PART_AFTER_KEYWORD = Pattern.compile(
            "\\b(?:FROM|JOIN|APPLY)\\s+(?<schema>[A-Za-z_][\\w]*)\\.(?<table>[A-Za-z_][\\w]*)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Unsupported targets — these patterns are reported with
     * {@link SchemaKind#UNKNOWN} so the caller can fail-closed.
     */
    private static final Pattern OPENQUERY = Pattern.compile("\\bOPENQUERY\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMP_TABLE = Pattern.compile("\\b(?:FROM|JOIN|APPLY)\\s+(#{1,2}[A-Za-z_][\\w]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_VARIABLE = Pattern.compile("\\b(?:FROM|JOIN|APPLY)\\s+(@[A-Za-z_][\\w]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREE_PART = Pattern.compile(
            "\\[?[\\w{}]+\\]?\\.\\[?[\\w{}]+\\]?\\.\\[?[A-Za-z_][\\w]*\\]?\\.\\[?[A-Za-z_][\\w]*\\]?"
    );

    /**
     * Scan a {@code sourceQuery} (template or rendered) and return all
     * table references found. Order matches source position. Each ref
     * carries enough metadata for downstream allowlist / tenant boundary
     * enforcement.
     */
    public static List<TableRef> scan(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        String masked = maskLiteralsAndComments(sql);
        List<TableRef> refs = new ArrayList<>();

        // Bracketed two-part (most common pattern in repo sourceQueries)
        Matcher m = BRACKETED_TWO_PART.matcher(masked);
        while (m.find()) {
            String schema = m.group("schema");
            String table = m.group("table").toUpperCase(Locale.ROOT);
            SchemaKind kind = classifySchema(schema);
            refs.add(new TableRef(m.group(), schema, table, kind, "UNKNOWN", m.start()));
        }

        // Unbracketed two-part after FROM/JOIN/APPLY
        m = UNBRACKETED_TWO_PART_AFTER_KEYWORD.matcher(masked);
        while (m.find()) {
            String schema = m.group("schema");
            String table = m.group("table").toUpperCase(Locale.ROOT);
            SchemaKind kind = classifySchema(schema);
            // Detect clause (FROM / JOIN / APPLY)
            String matched = m.group();
            String clause = matched.substring(0, matched.indexOf(' ')).toUpperCase(Locale.ROOT);
            refs.add(new TableRef(matched, schema, table, kind, clause, m.start()));
        }

        // Unsupported targets — emit UNKNOWN for fail-closed semantics
        m = OPENQUERY.matcher(masked);
        while (m.find()) {
            refs.add(new TableRef("OPENQUERY(...)", null, "OPENQUERY", SchemaKind.UNKNOWN, "FROM", m.start()));
        }
        m = TEMP_TABLE.matcher(masked);
        while (m.find()) {
            refs.add(new TableRef(m.group(1), null, m.group(1), SchemaKind.UNKNOWN, "FROM", m.start()));
        }
        m = TABLE_VARIABLE.matcher(masked);
        while (m.find()) {
            refs.add(new TableRef(m.group(1), null, m.group(1), SchemaKind.UNKNOWN, "FROM", m.start()));
        }

        return refs;
    }

    /**
     * Replace string literals and SQL comments with whitespace of the
     * same length so that subsequent regex matches keep their positions
     * intact but won't pick up phantom refs from inside strings.
     */
    private static String maskLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql);
        replaceWithSpaces(out, BLOCK_COMMENT);
        replaceWithSpaces(out, LINE_COMMENT);
        replaceWithSpaces(out, STRING_LITERAL);
        return out.toString();
    }

    private static void replaceWithSpaces(StringBuilder sb, Pattern pattern) {
        Matcher m = pattern.matcher(sb);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (sb.charAt(i) != '\n' && sb.charAt(i) != '\r') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
    }

    private static SchemaKind classifySchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return SchemaKind.UNQUALIFIED;
        }
        // Render-time placeholder
        if (schema.equals("{schema}")) {
            return SchemaKind.PLACEHOLDER;
        }
        // workcube_mikrolink_<year>_<tenantId> pattern
        if (schema.matches("(?i)workcube_mikrolink_\\d{4}_\\d+")) {
            return SchemaKind.YEARLY_PARTITION;
        }
        // workcube_mikrolink_<tenantId> (no year) — current tenant master
        if (schema.matches("(?i)workcube_mikrolink_\\d+")) {
            return SchemaKind.CURRENT_TENANT;
        }
        // workcube_mikrolink (bare) — canonical
        if (schema.equalsIgnoreCase("workcube_mikrolink")) {
            return SchemaKind.CANONICAL;
        }
        return SchemaKind.UNKNOWN;
    }
}
