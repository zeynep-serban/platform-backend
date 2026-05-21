package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.EndpointAuditEventDto;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointAuditService {

    private final EndpointAuditEventRepository repository;

    public EndpointAuditService(EndpointAuditEventRepository repository) {
        this.repository = repository;
    }

    public EndpointAuditEvent record(UUID tenantId,
                                     EndpointDevice device,
                                     EndpointCommand command,
                                     String eventType,
                                     String action,
                                     String performedBySubject,
                                     String correlationId,
                                     Map<String, Object> metadata,
                                     Map<String, Object> beforeState,
                                     Map<String, Object> afterState) {
        EndpointAuditEvent event = new EndpointAuditEvent();
        event.setTenantId(tenantId);
        event.setDevice(device);
        event.setCommand(command);
        event.setEventType(eventType);
        event.setAction(action);
        event.setPerformedBySubject(performedBySubject);
        event.setCorrelationId(correlationId);
        event.setMetadata(metadata);
        event.setBeforeState(beforeState);
        event.setAfterState(afterState);
        return repository.save(event);
    }

    public List<EndpointAuditEventDto> listEvents(UUID tenantId,
                                                  UUID deviceId,
                                                  UUID commandId,
                                                  String eventType,
                                                  int limit) {
        int resolvedLimit = Math.max(1, Math.min(limit, 200));
        String resolvedEventType = trimToNull(eventType);
        return repository.search(tenantId, deviceId, commandId, resolvedEventType, PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    private EndpointAuditEventDto toDto(EndpointAuditEvent event) {
        UUID deviceId = event.getDevice() == null ? null : event.getDevice().getId();
        UUID commandId = event.getCommand() == null ? null : event.getCommand().getId();
        return new EndpointAuditEventDto(
                event.getId(),
                event.getTenantId(),
                deviceId,
                commandId,
                event.getEventType(),
                event.getAction(),
                event.getPerformedBySubject(),
                event.getCorrelationId(),
                event.getMetadata(),
                event.getBeforeState(),
                event.getAfterState(),
                event.getOccurredAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
