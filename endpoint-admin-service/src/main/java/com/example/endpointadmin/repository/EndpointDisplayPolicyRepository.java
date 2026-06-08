package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDisplayPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * #508 slice-2b — Spring Data JPA repository for {@link EndpointDisplayPolicy}
 * (current desired-state, one row per device).
 *
 * <p>The current row is upserted in place by the approval listener (ENFORCE ⇄
 * CLEAR flips the same row), so there is exactly one row per device once any
 * policy has been approved. Reads stay tenant-keyed ({@code org_id = tenant_id}).
 */
public interface EndpointDisplayPolicyRepository
        extends JpaRepository<EndpointDisplayPolicy, UUID> {

    Optional<EndpointDisplayPolicy> findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId);
}
