package com.example.report.query;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.ReportDefinition;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves year-based schema names for Workcube multi-tenant structure.
 * Pattern: workcube_mikrolink_{YYYY}_{companyId}
 *
 * Caches available schema names from sys.schemas to avoid repeated lookups.
 * Extracts date ranges from AG Grid filters to determine which year schemas to query.
 */
@Component
public class YearlySchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(YearlySchemaResolver.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantMasterSchemaResolver tenantMasterSchemaResolver;

    public YearlySchemaResolver(NamedParameterJdbcTemplate jdbc,
                                TenantMasterSchemaResolver tenantMasterSchemaResolver) {
        this.jdbc = jdbc;
        this.tenantMasterSchemaResolver = tenantMasterSchemaResolver;
    }

    /**
     * Single resolved branch — one (tenant, year) pair plus its master
     * tenant-schema and lookup availability. Codex 019e0c99 iter-3 §A absorb:
     * branch granularity is tenant-year (not tenant-only), so the same tenant
     * across 2024-2026 emits three branches sharing the same tenantSchema.
     */
    public record Branch(
            String transactionSchema,
            int year,
            long tenantId,
            String tenantSchema,
            boolean tenantLookupAvailable) {}

    /**
     * All resolved branches for a yearly report. Each branch corresponds to
     * one transaction schema (workcube_mikrolink_&lt;year&gt;_&lt;tenantId&gt;)
     * plus its master tenant lookup metadata.
     *
     * <p>Backward-compat helpers ({@link #schemas()}, {@link #isSingle()}) keep
     * existing call sites working during the migration; new code should use
     * {@link #branches()} directly.
     */
    public record ResolvedSchemas(List<Branch> branches) {
        /** Backward-compat: flat list of transaction schemas. */
        public List<String> schemas() {
            return branches.stream().map(Branch::transactionSchema).toList();
        }

        public boolean isSingle() {
            return branches.size() == 1;
        }
    }

    /**
     * Resolve which year schemas to query for a yearly report.
     *
     * @param def           report definition (must have isYearlySchema() == true)
     * @param authz         user's authz context (for extracting companyId from COMPANY scope)
     * @param agGridFilter  AG Grid filter model (may contain date range on yearColumn)
     * @return resolved schema names that actually exist in the database
     */
    public ResolvedSchemas resolve(ReportDefinition def, AuthzMeResponse authz,
                                   Map<String, Object> agGridFilter) {
        if (!def.isYearlySchema()) {
            // Static schema — emit a single synthetic branch where the
            // transaction schema is the static sourceSchema. tenantId is
            // unknown at this layer; tenantSchema falls back to the global
            // master so tenant-lookup placeholders short-circuit to a stable
            // global path. lookupAvailable=false is conservative; static
            // reports do not currently use the tenant-lookup placeholder
            // family, so this branch is metadata-only.
            return new ResolvedSchemas(List.of(new Branch(
                    def.sourceSchema(),
                    Year.now().getValue(),
                    0L,
                    "workcube_mikrolink",
                    false)));
        }

        // Phase 2 Program 2a (Codex iter-10 §2a-AGREE absorb): tenant boundary
        // hardening for yearly reports. Silent fallback to def.sourceSchema()
        // removed — caller MUST provide explicit COMPANY scope (or super-admin
        // with X-Company-Id picker via CompanyHeaderScopeNarrower) so the
        // yearly partition resolver runs against an actual tenant ID.

        // Extract company IDs from RLS scope (set by CompanyHeaderScopeNarrower
        // for super-admin + picker; populated from JWT for regular users).
        Set<String> companyIds = authz != null ? authz.getScopeRefIds("COMPANY") : Set.of();
        if (companyIds.isEmpty()) {
            throw new TenantSelectionRequiredException(def.key(),
                    "Yearly report '" + def.key() + "' requires an explicit COMPANY scope; "
                            + "no scope present in authz context (super-admin must use "
                            + "X-Company-Id picker header). Silent sourceSchema fallback "
                            + "removed (Phase 2 Program 2a tenant guard hardening).");
        }

        // Extract year range from date filters
        int[] yearRange = extractYearRange(def.yearColumn(), agGridFilter);
        int startYear = yearRange[0];
        int endYear = yearRange[1];

        // Get all available schemas from cache
        Set<String> available = getAvailableSchemas();

        // Build branch list: one branch per (tenantId, year) pair whose
        // transaction schema actually exists. Each branch carries its master
        // tenant-schema + lookup-availability for placeholders like
        // {tenantSetupProcessCatRelation} (Codex 019e0c99 iter-3 §A absorb).
        List<Branch> resolvedBranches = new ArrayList<>();
        List<String> attempted = new ArrayList<>();
        for (String companyId : companyIds) {
            long tenantIdLong;
            try {
                tenantIdLong = Long.parseLong(companyId);
            } catch (NumberFormatException ex) {
                log.warn("Skipping non-numeric companyId in scope: {}", companyId);
                continue;
            }
            String tenantSchema = tenantMasterSchemaResolver.resolveTenantSchema(tenantIdLong);
            // Cached existence probe — tenant 1-40 typically true, 50+ inactive.
            boolean tenantLookupAvailable = tenantMasterSchemaResolver
                    .isTenantLookupAvailable(tenantIdLong, "SETUP_PROCESS_CAT");

            for (int year = startYear; year <= endYear; year++) {
                String schema = "workcube_mikrolink_" + year + "_" + companyId;
                attempted.add(schema);
                if (available.contains(schema.toLowerCase(Locale.ROOT))) {
                    resolvedBranches.add(new Branch(
                            schema, year, tenantIdLong, tenantSchema, tenantLookupAvailable));
                } else {
                    log.debug("Schema not found: {}", schema);
                }
            }
        }

        if (resolvedBranches.isEmpty()) {
            // Phase 2 Program 2a: silent def.sourceSchema() fallback removed.
            // Caller surfaces 503 schema_resolver_miss so users see a clearer
            // error rather than silently querying canonical reference data.
            throw new SchemaResolverMissException(def.key(), attempted,
                    "Yearly schema resolver miss for report '" + def.key() + "': "
                            + "no matching workcube_mikrolink_<year>_<companyId> schema "
                            + "found for years " + startYear + "-" + endYear
                            + ", companies " + companyIds + " (attempted "
                            + attempted.size() + " schemas). Phase 2 Program 2a tenant "
                            + "guard hardening: silent sourceSchema fallback removed.");
        }

        log.debug("Resolved {} branches for report {}: {}",
                resolvedBranches.size(), def.key(), resolvedBranches);
        return new ResolvedSchemas(resolvedBranches);
    }

    /**
     * Extract year range from AG Grid date filters on the yearColumn.
     * Returns [startYear, endYear]. Defaults to current year if no date filter found.
     */
    private int[] extractYearRange(String yearColumn, Map<String, Object> agGridFilter) {
        int currentYear = Year.now().getValue();

        if (yearColumn == null || yearColumn.isBlank() || agGridFilter == null || agGridFilter.isEmpty()) {
            // No date column or no filters — default to current year only
            return new int[]{currentYear, currentYear};
        }

        Object filterModel = agGridFilter.get(yearColumn);
        if (!(filterModel instanceof Map<?, ?> filterMap)) {
            // No filter on yearColumn — check all date-type filters for year hints
            return extractYearRangeFromAnyDateFilter(agGridFilter, currentYear);
        }

        return extractYearFromFilterMap(filterMap, currentYear);
    }

    @SuppressWarnings("unchecked")
    private int[] extractYearFromFilterMap(Map<?, ?> filterMap, int currentYear) {
        String type = (String) filterMap.get("type");
        if (type == null) {
            return new int[]{currentYear, currentYear};
        }

        return switch (type) {
            case "inRange" -> {
                int fromYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                int toYear = parseYearFromDateString(filterMap.get("filterTo"), currentYear);
                yield new int[]{Math.min(fromYear, toYear), Math.max(fromYear, toYear)};
            }
            case "equals" -> {
                int year = parseYearFromDateString(filterMap.get("filter"), currentYear);
                yield new int[]{year, year};
            }
            case "greaterThan", "greaterThanOrEqual" -> {
                int fromYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                yield new int[]{fromYear, currentYear};
            }
            case "lessThan", "lessThanOrEqual" -> {
                int toYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                // Go back max 5 years for open-ended "less than" filters
                yield new int[]{Math.max(toYear - 5, 2020), toYear};
            }
            case "notBlank" -> {
                // All data — go back 5 years
                yield new int[]{currentYear - 5, currentYear};
            }
            default -> new int[]{currentYear, currentYear};
        };
    }

    /**
     * If no filter on yearColumn specifically, scan all date filters for year hints.
     */
    private int[] extractYearRangeFromAnyDateFilter(Map<String, Object> agGridFilter, int currentYear) {
        int minYear = currentYear;
        int maxYear = currentYear;

        for (Map.Entry<String, Object> entry : agGridFilter.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> filterMap)) continue;
            String filterType = (String) filterMap.get("filterType");
            if (!"date".equals(filterType)) continue;

            int[] range = extractYearFromFilterMap(filterMap, currentYear);
            minYear = Math.min(minYear, range[0]);
            maxYear = Math.max(maxYear, range[1]);
        }

        return new int[]{minYear, maxYear};
    }

    /**
     * Parse year from an AG Grid date filter value.
     * AG Grid sends dates as "YYYY-MM-DD" strings.
     */
    private int parseYearFromDateString(Object dateValue, int fallback) {
        if (dateValue == null) return fallback;
        String s = dateValue.toString().trim();
        if (s.length() >= 4) {
            try {
                return Integer.parseInt(s.substring(0, 4));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return fallback;
    }

    /**
     * Cached: returns all schema names in the database (lowercase).
     * Queried from sys.schemas which is very fast on SQL Server.
     */
    /**
     * Extract company ID from sourceSchema pattern.
     * "workcube_mikrolink_1" → "1", "workcube_mikrolink_2" → "2"
     * Returns null if pattern doesn't match.
     */
    private String extractCompanyFromSchema(String sourceSchema) {
        if (sourceSchema == null) return null;
        // Pattern: workcube_mikrolink_{companyId} (no year part)
        String prefix = "workcube_mikrolink_";
        if (!sourceSchema.startsWith(prefix)) return null;
        String suffix = sourceSchema.substring(prefix.length());
        // Should be just a number (company ID), not year_company pattern
        if (suffix.matches("\\d+")) {
            return suffix;
        }
        return null;
    }

    @Cacheable(value = "yearlySchemas", key = "'all'")
    public Set<String> getAvailableSchemas() {
        log.info("Loading available schemas from sys.schemas...");
        List<String> schemas = jdbc.getJdbcTemplate().queryForList(
                "SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink%'",
                String.class);
        Set<String> result = schemas.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        log.info("Found {} workcube schemas", result.size());
        return result;
    }
}
