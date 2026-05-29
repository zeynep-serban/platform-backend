package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDeviceHealthDisk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * BE — device-health disk-per-snapshot read access (Faz 22.5, AG-033
 * ingest). In practice the snapshot's
 * {@code @OneToMany List<EndpointDeviceHealthDisk>} collection is the
 * canonical read path; this repository exists for tenant-scoped admin
 * queries and tests that need direct row access (parity with the BE-022
 * {@code EndpointHardwareInventoryDiskRepository}).
 */
@Repository
public interface EndpointDeviceHealthDiskRepository
        extends JpaRepository<EndpointDeviceHealthDisk, UUID> {
}
