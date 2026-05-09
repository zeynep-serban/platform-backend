package com.example.report.query;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves per-tenant master schema name and lookup-table availability for
 * Workcube multi-tenant structure. Pattern: {@code workcube_mikrolink_<tenantId>}
 * (no year part — distinct from {@link YearlySchemaResolver}'s yearly transactional
 * schemas {@code workcube_mikrolink_<year>_<tenantId>}).
 *
 * <p>Some lookup tables (notably {@code SETUP_PROCESS_CAT}) live per-tenant in
 * the master schema rather than in the global {@code workcube_mikrolink} schema
 * or in the yearly transaction partition. Hardcoding {@code workcube_mikrolink_7}
 * (Codex 019e0c99 iter-1 bug) caused all 6 finance reports to read process
 * categories from tenant-7 regardless of the request's tenant scope.
 *
 * <p>Live inventory (2026-05-09 schema-service probe, threadId 019e0c99):
 * tenants 1-40 all have {@code workcube_mikrolink_<id>.SETUP_PROCESS_CAT}.
 * Tenants 50+ are inactive (master schema absent). For active tenants this
 * resolver returns {@code true}; for inactive tenants the SQL builder must
 * fall back to an empty-rowset substitution preserving the {@code SPC} alias
 * (Codex iter-2 §2 mandate).
 *
 * <p>Cache: {@code tenantSchemaAvailability} (5-min TTL, keyed by
 * {@code "<tenantId>:<tableName>"}). See {@link com.example.report.config.CacheConfig}.
 */
@Component
public class TenantMasterSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantMasterSchemaResolver.class);

    private static final String CHECK_TABLE_SQL = """
            SELECT COUNT(*)
            FROM sys.schemas s
            INNER JOIN sys.tables t ON t.schema_id = s.schema_id
            WHERE LOWER(s.name) = :schema
              AND LOWER(t.name) = :table
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TenantMasterSchemaResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Build the per-tenant master schema name. Returns {@code workcube_mikrolink_<tenantId>}.
     * Does not check existence; pair with {@link #isTenantLookupAvailable(long, String)}
     * to confirm the schema/table actually exists before relying on it.
     */
    public String resolveTenantSchema(long tenantId) {
        return "workcube_mikrolink_" + tenantId;
    }

    /**
     * Returns {@code true} if {@code workcube_mikrolink_<tenantId>.<tableName>}
     * exists in the underlying SQL Server, {@code false} otherwise. Result cached
     * 5 minutes (see {@link com.example.report.config.CacheConfig#cacheManager()}).
     *
     * <p>Failure mode: if the underlying query throws, we log and return
     * {@code false} — empty-rowset degrade path is safer than a 500 cascade
     * because the SQL builder still produces compile-safe SQL with NULL
     * literals (Codex iter-3 §B prescription).
     */
    @Cacheable(value = "tenantSchemaAvailability", key = "#tenantId + ':' + #tableName")
    public boolean isTenantLookupAvailable(long tenantId, String tableName) {
        String schema = resolveTenantSchema(tenantId).toLowerCase(Locale.ROOT);
        String table = tableName.toLowerCase(Locale.ROOT);
        try {
            Integer count = jdbc.queryForObject(
                    CHECK_TABLE_SQL,
                    new MapSqlParameterSource()
                            .addValue("schema", schema)
                            .addValue("table", table),
                    Integer.class);
            boolean available = count != null && count > 0;
            log.debug("tenantSchemaAvailability tenantId={} table={} schema={} available={}",
                    tenantId, tableName, schema, available);
            return available;
        } catch (Exception ex) {
            // Probe failure → assume unavailable; SQL builder substitutes
            // empty-rowset preserving alias contract (Codex iter-3 §B).
            log.warn("tenantSchemaAvailability probe failed tenantId={} table={}; degrading "
                    + "to unavailable (empty-rowset substitution will be used). cause={}",
                    tenantId, tableName, ex.getMessage());
            return false;
        }
    }
}
