package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
