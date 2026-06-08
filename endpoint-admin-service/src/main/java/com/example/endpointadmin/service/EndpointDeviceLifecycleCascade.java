package com.example.endpointadmin.service;

import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMaintenanceToken;
import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.MaintenanceTokenStatus;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointMaintenanceTokenRepository;
import com.example.endpointadmin.repository.EndpointUninstallRequestRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decommission cascade — invalidates pending agent work so a later reactivate
 * cannot resurrect stale intent (Codex 019ea789 must-fix). Runs inside the
 * decommission transaction ({@code Propagation.MANDATORY}).
 *
 * <p>Three side effects, all org/tenant-scoped to the device:
 * <ol>
 *   <li>cancel non-terminal {@code endpoint_commands}
 *       (QUEUED/DELIVERED/ACKED/RUNNING → CANCELLED + cancelled_at) and clear
 *       their command secrets (CHANGE_LOCAL_PASSWORD one-time passwords) via
 *       {@link EndpointCommandSecretService#clearIfTerminal};</li>
 *   <li>revoke PENDING maintenance tokens (the consume path is otherwise
 *       status-unguarded);</li>
 *   <li>finalize open uninstall requests (state != TERMINAL → TERMINAL with a
 *       {@code cancelled_by_decommission} reason).</li>
 * </ol>
 */
@Component
public class EndpointDeviceLifecycleCascade {

    private static final List<CommandStatus> NON_TERMINAL_COMMAND_STATUSES =
            List.of(CommandStatus.QUEUED, CommandStatus.DELIVERED,
                    CommandStatus.ACKED, CommandStatus.RUNNING);

    /** Counts of cascaded side effects, surfaced in the lifecycle audit row. */
    public record CascadeCounts(int cancelledCommands, int revokedTokens, int finalizedUninstalls) {
        public static CascadeCounts none() {
            return new CascadeCounts(0, 0, 0);
        }
    }

    private final EndpointCommandRepository commandRepository;
    private final EndpointCommandSecretService commandSecretService;
    private final EndpointMaintenanceTokenRepository maintenanceTokenRepository;
    private final EndpointUninstallRequestRepository uninstallRequestRepository;

    public EndpointDeviceLifecycleCascade(
            EndpointCommandRepository commandRepository,
            EndpointCommandSecretService commandSecretService,
            EndpointMaintenanceTokenRepository maintenanceTokenRepository,
            EndpointUninstallRequestRepository uninstallRequestRepository) {
        this.commandRepository = commandRepository;
        this.commandSecretService = commandSecretService;
        this.maintenanceTokenRepository = maintenanceTokenRepository;
        this.uninstallRequestRepository = uninstallRequestRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CascadeCounts onDecommission(UUID tenantId, EndpointDevice device,
                                        String actorSubject, String reason) {
        Instant now = Instant.now();
        UUID deviceId = device.getId();

        // 1. Cancel non-terminal commands + clear their command secrets.
        //    clearIfTerminal is a no-op for non CHANGE_LOCAL_PASSWORD commands
        //    and records its own ENDPOINT_COMMAND_SECRET_CLEARED chain event.
        List<EndpointCommand> active = commandRepository
                .findByDevice_IdAndStatusInOrderByPriorityAscIssuedAtAsc(
                        deviceId, NON_TERMINAL_COMMAND_STATUSES);
        int cancelledCommands = 0;
        for (EndpointCommand command : active) {
            command.setStatus(CommandStatus.CANCELLED);
            command.setCancelledAt(now);
            commandRepository.save(command);
            commandSecretService.clearIfTerminal(command);
            cancelledCommands++;
        }

        // 2. Revoke PENDING maintenance tokens.
        int revokedTokens = 0;
        for (EndpointMaintenanceToken token : maintenanceTokenRepository
                .findByTenantIdAndDevice_IdOrderByCreatedAtDesc(tenantId, deviceId)) {
            if (token.getStatus() == MaintenanceTokenStatus.PENDING) {
                token.setStatus(MaintenanceTokenStatus.REVOKED);
                maintenanceTokenRepository.save(token);
                revokedTokens++;
            }
        }

        // 3. Finalize open uninstall requests.
        int finalizedUninstalls = 0;
        for (EndpointUninstallRequest request : uninstallRequestRepository
                .findByTenantIdAndDeviceIdOrderByCreatedAtDesc(tenantId, deviceId, Pageable.unpaged())) {
            if (request.getState() != UninstallRequestState.TERMINAL) {
                request.setState(UninstallRequestState.TERMINAL);
                request.setStateUpdatedAt(now);
                request.setReason(cancelledByDecommissionReason(reason));
                uninstallRequestRepository.save(request);
                finalizedUninstalls++;
            }
        }

        return new CascadeCounts(cancelledCommands, revokedTokens, finalizedUninstalls);
    }

    private static String cancelledByDecommissionReason(String reason) {
        String prefix = "cancelled_by_decommission";
        return reason == null || reason.isBlank() ? prefix : prefix + ": " + reason;
    }
}
