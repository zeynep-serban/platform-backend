package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * BE-020I — Spring Data JPA repository for
 * {@link EndpointSoftwareInventorySnapshot}.
 *
 * <p>All finders are tenant-scoped. The fleet-wide list endpoint
 * additionally supports an optional {@code softwareName} exists-query via
 * {@link #pageByTenantWithFilters(UUID, String, String, Boolean, Boolean, Pageable)}.
 */
public interface EndpointSoftwareInventorySnapshotRepository
        extends JpaRepository<EndpointSoftwareInventorySnapshot, UUID> {

    Optional<EndpointSoftwareInventorySnapshot>
        findByTenantIdAndDevice_Id(UUID tenantId, UUID deviceId);

    /**
     * Tenant-scoped paged snapshot list with optional case-insensitive
     * {@code softwareName} exists-query + publisher + winget-ready +
     * truncated filters. {@code softwareName == null} skips the
     * exists-query; otherwise the snapshot row is only returned when at
     * least one of its items matches.
     */
    // BE-020I lower(bytea) fix (Codex thread 019e73cf PARTIAL absorb).
    //
    // Same null-bound-String-into-lower() pattern as
    // EndpointSoftwareInventoryItemRepository.pageByTenantDeviceWithFilters
    // (see that JavaDoc for the root cause). Fixed in the same PR so a
    // future fleet-wide call cannot resurrect the live bug from this
    // repository.
    @Query("""
            select s
            from EndpointSoftwareInventorySnapshot s
            where s.tenantId = :tenantId
              and (cast(:publisher as string) is null
                   or exists (
                       select 1 from EndpointSoftwareInventoryItem i
                       where i.snapshot = s
                         and lower(i.publisher) = lower(cast(:publisher as string))
                   ))
              and (cast(:softwareName as string) is null
                   or exists (
                       select 1 from EndpointSoftwareInventoryItem i
                       where i.snapshot = s
                         and lower(i.displayName) like lower(concat('%', cast(:softwareName as string), '%'))
                   ))
              and (:wingetReady is null or s.wingetReady = :wingetReady)
              and (:truncated is null or s.truncated = :truncated)
            order by s.updatedAt desc
            """)
    Page<EndpointSoftwareInventorySnapshot> pageByTenantWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("softwareName") String softwareName,
            @Param("publisher") String publisher,
            @Param("wingetReady") Boolean wingetReady,
            @Param("truncated") Boolean truncated,
            Pageable pageable);
}
