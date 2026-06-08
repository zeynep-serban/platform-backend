package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceLifecycleAction;
import com.example.endpointadmin.model.EndpointDeviceLifecycleAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link EndpointDeviceLifecycleAudit} (V56).
 *
 * <p>Append-only table — only INSERT (via {@code save}) + reads. The single
 * read is the latest-by-action lookup used by reactivate to derive its target
 * state from the last DECOMMISSION's {@code from_status} (Codex 019ea789
 * reactivate-target rule step 1). The table is created fresh in V56 with
 * {@code org_id NOT NULL} (compat trigger + CHECK), so org_id exact-match is
 * canonical here (no legacy null-org rows exist).
 */
public interface EndpointDeviceLifecycleAuditRepository
        extends JpaRepository<EndpointDeviceLifecycleAudit, UUID> {

    Optional<EndpointDeviceLifecycleAudit>
        findTopByDeviceIdAndOrgIdAndActionOrderByCreatedAtDesc(
            UUID deviceId, UUID orgId, DeviceLifecycleAction action);
}
