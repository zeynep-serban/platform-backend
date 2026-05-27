package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * BE-020 — Spring Data JPA repository for
 * {@link EndpointSoftwareCatalogItem}.
 *
 * <p>All finders are tenant-scoped. Callers always pass the
 * {@code tenantId} from the {@code AdminTenantContext} so a tenant can
 * never observe another tenant's catalog rows even if the
 * {@code catalogItemId} slug collides across tenants (the
 * {@code uq_endpoint_software_catalog_items_tenant_catalog_item} unique
 * constraint scopes the slug per tenant).
 */
public interface EndpointSoftwareCatalogItemRepository
        extends JpaRepository<EndpointSoftwareCatalogItem, UUID> {

    Optional<EndpointSoftwareCatalogItem>
        findByTenantIdAndCatalogItemId(UUID tenantId, String catalogItemId);

    Optional<EndpointSoftwareCatalogItem>
        findByTenantIdAndId(UUID tenantId, UUID id);

    Page<EndpointSoftwareCatalogItem>
        findByTenantId(UUID tenantId, Pageable pageable);

    Page<EndpointSoftwareCatalogItem>
        findByTenantIdAndStatus(UUID tenantId,
                                CatalogItemStatus status,
                                Pageable pageable);

    Page<EndpointSoftwareCatalogItem>
        findByTenantIdAndStatusAndEnabled(UUID tenantId,
                                          CatalogItemStatus status,
                                          boolean enabled,
                                          Pageable pageable);

    Page<EndpointSoftwareCatalogItem>
        findByTenantIdAndEnabled(UUID tenantId,
                                 boolean enabled,
                                 Pageable pageable);

    boolean existsByTenantIdAndCatalogItemId(UUID tenantId,
                                             String catalogItemId);
}
