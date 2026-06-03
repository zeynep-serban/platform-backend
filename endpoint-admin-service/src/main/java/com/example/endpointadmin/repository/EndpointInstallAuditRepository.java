package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointInstallAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-021 — Spring Data JPA repository for {@link EndpointInstallAudit}.
 *
 * <p>Faz 21.1 PR2b-iv.e-A — per-device + tenant-wide history reads
 * migrated from derived {@code findByTenantIdAnd*} to explicit
 * {@code @Query} with the canonical effective-org filter in
 * index-friendly form (Codex 019e8dde Option B sub-slice e-A AGREE;
 * slice c iter-1 lesson — keep {@code tenant_id = :orgId} explicit so
 * the V12 composite index column order stays usable):
 *
 * <pre>
 *   WHERE a.tenant_id = :orgId
 *     AND (a.org_id = :orgId OR a.org_id IS NULL)
 *     AND a.device_id = :deviceId  -- device-scoped variants
 * </pre>
 *
 * <p>V30 {@code CHECK (org_id IS NULL OR org_id = tenant_id)} guarantees
 * semantic equivalence with the canonical effective-org form; V12 makes
 * {@code tenant_id NOT NULL} so legacy NULL fallback survives.
 *
 * <p>The compliance evaluator deterministic-selector query
 * {@link #findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(UUID, UUID, UUID, Instant)}
 * uses {@code created_at < before} (database commit timestamp) so the
 * selector cannot observe its own evaluation's audit row. The matching
 * partial index {@code idx_endpoint_install_audit_eval_selector} (V12)
 * keeps this read on the hot path. The compliance selector + the
 * existence guard are migrated in sub-slice e-B (separate PR — Codex
 * 019e8dde sub-slice split because they share the compliance evaluator
 * review surface, not the admin visibility surface).
 *
 * <p>{@link #findByCommandId(UUID)} is NOT migrated — V12
 * {@code command_id UNIQUE} (plus composite FK to
 * {@code endpoint_commands (id, tenant_id)}) enforces per-result global
 * uniqueness + tenant integrity by itself. The method is only used as
 * an idempotency probe; it is not part of any admin visibility read
 * path.
 */
public interface EndpointInstallAuditRepository extends JpaRepository<EndpointInstallAudit, UUID> {

    /**
     * Canonical PR2b-iv.e-A read — audit ownership gate by audit id.
     * PK lookup on {@code id} (no composite UNIQUE); tenant/org
     * predicate filters the single row returned. Empty Optional →
     * admin action 404 (no existence leak).
     */
    @Query("""
            select a
            from EndpointInstallAudit a
            where a.tenantId = :orgId
              and (a.orgId = :orgId or a.orgId is null)
              and a.id = :id
            """)
    Optional<EndpointInstallAudit> findVisibleToOrgAndId(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /**
     * Idempotency / per-command lookup probe. V12
     * {@code command_id UNIQUE} guarantees per-result uniqueness +
     * composite FK to {@code endpoint_commands (id, tenant_id)}
     * guarantees tenant integrity. NOT migrated — visibility/admin
     * read paths are served by {@link #findVisibleToOrgAndId(UUID, UUID)}
     * and the page-history methods below.
     */
    Optional<EndpointInstallAudit> findByCommandId(UUID commandId);

    /**
     * Canonical PR2b-iv.e-A read — device history listing with
     * effective-org filter. V12 composite index
     * {@code (tenant_id, device_id, reported_at DESC)} backs this read.
     * {@code countQuery} sibling computes total over the same
     * predicate.
     */
    @Query(value = """
            select a
            from EndpointInstallAudit a
            where a.tenantId = :orgId
              and (a.orgId = :orgId or a.orgId is null)
              and a.deviceId = :deviceId
            order by a.reportedAt desc
            """,
            countQuery = """
            select count(a)
            from EndpointInstallAudit a
            where a.tenantId = :orgId
              and (a.orgId = :orgId or a.orgId is null)
              and a.deviceId = :deviceId
            """)
    Page<EndpointInstallAudit> findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
            @Param("orgId") UUID orgId, @Param("deviceId") UUID deviceId, Pageable pageable);

    /**
     * Canonical PR2b-iv.e-A read — tenant-wide history listing with
     * effective-org filter (Codex 019e8dde non-blocker note:
     * {@code (tenant_id, reported_at DESC)} composite index would be
     * the optimal physical match for this query; the V12 schema does
     * not currently expose such an index — adding it is out of this
     * slice's source-only scope and would be a separate
     * state-mutation PR). PG planner picks the best available scan;
     * {@code countQuery} sibling computes total over the same
     * predicate.
     */
    @Query(value = """
            select a
            from EndpointInstallAudit a
            where a.tenantId = :orgId
              and (a.orgId = :orgId or a.orgId is null)
            order by a.reportedAt desc
            """,
            countQuery = """
            select count(a)
            from EndpointInstallAudit a
            where a.tenantId = :orgId
              and (a.orgId = :orgId or a.orgId is null)
            """)
    Page<EndpointInstallAudit> findVisibleToOrgOrderByReportedAtDesc(
            @Param("orgId") UUID orgId, Pageable pageable);

    /**
     * AG-028 Phase 1b — destructive-side existence gate. Returns {@code true}
     * if at least one prior SUCCEEDED + SATISFIED install audit row exists for
     * the given {@code (tenant, device, catalog)}. Used by
     * {@code EndpointUninstallService.propose} to enforce the "platform only
     * uninstalls what the platform installed" provenance invariant.
     *
     * <p>Codex post-impl iter-1 absorb (thread `019e8dcd`): the prior
     * `findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(Instant.MAX)`
     * is unsafe — {@code Instant.MAX} (year 1000000000) is outside the Postgres
     * {@code timestamptz} range and risks Hibernate/JDBC bind-time overflow or
     * a DB range error. A dedicated existence query is the right shape (no
     * deterministic-selector window, no upper bound, no select-list overhead).
     *
     * <p>NOT migrated in slice e-A; sub-slice e-B (separate PR) folds
     * this and the compliance selector into the effective-org form
     * together because they share the same review surface (compliance /
     * uninstall provenance under V12 partial index).
     */
    @Query("""
            select case when count(audit) > 0 then true else false end
            from EndpointInstallAudit audit
            where audit.tenantId = :tenantId
              and audit.deviceId = :deviceId
              and audit.catalogItemId = :catalogItemId
              and audit.resultStatus = com.example.endpointadmin.model.CommandResultStatus.SUCCEEDED
              and audit.postVerification = com.example.endpointadmin.model.InstallPostVerification.SATISFIED
            """)
    boolean existsSucceededSatisfiedByTenantDeviceCatalog(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("catalogItemId") UUID catalogItemId);

    @Query("""
            select audit
            from EndpointInstallAudit audit
            where audit.tenantId = :tenantId
              and audit.deviceId = :deviceId
              and audit.catalogItemId = :catalogItemId
              and audit.resultStatus = com.example.endpointadmin.model.CommandResultStatus.SUCCEEDED
              and audit.postVerification = com.example.endpointadmin.model.InstallPostVerification.SATISFIED
              and audit.createdAt < :before
            order by audit.createdAt desc
            """)
    java.util.List<EndpointInstallAudit>
        findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                @Param("tenantId") UUID tenantId,
                @Param("deviceId") UUID deviceId,
                @Param("catalogItemId") UUID catalogItemId,
                @Param("before") Instant before,
                Pageable pageable);

    default Optional<EndpointInstallAudit>
        findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                UUID tenantId, UUID deviceId, UUID catalogItemId, Instant before) {
        var page = findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                tenantId, deviceId, catalogItemId, before,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return page.isEmpty() ? Optional.empty() : Optional.of(page.get(0));
    }
}
