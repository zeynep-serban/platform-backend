package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointCommandRepository extends JpaRepository<EndpointCommand, UUID>,
        EndpointCommandRepositoryCustom {

    Optional<EndpointCommand> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<EndpointCommand> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<EndpointCommand> findByDevice_IdAndStatusInOrderByPriorityAscIssuedAtAsc(UUID deviceId,
                                                                                  List<CommandStatus> statuses);

    List<EndpointCommand> findByTenantIdAndDevice_IdOrderByIssuedAtDesc(UUID tenantId, UUID deviceId);

    List<EndpointCommand> findByTenantIdOrderByIssuedAtDesc(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select command
            from EndpointCommand command
            where command.device.id = :deviceId
              and command.device.status = :deviceStatus
              and command.visibleAfterAt <= :now
              and (command.expiresAt is null or command.expiresAt > :now)
              and command.attemptCount < command.maxAttempts
              and (
                    command.status = :queuedStatus
                    or (
                        command.status = :deliveredStatus
                        and command.lockedUntil is not null
                        and command.lockedUntil <= :now
                    )
              )
            order by command.priority asc, command.issuedAt asc
            """)
    List<EndpointCommand> findClaimCandidatesForDevice(
            @Param("deviceId") UUID deviceId,
            @Param("deviceStatus") DeviceStatus deviceStatus,
            @Param("queuedStatus") CommandStatus queuedStatus,
            @Param("deliveredStatus") CommandStatus deliveredStatus,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select command
            from EndpointCommand command
            where command.id = :commandId
              and command.device.id = :deviceId
            """)
    Optional<EndpointCommand> findByIdAndDeviceIdForUpdate(@Param("commandId") UUID commandId,
                                                           @Param("deviceId") UUID deviceId);
}
