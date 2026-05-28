package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemRequest;
import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemResponse;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCompliancePolicyItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-023 — Compliance policy item CRUD service (Faz 22.5).
 *
 * <p>Manages the per-tenant {@link EndpointSoftwareCompliancePolicyItem}
 * intent rows that drive {@link EndpointComplianceService}. Operations
 * are tenant-scoped and write the {@code createdBySubject /
 * lastUpdatedBySubject} audit fields from the bound
 * {@link AdminTenantContext}.
 *
 * <p>Cross-tenant catalog references are physically impossible at the
 * DB layer (composite FK {@code (catalog_item_id, tenant_id) ->
 * endpoint_software_catalog_items (id, tenant_id)}). The service also
 * performs an explicit pre-check so the operator gets a clean 400
 * rather than a 500 / DB FK violation when they supply a foreign
 * catalog id.
 */
@Service
public class EndpointCompliancePolicyService {

    private final EndpointSoftwareCompliancePolicyItemRepository policyRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final Clock clock;

    public EndpointCompliancePolicyService(
            EndpointSoftwareCompliancePolicyItemRepository policyRepository,
            EndpointSoftwareCatalogItemRepository catalogRepository) {
        this.policyRepository = policyRepository;
        this.catalogRepository = catalogRepository;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public Page<CompliancePolicyItemResponse> list(AdminTenantContext tenant, Pageable pageable) {
        return policyRepository
                .findByTenantIdOrderByLastUpdatedAtDesc(tenant.tenantId(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CompliancePolicyItemResponse get(AdminTenantContext tenant, UUID id) {
        return policyRepository.findByIdAndTenantId(id, tenant.tenantId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Compliance policy item not found."));
    }

    @Transactional
    public CompliancePolicyItemResponse create(
            AdminTenantContext tenant, CompliancePolicyItemRequest request) {
        EndpointSoftwareCatalogItem catalog = catalogRepository
                .findById(request.catalogItemId())
                .filter(c -> Objects.equals(c.getTenantId(), tenant.tenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Catalog item does not exist in the current tenant."));

        if (policyRepository.findByTenantIdAndCatalogItemId(
                tenant.tenantId(), request.catalogItemId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Compliance policy item already exists for this catalog item.");
        }

        EndpointSoftwareCompliancePolicyItem policy = new EndpointSoftwareCompliancePolicyItem();
        policy.setTenantId(tenant.tenantId());
        policy.setCatalogItemId(catalog.getId());
        policy.setEnforcementMode(request.enforcementMode());
        policy.setEnabled(request.enabled() == null ? true : request.enabled());
        // createdAt + lastUpdatedAt are managed by the entity's
        // @PrePersist hook; @PreUpdate refreshes lastUpdatedAt on
        // every UPDATE so the service doesn't touch the timestamps
        // directly.
        policy.setCreatedBySubject(tenant.subject());
        policy.setLastUpdatedBySubject(tenant.subject());

        try {
            EndpointSoftwareCompliancePolicyItem saved = policyRepository.save(policy);
            // Re-read so the lazy catalog reference is populated.
            return policyRepository.findByIdAndTenantId(saved.getId(), tenant.tenantId())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException("Saved policy item disappeared after persist."));
        } catch (DataIntegrityViolationException ex) {
            // Composite FK violation (cross-tenant) or unique
            // constraint races; surface as 400 / 409 respectively.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Compliance policy item could not be persisted: tenant or catalog mismatch.", ex);
        }
    }

    @Transactional
    public CompliancePolicyItemResponse update(
            AdminTenantContext tenant, UUID id, CompliancePolicyItemRequest request) {
        EndpointSoftwareCompliancePolicyItem policy = policyRepository
                .findByIdAndTenantId(id, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Compliance policy item not found."));

        // Catalog item id is immutable for v1 — if you need to retarget
        // a policy, delete and recreate. This keeps audit / hash
        // semantics straightforward.
        if (!Objects.equals(policy.getCatalogItemId(), request.catalogItemId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "catalogItemId is immutable on update; delete and recreate to retarget.");
        }

        policy.setEnforcementMode(request.enforcementMode());
        if (request.enabled() != null) {
            policy.setEnabled(request.enabled());
        }
        policy.setLastUpdatedBySubject(tenant.subject());
        EndpointSoftwareCompliancePolicyItem saved = policyRepository.save(policy);
        return policyRepository.findByIdAndTenantId(saved.getId(), tenant.tenantId())
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("Updated policy item disappeared after persist."));
    }

    @Transactional
    public void delete(AdminTenantContext tenant, UUID id) {
        EndpointSoftwareCompliancePolicyItem policy = policyRepository
                .findByIdAndTenantId(id, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Compliance policy item not found."));
        policyRepository.delete(policy);
    }

    private CompliancePolicyItemResponse toResponse(EndpointSoftwareCompliancePolicyItem policy) {
        EndpointSoftwareCatalogItem catalog = policy.getCatalogItem();
        return new CompliancePolicyItemResponse(
                policy.getId(),
                policy.getTenantId(),
                policy.getCatalogItemId(),
                catalog == null ? null : catalog.getCatalogItemId(),
                catalog == null ? null : catalog.getDisplayName(),
                policy.getEnforcementMode(),
                policy.isEnabled(),
                policy.getCreatedBySubject(),
                policy.getCreatedAt(),
                policy.getLastUpdatedBySubject(),
                policy.getLastUpdatedAt(),
                policy.getVersion());
    }
}
