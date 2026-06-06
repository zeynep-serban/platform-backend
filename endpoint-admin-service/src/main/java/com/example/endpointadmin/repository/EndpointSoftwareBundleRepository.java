package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareBundle;
import com.example.endpointadmin.model.SoftwareBundleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointSoftwareBundleRepository
        extends JpaRepository<EndpointSoftwareBundle, UUID> {

    Optional<EndpointSoftwareBundle> findByTenantIdAndBundleId(
            UUID tenantId, String bundleId);

    Page<EndpointSoftwareBundle> findByTenantId(UUID tenantId,
                                                Pageable pageable);

    Page<EndpointSoftwareBundle> findByTenantIdAndStatus(
            UUID tenantId,
            SoftwareBundleStatus status,
            Pageable pageable);

    Page<EndpointSoftwareBundle> findByTenantIdAndStatusAndEnabled(
            UUID tenantId,
            SoftwareBundleStatus status,
            boolean enabled,
            Pageable pageable);

    Page<EndpointSoftwareBundle> findByTenantIdAndEnabled(
            UUID tenantId,
            boolean enabled,
            Pageable pageable);

    boolean existsByTenantIdAndBundleId(UUID tenantId, String bundleId);
}
