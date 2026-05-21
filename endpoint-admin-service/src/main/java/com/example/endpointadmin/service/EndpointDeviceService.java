package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentRequest;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class EndpointDeviceService {

    private final EndpointDeviceRepository repository;

    public EndpointDeviceService(EndpointDeviceRepository repository) {
        this.repository = repository;
    }

    public EndpointDevice upsertFromEnrollment(UUID tenantId,
                                               ConsumeEnrollmentRequest request,
                                               Instant now) {
        EndpointDevice device = repository
                .findByTenantIdAndMachineFingerprint(tenantId, request.machineFingerprint())
                .or(() -> repository.findByTenantIdAndHostname(tenantId, request.hostname()))
                .orElseGet(EndpointDevice::new);

        if (device.getId() == null) {
            device.setTenantId(tenantId);
            device.setEnrolledAt(now);
        }
        device.setHostname(request.hostname());
        device.setOsType(request.osType());
        device.setOsVersion(request.osVersion());
        device.setAgentVersion(request.agentVersion());
        device.setMachineFingerprint(request.machineFingerprint());
        device.setDomainName(request.domainName());
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(now);
        if (device.getEnrolledAt() == null) {
            device.setEnrolledAt(now);
        }
        return repository.saveAndFlush(device);
    }

    public List<EndpointDeviceDto> listDevices(UUID tenantId) {
        return repository.findByTenantIdOrderByHostnameAsc(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public EndpointDeviceDto getDevice(UUID tenantId, UUID deviceId) {
        EndpointDevice device = repository.findByTenantIdAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Endpoint device not found."));
        return toDto(device);
    }

    public EndpointDeviceDto toDto(EndpointDevice device) {
        return new EndpointDeviceDto(
                device.getId(),
                device.getTenantId(),
                device.getHostname(),
                device.getDisplayName(),
                device.getOsType(),
                device.getOsVersion(),
                device.getAgentVersion(),
                device.getMachineFingerprint(),
                device.getDomainName(),
                device.getStatus(),
                device.getLastSeenAt(),
                device.getEnrolledAt(),
                device.getCreatedAt(),
                device.getUpdatedAt()
        );
    }
}
