package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only history repository — BE-023.
 *
 * <p>Hot-path GET reads {@link com.example.endpointadmin.model.EndpointDeviceComplianceState
 * latest-pointer table}; this repository is used for history pagination
 * and audit replay.
 */
public interface EndpointComplianceEvaluationRepository
        extends JpaRepository<EndpointComplianceEvaluation, UUID> {

    Page<EndpointComplianceEvaluation> findByTenantIdAndDeviceIdOrderByEvaluatedAtDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * BE-025 — latest evaluation row for a device within the tenant. Used
     * by the prohibited-software read surface to read the persisted
     * {@code matchedItems.prohibitedInstalled} evidence (NOT a live
     * recompute). Tenant-scoped, so a cross-tenant / unknown device returns
     * empty — indistinguishable from "no evaluation yet" (no existence
     * leak).
     */
    Optional<EndpointComplianceEvaluation> findFirstByTenantIdAndDeviceIdOrderByEvaluatedAtDesc(
            UUID tenantId, UUID deviceId);
}
