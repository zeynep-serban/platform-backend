package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint device repository.
 *
 * <p>Faz 21.1 PR2b-iv.b1 — device-by-id ownership gate migrated from
 * derived {@code findByTenantIdAndId} to explicit {@code @Query} with
 * the canonical effective-org filter (Codex 019e8d1d B-B sub-slice
 * AGREE; P1 parenthesized OR pattern). Accepts both canonical rows
 * (post-PR2b-ii: {@code org_id = tenant_id}) and legacy rows
 * ({@code org_id IS NULL AND tenant_id = :orgId}, defensive — V29
 * trigger normally back-fills but the OR branch guarantees correctness
 * independent of the trigger).
 *
 * <p>The {@code orgId} parameter is the caller's canonical tenant scope
 * (= legacy {@code tenantId}); V30 CHECK guarantees a written row's
 * {@code org_id} matches its {@code tenant_id} when both are populated.
 *
 * <p>b1 (id), b2 (hostname + fingerprint), b3 (hostnameAsc listing), and
 * b4 (statusIn) implemented in PR2b-iv.b1 (#397), PR2b-iv.b2 (#398),
 * PR2b-iv.b3 (#400), and PR2b-iv.b4 — closing the EndpointDeviceRepository
 * effective-org migration arc for the V29-eligible methods.
 */
public interface EndpointDeviceRepository extends JpaRepository<EndpointDevice, UUID> {

    /**
     * Canonical PR2b-iv.b1 read — effective-org device-by-id ownership
     * gate. Accepts both canonical (org_id = tenant_id) and legacy
     * (org_id IS NULL AND tenant_id = :orgId) rows via parenthesized OR.
     * Replaces the pre-PR2b-iv {@code findByTenantIdAndId} derived
     * method. Empty Optional → admin action 404 (no existence leak).
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.id = :id
            """)
    Optional<EndpointDevice> findVisibleToOrgAndId(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /**
     * Row-locking variant of {@link #findVisibleToOrgAndId} — acquires a
     * {@code PESSIMISTIC_WRITE} lock on the device row so a lifecycle
     * transition (decommission/reactivate) + its cascade run serialized
     * against concurrent operator races (Codex 019ea789: do not rely on
     * optimistic-lock exceptions for normal operator concurrency). MUST be
     * called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.id = :id
            """)
    Optional<EndpointDevice> findVisibleToOrgAndIdForUpdate(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /**
     * Canonical PR2b-iv.b2 read — effective-org device-by-hostname
     * adoption/discovery resolver (Codex 019e8d1d B-B sub-slice b2 AGREE).
     * Used by enrollment/auto-enroll flow as the hostname-fallback half
     * of the fingerprint-first/hostname-fallback adoption order.
     * Replaces the pre-PR2b-iv {@code findByTenantIdAndHostname} derived
     * method.
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.hostname = :hostname
            """)
    Optional<EndpointDevice> findVisibleToOrgAndHostname(
            @Param("orgId") UUID orgId, @Param("hostname") String hostname);

    /**
     * Canonical PR2b-iv.b2 read — effective-org device-by-machine
     * fingerprint adoption/discovery resolver (Codex 019e8d1d B-B
     * sub-slice b2 AGREE). Used by enrollment/auto-enroll flow as the
     * fingerprint-first half of the fingerprint-first/hostname-fallback
     * adoption order. Replaces the pre-PR2b-iv
     * {@code findByTenantIdAndMachineFingerprint} derived method.
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.machineFingerprint = :machineFingerprint
            """)
    Optional<EndpointDevice> findVisibleToOrgAndMachineFingerprint(
            @Param("orgId") UUID orgId, @Param("machineFingerprint") String machineFingerprint);

    /**
     * Canonical PR2b-iv.b3 read — effective-org device listing sorted by
     * hostname ascending (Codex 019e8d1d B-B sub-slice b3 AGREE; P1
     * parenthesized OR pattern). Used by the admin device list endpoint.
     * Replaces the pre-PR2b-iv {@code findByTenantIdOrderByHostnameAsc}
     * derived method.
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
            order by d.hostname asc
            """)
    List<EndpointDevice> findVisibleToOrgOrderByHostnameAsc(@Param("orgId") UUID orgId);

    /**
     * Canonical PR2b-iv.b4 read — effective-org device listing filtered by
     * status set (Codex 019e8d1d B-B sub-slice b4 AGREE; P1 parenthesized
     * OR pattern + IN clause). Currently used by repository-domain tests;
     * the derived {@code findByTenantIdAndStatusIn} had no production
     * service callsite but the repository API contract is preserved
     * (test migration absorbed here rather than dropping the method).
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.status in :statuses
            """)
    List<EndpointDevice> findVisibleToOrgAndStatusIn(
            @Param("orgId") UUID orgId, @Param("statuses") Collection<DeviceStatus> statuses);
}
