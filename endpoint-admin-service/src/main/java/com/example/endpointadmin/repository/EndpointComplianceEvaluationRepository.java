package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only history repository — BE-023.
 *
 * <p>Hot-path GET reads {@link com.example.endpointadmin.model.EndpointDeviceComplianceState
 * latest-pointer table}; this repository is used for history pagination
 * and audit replay.
 *
 * <p>Faz 21.1 PR2b-iv.a — read methods migrated from derived
 * {@code findByTenantIdAnd…} to explicit {@code @Query} with the
 * canonical effective-org filter (Codex 019e8d12 D′ eligibility-gated
 * hybrid slice strategy + P1 parenthesized OR pattern). Accepts both
 * canonical rows (post-PR2b-ii: {@code org_id = tenant_id}) and legacy
 * rows ({@code org_id IS NULL AND tenant_id = :orgId}, defensive — V29
 * trigger normally back-fills but the OR branch guarantees correctness
 * independent of the trigger).
 *
 * <p>NOTE: the {@code orgId} parameter here is the caller's canonical
 * tenant scope (= legacy {@code tenantId}); the OR predicate is the
 * "effective-org" projection — V30 CHECK guarantees a written row's
 * {@code org_id} matches its {@code tenant_id} when both are populated.
 */
public interface EndpointComplianceEvaluationRepository
        extends JpaRepository<EndpointComplianceEvaluation, UUID> {

    /**
     * Paginated history for one device under one org/tenant scope.
     * Canonical PR2b-iv.a read — parenthesized effective-org OR replaces
     * the pre-PR2b-iv {@code findByTenantIdAndDeviceIdOrderByEvaluatedAtDesc}
     * derived method.
     */
    @Query("""
            select e
            from EndpointComplianceEvaluation e
            where (e.orgId = :orgId or (e.orgId is null and e.tenantId = :orgId))
              and e.deviceId = :deviceId
            order by e.evaluatedAt desc
            """)
    Page<EndpointComplianceEvaluation> findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(
            @Param("orgId") UUID orgId, @Param("deviceId") UUID deviceId, Pageable pageable);

    /**
     * BE-025 — latest evaluation row for a device within the org. Used
     * by the prohibited-software read surface to read the persisted
     * {@code matchedItems.prohibitedInstalled} evidence (NOT a live
     * recompute). Effective-org-scoped, so a cross-org / unknown device
     * returns empty — indistinguishable from "no evaluation yet" (no
     * existence leak).
     *
     * <p>PR2b-iv.a canonical replacement of the pre-PR2b-iv
     * {@code findFirstByTenantIdAndDeviceIdOrderByEvaluatedAtDesc}
     * derived method.
     */
    /**
     * Paginated effective-org latest history with explicit
     * {@code evaluatedAt DESC, id DESC} tiebreaker — the underlying
     * query for {@link #findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc}.
     * Called via that default-method wrapper with
     * {@link org.springframework.data.domain.PageRequest#of(int, int)
     * PageRequest(0, 1)} so plain JPQL can express {@code LIMIT 1}
     * portably (Codex 019e8d1d AGREE — default-method PageRequest
     * idiom over schema-qualified native LIMIT to avoid the
     * non-public-schema bug class).
     */
    @Query("""
            select e
            from EndpointComplianceEvaluation e
            where (e.orgId = :orgId or (e.orgId is null and e.tenantId = :orgId))
              and e.deviceId = :deviceId
            order by e.evaluatedAt desc, e.id desc
            """)
    java.util.List<EndpointComplianceEvaluation> findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDescAndIdDesc(
            @Param("orgId") UUID orgId, @Param("deviceId") UUID deviceId,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Effective-org latest-evaluation lookup (replaces derived
     * {@code findFirstByTenantIdAnd…}). Internally uses
     * {@link org.springframework.data.domain.PageRequest#of(int, int)
     * PageRequest(0, 1)} to express {@code LIMIT 1} portably across the
     * Spring Data JPA {@code @Query} API.
     */
    default Optional<EndpointComplianceEvaluation>
            findFirstVisibleToOrgAndDeviceIdOrderByEvaluatedAtDesc(UUID orgId, UUID deviceId) {
        java.util.List<EndpointComplianceEvaluation> page =
                findVisibleToOrgAndDeviceIdOrderByEvaluatedAtDescAndIdDesc(
                        orgId, deviceId, org.springframework.data.domain.PageRequest.of(0, 1));
        return page.isEmpty() ? Optional.empty() : Optional.of(page.get(0));
    }
}
