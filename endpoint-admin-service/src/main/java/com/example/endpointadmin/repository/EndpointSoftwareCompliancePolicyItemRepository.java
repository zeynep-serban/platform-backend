package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.ComplianceEnforcementMode;
import com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link EndpointSoftwareCompliancePolicyItem} — BE-023.
 *
 * <p>All queries are tenant-scoped at the SQL layer; cross-tenant
 * leakage is structurally impossible at the DB layer via the composite
 * FK ({@code (catalog_item_id, tenant_id)}), but the queries still
 * filter by {@code tenant_id} to keep the query plan tenant-narrow.
 */
public interface EndpointSoftwareCompliancePolicyItemRepository
        extends JpaRepository<EndpointSoftwareCompliancePolicyItem, UUID> {

    Page<EndpointSoftwareCompliancePolicyItem> findByTenantIdOrderByLastUpdatedAtDesc(
            UUID tenantId, Pageable pageable);

    Optional<EndpointSoftwareCompliancePolicyItem> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Hot path for the evaluator: enabled REQUIRED/FORBIDDEN policy
     * rows for the tenant. ALLOWED rows are skipped to keep the
     * compliance evaluation O(REQUIRED+FORBIDDEN) rather than
     * O(catalog size). The catalog item is eagerly fetched on the same
     * round trip so the service does not need a second SELECT per row.
     */
    @Query("""
            SELECT p
              FROM EndpointSoftwareCompliancePolicyItem p
              JOIN FETCH p.catalogItem c
             WHERE p.tenantId = :tenantId
               AND p.enabled = true
               AND p.enforcementMode IN :modes
             ORDER BY p.enforcementMode ASC, p.id ASC
            """)
    List<EndpointSoftwareCompliancePolicyItem> findEnabledByTenantAndModes(
            @Param("tenantId") UUID tenantId,
            @Param("modes") List<ComplianceEnforcementMode> modes);

    Optional<EndpointSoftwareCompliancePolicyItem> findByTenantIdAndCatalogItemId(
            UUID tenantId, UUID catalogItemId);
}
