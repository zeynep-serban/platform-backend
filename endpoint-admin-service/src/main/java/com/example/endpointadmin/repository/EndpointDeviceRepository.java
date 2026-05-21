package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointDeviceRepository extends JpaRepository<EndpointDevice, UUID> {

    Optional<EndpointDevice> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<EndpointDevice> findByTenantIdAndHostname(UUID tenantId, String hostname);

    Optional<EndpointDevice> findByTenantIdAndMachineFingerprint(UUID tenantId, String machineFingerprint);

    List<EndpointDevice> findByTenantIdAndStatusIn(UUID tenantId, Collection<DeviceStatus> statuses);

    List<EndpointDevice> findByTenantIdOrderByHostnameAsc(UUID tenantId);
}
