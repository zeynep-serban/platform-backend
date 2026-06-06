package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.dto.v1.admin.UpdateDeviceRolloutRequest;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointDeviceServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EndpointDeviceRepository repository;

    @InjectMocks
    private EndpointDeviceService service;

    @Test
    void updateRolloutAssignmentNormalizesTagsBeforeSaving() {
        EndpointDevice device = new EndpointDevice();
        when(repository.findVisibleToOrgAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(repository.saveAndFlush(any(EndpointDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        EndpointDeviceDto dto = service.updateRolloutAssignment(
                TENANT_ID,
                DEVICE_ID,
                new UpdateDeviceRolloutRequest(
                        DeploymentRing.IT,
                        new LinkedHashSet<>(Set.of(" Pilot ", "it", "IT", "", "finance.ops"))));

        ArgumentCaptor<EndpointDevice> saved = ArgumentCaptor.forClass(EndpointDevice.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDeploymentRing()).isEqualTo(DeploymentRing.IT);
        assertThat(saved.getValue().getDeviceTags()).containsExactlyInAnyOrder("pilot", "it", "finance.ops");
        assertThat(dto.deploymentRing()).isEqualTo(DeploymentRing.IT);
        assertThat(dto.deviceTags()).containsExactlyInAnyOrder("pilot", "it", "finance.ops");
    }

    @Test
    void updateRolloutAssignmentRejectsNonSlugTag() {
        EndpointDevice device = new EndpointDevice();
        when(repository.findVisibleToOrgAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.updateRolloutAssignment(
                TENANT_ID,
                DEVICE_ID,
                new UpdateDeviceRolloutRequest(DeploymentRing.PILOT, Set.of("bad tag"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRolloutAssignmentRejectsMoreThanThirtyTwoTagsAfterNormalization() {
        EndpointDevice device = new EndpointDevice();
        when(repository.findVisibleToOrgAndId(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        Set<String> tags = IntStream.range(0, 33)
                .mapToObj(i -> "tag-" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThatThrownBy(() -> service.updateRolloutAssignment(
                TENANT_ID,
                DEVICE_ID,
                new UpdateDeviceRolloutRequest(DeploymentRing.PILOT, tags)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).saveAndFlush(any());
    }
}
