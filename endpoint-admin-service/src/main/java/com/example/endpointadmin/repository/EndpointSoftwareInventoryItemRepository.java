package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * BE-020I — Spring Data JPA repository for
 * {@link EndpointSoftwareInventoryItem}.
 *
 * <p>All queries are tenant + device scoped. The device-detail admin
 * endpoint pages items with optional case-insensitive {@code q}
 * (display name), publisher and install-source filters.
 */
public interface EndpointSoftwareInventoryItemRepository
        extends JpaRepository<EndpointSoftwareInventoryItem, UUID> {

    @Query("""
            select i
            from EndpointSoftwareInventoryItem i
            where i.tenantId = :tenantId
              and i.deviceId = :deviceId
              and (:q is null
                   or lower(i.displayName) like lower(concat('%', :q, '%')))
              and (:publisher is null
                   or lower(i.publisher) = lower(:publisher))
              and (:installSource is null
                   or i.installSource = :installSource)
            order by lower(i.displayName)
            """)
    Page<EndpointSoftwareInventoryItem> pageByTenantDeviceWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("q") String q,
            @Param("publisher") String publisher,
            @Param("installSource")
                com.example.endpointadmin.model.SoftwareInstallSource installSource,
            Pageable pageable);
}
