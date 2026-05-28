package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.EndpointDeviceComplianceState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Latest-pointer read-model repository — BE-023.
 *
 * <p>Used by the hot-path GET endpoints and the cross-device list
 * endpoint with optional {@link ComplianceDecision} filter.
 */
public interface EndpointDeviceComplianceStateRepository
        extends JpaRepository<EndpointDeviceComplianceState, EndpointDeviceComplianceState.PK> {

    Optional<EndpointDeviceComplianceState> findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId);

    Page<EndpointDeviceComplianceState> findByTenantIdOrderByEvaluatedAtDesc(
            UUID tenantId, Pageable pageable);

    Page<EndpointDeviceComplianceState> findByTenantIdAndDecisionOrderByEvaluatedAtDesc(
            UUID tenantId, ComplianceDecision decision, Pageable pageable);
}
