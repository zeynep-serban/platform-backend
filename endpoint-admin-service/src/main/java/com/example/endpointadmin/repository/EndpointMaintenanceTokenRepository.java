package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointMaintenanceToken;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.MaintenanceTokenStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointMaintenanceTokenRepository extends JpaRepository<EndpointMaintenanceToken, UUID> {

    Optional<EndpointMaintenanceToken> findByTenantIdAndId(UUID tenantId, UUID id);

    List<EndpointMaintenanceToken> findByTenantIdAndDevice_IdOrderByCreatedAtDesc(UUID tenantId, UUID deviceId);

    boolean existsByTenantIdAndDevice_IdAndActionAndStatusAndExpiresAtAfter(UUID tenantId,
                                                                            UUID deviceId,
                                                                            MaintenanceAction action,
                                                                            MaintenanceTokenStatus status,
                                                                            Instant expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token
            from EndpointMaintenanceToken token
            join fetch token.device device
            where token.tokenHash = :tokenHash
            """)
    Optional<EndpointMaintenanceToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
