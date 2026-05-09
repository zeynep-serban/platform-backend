package com.example.report.query;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.ReportDefinition;
import java.time.Year;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the current per-tenant master schema for {@code schemaMode=current}
 * reports. Pattern: {@code workcube_mikrolink_<tenantId>} — no year part —
 * for "current state" reports that don't partition transactional data by year
 * (e.g. {@code stok-durum}, {@code satis-ozet}).
 *
 * <p>Codex 019e0d06 iter-2 absorb: graduates the existing
 * {@code schemaMode=current} contract enum (already declared in the JSON
 * schema + RC008 registered list) into a runtime resolver. Previously
 * {@code QueryEngine.resolveSchemas()} returned {@code null} for non-yearly
 * reports and {@code SqlBuilder} fell back to the literal {@code sourceSchema}
 * field. That literal was hardcoded ({@code workcube_mikrolink_1}) for the two
 * static reports, so every tenant saw tenant 1's data — the runtime bug this
 * resolver fixes.
 *
 * <p>Behavior contract (Codex iter-2 §5 + §7):
 * <ul>
 *   <li>Single COMPANY scope → auto-pick (one branch).</li>
 *   <li>Multi-company scope without {@code X-Company-Id} narrowing →
 *       {@link TenantSelectionRequiredException} 400. Header narrowing is
 *       performed upstream by
 *       {@code com.example.report.authz.CompanyHeaderScopeNarrower}.</li>
 *   <li>No COMPANY scope at all → {@link TenantSelectionRequiredException}.</li>
 *   <li>Implicit UNION ALL across tenants is INTENTIONALLY not supported;
 *       portfolio/cross-tenant view requires an explicit capability that
 *       does not exist yet.</li>
 *   <li>Schema existence miss → {@link SchemaResolverMissException} 503,
 *       fail-closed; empty-rowset degrade is reserved for optional lookup
 *       placeholders only (e.g. {@code {tenantSetupProcessCatRelation}}).</li>
 * </ul>
 */
@Component
public class CurrentTenantSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentTenantSchemaResolver.class);

    private final TenantMasterSchemaResolver tenantMasterResolver;
    private final YearlySchemaResolver yearlySchemaResolver;

    public CurrentTenantSchemaResolver(TenantMasterSchemaResolver tenantMasterResolver,
                                       YearlySchemaResolver yearlySchemaResolver) {
        this.tenantMasterResolver = tenantMasterResolver;
        this.yearlySchemaResolver = yearlySchemaResolver;
    }

    /**
     * Build a single-branch {@link YearlySchemaResolver.ResolvedSchemas} for
     * a {@code schemaMode=current} report. Tenant is taken from the COMPANY
     * scope on {@code authz} (already narrowed by
     * {@link com.example.report.access.CompanyHeaderScopeNarrower} when an
     * {@code X-Company-Id} header is present).
     */
    public YearlySchemaResolver.ResolvedSchemas resolve(ReportDefinition def, AuthzMeResponse authz) {
        Set<String> companyIds = authz != null ? authz.getScopeRefIds("COMPANY") : Set.of();

        if (companyIds.isEmpty()) {
            throw new TenantSelectionRequiredException(def.key(),
                    "Current-tenant report '" + def.key() + "' requires an explicit COMPANY scope; "
                            + "no scope present in authz context (super-admin must use "
                            + "X-Company-Id picker header).");
        }
        if (companyIds.size() > 1) {
            // Multi-company scope after CompanyHeaderScopeNarrower.narrow():
            // header was missing or did not match. Reuse same exception so
            // ReportPage's existing tenant-gate UX surfaces a CompanyPicker.
            throw new TenantSelectionRequiredException(def.key(),
                    "Current-tenant report '" + def.key() + "' requires X-Company-Id picker "
                            + "for multi-company users; got " + companyIds.size() + " allowed companies.");
        }

        long tenantId;
        try {
            tenantId = Long.parseLong(companyIds.iterator().next());
        } catch (NumberFormatException ex) {
            throw new TenantSelectionRequiredException(def.key(),
                    "Current-tenant report '" + def.key() + "' got non-numeric COMPANY scope: "
                            + companyIds);
        }

        String tenantSchema = tenantMasterResolver.resolveTenantSchema(tenantId);

        // Schema existence check — fail-closed if the tenant master schema
        // isn't actually present in MSSQL. We reuse YearlySchemaResolver's
        // cached sys.schemas snapshot (single round-trip per cache window).
        boolean schemaExists = yearlySchemaResolver
                .getAvailableSchemas()
                .contains(tenantSchema.toLowerCase(java.util.Locale.ROOT));
        if (!schemaExists) {
            throw new SchemaResolverMissException(def.key(), List.of(tenantSchema),
                    "Current-tenant resolver miss for report '" + def.key() + "': "
                            + "expected master schema '" + tenantSchema + "' not found in MSSQL "
                            + "(sys.schemas snapshot). Tenant " + tenantId + " inactive or not "
                            + "provisioned.");
        }

        // Codex 019e0d06 iter-3 absorb: tenantLookupAvailable must reflect
        // actual SETUP_PROCESS_CAT existence, not just schema existence.
        // satis-ozet/stok-durum şu an placeholder kullanmıyor ama bu doğru
        // değer ileride current sourceQuery {tenantSetupProcessCatRelation}
        // kullandığında SqlBuilder'ın empty-rowset degrade kararını korur.
        boolean lookupAvailable = tenantMasterResolver
                .isTenantLookupAvailable(tenantId, "SETUP_PROCESS_CAT");

        YearlySchemaResolver.Branch branch = new YearlySchemaResolver.Branch(
                tenantSchema, Year.now().getValue(), tenantId, tenantSchema, lookupAvailable);

        log.debug("Resolved current-tenant branch for report {}: {}", def.key(), branch);
        return new YearlySchemaResolver.ResolvedSchemas(List.of(branch));
    }
}
