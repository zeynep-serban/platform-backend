package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointMachineCert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.3 — EndpointMachineCert query API. Active = revoked_at IS NULL
 * (partial unique indexes on (device_id) and (san_uri) enforce single-active).
 */
public interface EndpointMachineCertRepository extends JpaRepository<EndpointMachineCert, UUID> {

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.sanUri = :sanUri
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveBySanUri(@Param("sanUri") String sanUri);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.device.id = :deviceId
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveByDeviceId(@Param("deviceId") UUID deviceId);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.tenantId = :tenantId
              AND c.machineFingerprint = :machineFingerprint
              AND c.revokedAt IS NULL
            """)
    List<EndpointMachineCert> findActiveByTenantAndMachineFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("machineFingerprint") String machineFingerprint);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.tenantId = :tenantId
              AND c.certThumbprint = :thumbprint
            """)
    List<EndpointMachineCert> findByTenantIdAndCertThumbprint(
            @Param("tenantId") UUID tenantId,
            @Param("thumbprint") String thumbprint);
}
