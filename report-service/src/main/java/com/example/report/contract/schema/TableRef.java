package com.example.report.contract.schema;

/**
 * Phase 2 Program 11.2a — Workcube SQL table reference (Codex iter-20 absorb).
 *
 * <p>Extracted from a {@code sourceQuery} string by
 * {@link WorkcubeSqlTableRefScanner}. Captures enough metadata for
 * downstream allowlist enforcement and (Adım 11.2c) composite tenant
 * boundary checking — without committing to a particular SQL AST.
 *
 * @param raw         the matched substring as it appeared in the SQL
 * @param schema      the schema qualifier (e.g. {@code workcube_mikrolink},
 *                    {@code {schema}} placeholder, or {@code null} for
 *                    unqualified refs)
 * @param table       the table identifier (uppercase normalised by the
 *                    scanner)
 * @param schemaKind  classification driving tenant-boundary semantics —
 *                    see {@link SchemaKind}
 * @param clause      best-effort source clause ({@code FROM} / {@code JOIN} /
 *                    {@code APPLY} / {@code UNKNOWN}); informational only
 * @param position    character offset of the match in the original SQL,
 *                    for diagnostic error messages
 */
public record TableRef(
        String raw,
        String schema,
        String table,
        SchemaKind schemaKind,
        String clause,
        int position) {

    /**
     * Schema classification — drives whether the ref is tenant-scoped,
     * canonical, or unsupported.
     */
    public enum SchemaKind {
        /** {@code {schema}} placeholder — resolved at render time. */
        PLACEHOLDER,
        /** {@code workcube_mikrolink} (no suffix) — global canonical/master schema. */
        CANONICAL,
        /** {@code workcube_mikrolink_<year>_<tenantId>} — yearly tenant partition. */
        YEARLY_PARTITION,
        /** {@code workcube_mikrolink_<tenantId>} (no year) — current tenant master. */
        CURRENT_TENANT,
        /** Unqualified table name (no schema prefix). */
        UNQUALIFIED,
        /** Schema didn't match any expected pattern — fail-closed candidate. */
        UNKNOWN
    }
}
