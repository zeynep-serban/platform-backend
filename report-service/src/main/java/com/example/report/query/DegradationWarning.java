package com.example.report.query;

/**
 * A single degradation event surfaced from query construction or execution.
 * Codex 019e0c99 iter-3 §C absorb: explicit warning model (no ThreadLocal)
 * propagated through {@link SqlBuilder.BuiltQuery#warnings()} and
 * {@link QueryEngine.PagedData#warnings()} so controllers can lift them
 * onto response headers (e.g. {@code X-Report-Degraded}).
 *
 * @param code        machine-readable degradation kind (e.g.
 *                    {@code tenant_lookup_unavailable}). Header dedupe by
 *                    code; per-tenant detail kept in this record's other
 *                    fields for log/metric.
 * @param tenantId    tenant identifier for which degradation applies
 *                    (string, may be {@code "unknown"} for cross-tenant
 *                    cases).
 * @param reportKey   report key (e.g. {@code fin-muhasebe-detay}).
 * @param table       lookup table name when relevant
 *                    (e.g. {@code SETUP_PROCESS_CAT}); may be {@code null}.
 * @param message     human-readable diagnostic for logs/metrics.
 */
public record DegradationWarning(
        String code,
        String tenantId,
        String reportKey,
        String table,
        String message) {

    /** Codex 019e0c99: lookup table missing in per-tenant master schema. */
    public static final String CODE_TENANT_LOOKUP_UNAVAILABLE = "tenant_lookup_unavailable";

    public static DegradationWarning tenantLookupUnavailable(
            long tenantId, String reportKey, String table) {
        return new DegradationWarning(
                CODE_TENANT_LOOKUP_UNAVAILABLE,
                Long.toString(tenantId),
                reportKey,
                table,
                String.format(
                        "tenant master lookup unavailable: workcube_mikrolink_%d.%s "
                                + "(report=%s); SQL substituted with empty-rowset, "
                                + "PROCESS_CAT_NAME defaulted to 'Diger'",
                        tenantId, table, reportKey));
    }
}
