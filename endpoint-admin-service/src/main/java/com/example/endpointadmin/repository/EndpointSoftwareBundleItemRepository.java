package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareBundleItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EndpointSoftwareBundleItemRepository
        extends JpaRepository<EndpointSoftwareBundleItem, UUID> {

    @EntityGraph(attributePaths = {"catalogItem"})
    List<EndpointSoftwareBundleItem> findByTenantIdAndBundleIdOrderByItemOrderAsc(
            UUID tenantId, UUID bundleId);

    long countByTenantIdAndBundleId(UUID tenantId, UUID bundleId);
}
