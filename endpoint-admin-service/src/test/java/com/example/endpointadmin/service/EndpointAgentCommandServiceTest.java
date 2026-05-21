package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.DeviceCredentialResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TimeConfig.class, EndpointAgentCommandService.class})
class EndpointAgentCommandServiceTest {

    @Autowired
    private EndpointAgentCommandService commandService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Test
    void claimNextClaimsOldestVisibleQueuedCommandForAuthenticatedDevice() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-1", 10));
        commandRepository.saveAndFlush(command(device, "cmd-2", 20));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(device));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().commandId()).isEqualTo(command.getId());
        assertThat(response.orElseThrow().type()).isEqualTo(CommandType.COLLECT_INVENTORY);
        assertThat(response.orElseThrow().attemptNumber()).isEqualTo(1);
        assertThat(response.orElseThrow().claimId()).isNotBlank();
        assertThat(response.orElseThrow().reason()).isEqualTo("inventory refresh");
        assertThat(response.orElseThrow().claimExpiresAt()).isNotNull();

        EndpointCommand updated = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommandStatus.DELIVERED);
        assertThat(updated.getLockedBy()).isEqualTo(response.orElseThrow().claimId());
        assertThat(updated.getLockedUntil()).isEqualTo(response.orElseThrow().claimExpiresAt());
        assertThat(updated.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void claimNextDoesNotClaimAnotherDevicesCommand() {
        EndpointDevice first = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-002"));
        commandRepository.saveAndFlush(command(second, "cmd-other", 10));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(first));

        assertThat(response).isEmpty();
    }

    @Test
    void submitResultStoresResultAndFinalizesCommand() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-result", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();
        AgentCommandResultRequest request = resultRequest(
                claimed.claimId(),
                claimed.attemptNumber(),
                CommandResultStatus.SUCCEEDED
        );

        commandService.submitResult(principal(device), command.getId(), request);
        commandService.submitResult(principal(device), command.getId(), request);

        EndpointCommand updated = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getLockedBy()).isNull();
        assertThat(updated.getLockedUntil()).isNull();

        EndpointCommandResult result = resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(result.getResultPayload())
                .containsEntry("summary", "done")
                .containsEntry("claimId", claimed.claimId())
                .containsEntry("attemptNumber", claimed.attemptNumber())
                .containsKey("details");
    }

    @Test
    void submitResultRejectsWrongClaim() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-wrong-claim", 10));
        commandService.claimNext(principal(device)).orElseThrow();

        assertThatThrownBy(() -> commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequest("wrong-claim", 1, CommandResultStatus.FAILED)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Command claim is not valid.");
    }

    private DeviceCredentialResult principal(EndpointDevice device) {
        return new DeviceCredentialResult(device.getId().toString(), UUID.randomUUID().toString(), Instant.now());
    }

    private EndpointDevice device(DeviceStatus status, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(UUID.randomUUID());
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.2.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(status);
        device.setLastSeenAt(Instant.now());
        return device;
    }

    private EndpointCommand command(EndpointDevice device, String idempotencyKey, int priority) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(device.getTenantId());
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setPayload(Map.of("reason", "inventory refresh", "requestedDetail", "basic"));
        command.setPriority(priority);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setIssuedBySubject("admin@example.com");
        command.setVisibleAfterAt(Instant.now().minusSeconds(60));
        command.setIssuedAt(Instant.now().minusSeconds(120));
        return command;
    }

    private AgentCommandResultRequest resultRequest(String claimId,
                                                    int attemptNumber,
                                                    CommandResultStatus status) {
        return new AgentCommandResultRequest(
                claimId,
                attemptNumber,
                status,
                "done",
                Map.of("inventory", Map.of("hostname", "PC-001")),
                null,
                null,
                0,
                Instant.now().minusSeconds(5),
                Instant.now()
        );
    }
}
