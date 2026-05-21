package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatRequest;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.security.DeviceCredentialException;
import com.example.endpointadmin.security.DeviceCredentialResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointHeartbeatService {

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointHeartbeatRepository heartbeatRepository;
    private final Clock clock;

    public EndpointHeartbeatService(EndpointDeviceRepository deviceRepository,
                                    EndpointHeartbeatRepository heartbeatRepository,
                                    Clock clock) {
        this.deviceRepository = deviceRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.clock = clock;
    }

    @Transactional
    public AgentHeartbeatResponse recordHeartbeat(DeviceCredentialResult principal,
                                                  AgentHeartbeatRequest request,
                                                  String remoteAddress) {
        UUID deviceId = resolveDeviceId(principal);
        Instant now = Instant.now(clock);
        EndpointDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));

        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Endpoint device is decommissioned.");
        }

        String agentVersion = trimToNull(request.agentVersion());
        String osVersion = trimToNull(request.osVersion());
        OsType osType = request.resolvedOsType();

        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(now);
        if (agentVersion != null) {
            device.setAgentVersion(agentVersion);
        }
        if (osVersion != null) {
            device.setOsVersion(osVersion);
        }
        if (osType != null) {
            device.setOsType(osType);
        }

        EndpointHeartbeat heartbeat = new EndpointHeartbeat();
        heartbeat.setTenantId(device.getTenantId());
        heartbeat.setDevice(device);
        heartbeat.setReceivedAt(now);
        heartbeat.setAgentVersion(agentVersion);
        heartbeat.setOsVersion(osVersion);
        heartbeat.setIpAddress(truncate(trimToNull(remoteAddress), 64));
        heartbeat.setPayload(payload(request));

        deviceRepository.saveAndFlush(device);
        heartbeatRepository.saveAndFlush(heartbeat);

        return new AgentHeartbeatResponse(true, device.getId(), device.getStatus(), now);
    }

    private UUID resolveDeviceId(DeviceCredentialResult principal) {
        if (principal == null || trimToNull(principal.deviceId()) == null) {
            throw new DeviceCredentialException("DEVICE_AUTH_REQUIRED", "Device authentication is required.");
        }
        try {
            return UUID.fromString(principal.deviceId());
        } catch (IllegalArgumentException ex) {
            throw new DeviceCredentialException("DEVICE_AUTH_INVALID", "Device authentication is invalid.");
        }
    }

    private Map<String, Object> payload(AgentHeartbeatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "installId", request.installId());
        putIfPresent(payload, "hostname", request.hostname());
        putIfPresent(payload, "osType", request.resolvedOsType() == null ? null : request.resolvedOsType().name());
        putIfPresent(payload, "architecture", request.architecture());
        putIfPresent(payload, "state", request.state());
        putIfPresent(payload, "timestamp", request.timestamp());
        payload.put("capabilities", normalizeCapabilities(request.capabilities()));
        if (request.inventory() != null) {
            payload.put("inventory", request.inventory());
        }
        if (request.localUsers() != null) {
            payload.put("localUsers", request.localUsers());
        }
        if (request.metrics() != null) {
            payload.put("metrics", request.metrics());
        }
        return payload;
    }

    private List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String capability : capabilities) {
            String value = trimToNull(capability);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value instanceof String stringValue) {
            String trimmed = trimToNull(stringValue);
            if (trimmed != null) {
                payload.put(key, trimmed);
            }
            return;
        }
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
