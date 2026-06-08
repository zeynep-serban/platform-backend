package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
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

    long countByTenantIdAndCommandTypeAndStatusInAndLockedUntilAfter(UUID tenantId,
                                                                     CommandType commandType,
                                                                     List<CommandStatus> statuses,
                                                                     Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select command
            from EndpointCommand command
            where command.device.id = :deviceId
              and command.device.status = :deviceStatus
              and command.visibleAfterAt <= :now
              and (command.expiresAt is null or command.expiresAt > :now)
              and command.attemptCount < command.maxAttempts
              and command.approvalStatus in (
                    com.example.endpointadmin.model.ApprovalStatus.NOT_REQUIRED,
                    com.example.endpointadmin.model.ApprovalStatus.APPROVED
              )
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

    /** BE-017 — pessimistic-lock load for the dual-control approval decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select command
            from EndpointCommand command
            where command.tenantId = :tenantId
              and command.id = :commandId
            """)
    Optional<EndpointCommand> findByTenantIdAndIdForUpdate(@Param("tenantId") UUID tenantId,
                                                           @Param("commandId") UUID commandId);

    /**
     * #508 — non-terminal {@code SET_DISPLAY_POLICY} command ids for a device,
     * excluding one. Used by {@code DisplayPolicyApprovalListener} at approval
     * time to supersede a prior approved-queued display-policy command so the
     * agent never has two non-terminal display-policy commands to order
     * (per-device single-flight, Codex 019ea92f).
     */
    @Query("""
            select command.id
            from EndpointCommand command
            where command.device.id = :deviceId
              and command.commandType = com.example.endpointadmin.model.CommandType.SET_DISPLAY_POLICY
              and command.id <> :exceptCommandId
              and command.status not in (
                    com.example.endpointadmin.model.CommandStatus.CANCELLED,
                    com.example.endpointadmin.model.CommandStatus.EXPIRED,
                    com.example.endpointadmin.model.CommandStatus.FAILED,
                    com.example.endpointadmin.model.CommandStatus.SUCCEEDED)
            """)
    List<UUID> findActiveDisplayPolicyCommandIdsForDevice(@Param("deviceId") UUID deviceId,
                                                          @Param("exceptCommandId") UUID exceptCommandId);
}
