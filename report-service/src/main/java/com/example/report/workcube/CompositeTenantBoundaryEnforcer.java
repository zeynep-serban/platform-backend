package com.example.report.workcube;

import com.example.report.contract.schema.TableRef;
import com.example.report.registry.ReportDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 11.2c — composite multi-table tenant boundary
 * enforcement for rendered Workcube SQL (Adım 11.2c).
 *
 * <p>Codex {@code 019e258f} iter-27 PARTIAL absorb. Ensures every
 * tenant-scoped table ref ({@link TableRef.SchemaKind#YEARLY_PARTITION}
 * + {@link TableRef.SchemaKind#CURRENT_TENANT}) in a rendered SQL
 * resolves to the <b>same tenant id</b>. Years may differ — multi-year
 * partition queries are a legitimate yearly reporting pattern; only the
 * tenant axis is the cross-tenant boundary.
 *
 * <h2>Why tenantId equality, not schema string equality</h2>
 * Codex iter-27 S3: a schema-string set with size&gt;1 would fail
 * legitimate multi-year reports (e.g. {@code workcube_mikrolink_2025_35}
 * + {@code workcube_mikrolink_2026_35} both resolve to tenant 35). The
 * invariant is "same tenantId across all tenant-scoped refs", not "same
 * schema string."
 *
 * <h2>Why also separate from V1 allowlist enforcement</h2>
 * Codex iter-27 final order: null/blank → UNKNOWN/UNQUALIFIED → V1 →
 * composite. Composite only meaningful on classified, allowlisted refs;
 * running it on garbage would obscure the root cause.
 *
 * <h2>Defense-in-depth rationale (Codex iter-27 S2)</h2>
 * The normal narrow chain ({@code TenantBoundaryGuard} →
 * {@code CompanyHeaderScopeNarrower} → singleton COMPANY →
 * {@code YearlySchemaResolver}) already drives to a single tenant. This
 * enforcer is the final defense before execution against:
 * <ul>
 *   <li>template tampering in {@code sourceQuery}</li>
 *   <li>{@code SqlBuilder} placeholder-substitution regression</li>
 *   <li>future controller adoption hatası (Adım 11.3/11.4 super-admin
 *       gate kaldırıldıktan sonra)</li>
 * </ul>
 */
@Service
public class CompositeTenantBoundaryEnforcer {

    /** {@code workcube_mikrolink_<year>_<tenantId>} — yearly partition. */
    private static final Pattern YEARLY_PATTERN =
            Pattern.compile("(?i)workcube_mikrolink_\\d{4}_(\\d+)");

    /** {@code workcube_mikrolink_<tenantId>} (no year) — current tenant. */
    private static final Pattern CURRENT_TENANT_PATTERN =
            Pattern.compile("(?i)workcube_mikrolink_(\\d+)");

    /**
     * Extract the numeric tenant id from a tenant-scoped schema name.
     * Returns empty for {@link TableRef.SchemaKind#CANONICAL},
     * {@link TableRef.SchemaKind#PLACEHOLDER}, and any kind that is not
     * inherently tenant-bound. Tenant-scoped kinds that fail regex parse
     * (shouldn't happen — scanner already classified them) signal an
     * unparseable schema and surface via {@link Optional#empty()} so the
     * caller can fail-closed.
     */
    Optional<Long> extractTenantId(TableRef ref) {
        if (ref.schema() == null) {
            return Optional.empty();
        }
        switch (ref.schemaKind()) {
            case YEARLY_PARTITION:
                Matcher y = YEARLY_PATTERN.matcher(ref.schema());
                if (y.matches()) {
                    return Optional.of(Long.parseLong(y.group(1)));
                }
                return Optional.empty();
            case CURRENT_TENANT:
                Matcher c = CURRENT_TENANT_PATTERN.matcher(ref.schema());
                if (c.matches()) {
                    return Optional.of(Long.parseLong(c.group(1)));
                }
                return Optional.empty();
            default:
                return Optional.empty();
        }
    }

    /**
     * Validate that all tenant-scoped refs in the rendered SQL resolve
     * to the same tenantId. Throws {@link WorkcubeQuerySecurityException}
     * on violation; caller maps to 403 via
     * {@link WorkcubeQueryExceptionHandler}.
     *
     * <p><b>Cases</b>:
     * <ul>
     *   <li>0 tenant-scoped refs (all canonical) → pass</li>
     *   <li>1 tenant id (multi-year or single-year same tenant) → pass</li>
     *   <li>&gt;1 distinct tenant ids → fail</li>
     *   <li>tenant-scoped ref with unparseable schema → fail-closed</li>
     * </ul>
     */
    public void validateComposite(List<TableRef> refs, ReportDefinition def) {
        Set<Long> tenantIds = new HashSet<>();
        for (TableRef ref : refs) {
            if (ref.schemaKind() != TableRef.SchemaKind.YEARLY_PARTITION
                    && ref.schemaKind() != TableRef.SchemaKind.CURRENT_TENANT) {
                continue;
            }
            Optional<Long> tenantId = extractTenantId(ref);
            if (tenantId.isEmpty()) {
                throw new WorkcubeQuerySecurityException(def.key(),
                        "Tenant-scoped schema '" + ref.schema() + "' at position "
                                + ref.position() + " for report '" + def.key()
                                + "' could not be parsed for tenant id — "
                                + "refusing to execute (scanner classification drift suspected).");
            }
            tenantIds.add(tenantId.get());
        }
        if (tenantIds.size() > 1) {
            throw new WorkcubeQuerySecurityException(def.key(),
                    "Rendered SQL spans multiple tenant ids " + tenantIds
                            + " for report '" + def.key() + "' — refusing "
                            + "cross-tenant query execution.");
        }
    }
}
