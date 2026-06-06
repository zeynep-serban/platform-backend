package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.dto.v1.admin.UpdateDeviceRolloutRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentRequest;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class EndpointDeviceService {

    private static final Pattern DEVICE_TAG_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final EndpointDeviceRepository repository;

    public EndpointDeviceService(EndpointDeviceRepository repository) {
        this.repository = repository;
    }

    public EndpointDevice upsertFromEnrollment(UUID tenantId,
                                               ConsumeEnrollmentRequest request,
                                               Instant now) {
        // Faz 21.1 PR2b-iv.b2 (Codex 019e8d1d AGREE): canonical effective-org
        // adoption resolver. Fingerprint-first / hostname-fallback order
        // preserved exactly; both lookups now use parenthesized OR pattern
        // (canonical post-PR2b-ii rows + legacy NULL rows both reachable).
        EndpointDevice device = repository
                .findVisibleToOrgAndMachineFingerprint(tenantId, request.machineFingerprint())
                .or(() -> repository.findVisibleToOrgAndHostname(tenantId, request.hostname()))
                .orElseGet(EndpointDevice::new);

        if (device.getId() == null) {
            // Faz 21.1 PR2b-ii canonical org_id write (Codex 019e8cc2 Option A,
            // inline pattern). Set both columns to the same UUID so V30 CHECK
            // (org_id IS NULL OR org_id = tenant_id) passes; the cleanup PR
            // (DROP COLUMN tenant_id) can safely flip the read path.
            device.setTenantId(tenantId);
            device.setOrgId(tenantId);
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
        // Faz 21.1 PR2b-iv.b3 (Codex 019e8d1d AGREE): canonical effective-org
        // listing — admin device list sorted by hostname ASC; accepts both
        // canonical and legacy NULL rows via parenthesized OR.
        return repository.findVisibleToOrgOrderByHostnameAsc(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public EndpointDeviceDto getDevice(UUID tenantId, UUID deviceId) {
        EndpointDevice device = repository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Endpoint device not found."));
        return toDto(device);
    }

    @Transactional
    public EndpointDeviceDto updateRolloutAssignment(UUID tenantId, UUID deviceId,
                                                     UpdateDeviceRolloutRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rollout assignment request is required.");
        }
        EndpointDevice device = repository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Endpoint device not found."));
        DeploymentRing ring = request.deploymentRing() == null ? DeploymentRing.PILOT : request.deploymentRing();
        device.setDeploymentRing(ring);
        device.setDeviceTags(normalizeTags(request.deviceTags()));
        return toDto(repository.saveAndFlush(device));
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
                device.getDeploymentRing(),
                device.getDeviceTags(),
                device.getStatus(),
                device.getLastSeenAt(),
                device.getEnrolledAt(),
                device.getCreatedAt(),
                device.getUpdatedAt()
        );
    }

    private static Set<String> normalizeTags(Set<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTags) {
            String tag = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (tag.isEmpty()) {
                continue;
            }
            if (!DEVICE_TAG_PATTERN.matcher(tag).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Device tags must be lowercase slug values up to 64 characters.");
            }
            normalized.add(tag);
        }
        if (normalized.size() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 32 device tags are allowed.");
        }
        return normalized;
    }
}
