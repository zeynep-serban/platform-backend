package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Runtime model for the report-definition {@code tenantBoundary} block.
 *
 * <p>Codex 019e0d06 iter-2 absorb: previously {@code tenantBoundary} was
 * declared in the JSON schema (Phase 2 Program 1c) but
 * {@link ReportDefinition} ignored it ({@code @JsonIgnoreProperties}). This
 * record graduates the field to a typed runtime value so resolver dispatch
 * (current vs yearly vs literal) can be driven by JSON contract instead of
 * implicit defaults.
 *
 * @param mode             tenant isolation mode (currently {@code schema}
 *                         is the only supported value).
 * @param scopeType        scope dimension that selects the tenant
 *                         (typically {@code tenant}).
 * @param schemaResolver   resolver name registered in
 *                         {@code RC008SchemaResolverRegistered} (e.g.
 *                         {@code yearlyPartitionRotation},
 *                         {@code workcube-current-company}).
 * @param schemaPattern    template used by the resolver to build the
 *                         transaction/master schema name (e.g.
 *                         {@code workcube_mikrolink_{year}_{tenantId}} or
 *                         {@code workcube_mikrolink_{tenantId}}).
 * @param reason           free-form rationale (informational).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantBoundary(
        String mode,
        String scopeType,
        String schemaResolver,
        String schemaPattern,
        String reason) {

    /** Codex 019e0d06: registered resolver name for current-tenant raporlar. */
    public static final String RESOLVER_WORKCUBE_CURRENT_COMPANY = "workcube-current-company";

    /** Codex 019dd34e: registered resolver name for yearly partition rotation. */
    public static final String RESOLVER_YEARLY_PARTITION_ROTATION = "yearlyPartitionRotation";

    public boolean isCurrentCompanyResolver() {
        return RESOLVER_WORKCUBE_CURRENT_COMPANY.equals(schemaResolver);
    }

    public boolean isYearlyResolver() {
        return RESOLVER_YEARLY_PARTITION_ROTATION.equals(schemaResolver);
    }
}
