package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.AuditChainLock;
import com.example.endpointadmin.audit.AuditChainSupport;
import com.example.endpointadmin.dto.v1.admin.EndpointAuditEventDto;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointAuditService {

    private final EndpointAuditEventRepository repository;
    private final AuditChainLock auditChainLock;
    private final Clock clock;

    public EndpointAuditService(EndpointAuditEventRepository repository,
                                AuditChainLock auditChainLock,
                                Clock clock) {
        this.repository = repository;
        this.auditChainLock = auditChainLock;
        this.clock = clock;
    }

    /**
     * Record an audit event with a tenant-scoped tamper-evident hash-chain
     * (BE-016, Codex 019e4f8e + post-impl review absorb).
     *
     * <p>{@code @Transactional(MANDATORY)} — this MUST run inside an existing
     * transaction. The advisory lock is transaction-scoped, so without an outer
     * transaction the lock would release at statement end and the tail-read +
     * insert would lose serialization (Codex P1-2). All current callers are
     * already {@code @Transactional}.
     *
     * <p>Ordering (Codex P1-1 — chain order must be deterministic):
     * <ol>
     *   <li>set every non-chain field + {@code event_hash_alg/version};</li>
     *   <li>acquire the per-tenant {@link AuditChainLock} FIRST — before the
     *       chain-order timestamp is assigned;</li>
     *   <li>read the current chain tail under the lock (legacy pre-BE-016
     *       null-hash rows are skipped, so the first post-deploy row is the
     *       tenant GENESIS);</li>
     *   <li>assign {@code occurredAt} as strictly monotonic per tenant:
     *       {@code max(now, tail.occurredAt + 1µs)} — so chain-write order and
     *       {@code ORDER BY occurred_at} agree even for same-microsecond or
     *       lock-reordered concurrent writers;</li>
     *   <li>compute {@code event_hash = SHA-256(domain || prev || canonical)}
     *       and persist.</li>
     * </ol>
     */
    @Transactional(propagation = Propagation.MANDATORY)
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
        event.setId(UUID.randomUUID());
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
        event.setEventHashAlg(AuditChainSupport.HASH_ALGORITHM);
        event.setEventHashVersion(AuditChainSupport.HASH_VERSION);

        // BE-016: lock the tenant chain BEFORE the chain-order timestamp is
        // assigned, so the timestamp ordering matches the lock-serialized
        // write order (Codex P1-1).
        auditChainLock.lockTenantChain(tenantId);
        EndpointAuditEvent tail = repository
                .findTop1ByTenantIdAndEventHashIsNotNullOrderByOccurredAtDescIdDesc(tenantId)
                .orElse(null);
        String prevHash = tail == null ? null : tail.getEventHash();

        // Strictly monotonic per-tenant occurredAt: never <= the prior tail.
        Instant occurredAt = AuditChainSupport.normalizeTimestamp(clock.instant());
        if (tail != null && tail.getOccurredAt() != null
                && !occurredAt.isAfter(tail.getOccurredAt())) {
            occurredAt = tail.getOccurredAt().plus(1, ChronoUnit.MICROS);
        }
        event.setOccurredAt(occurredAt);

        event.setPrevEventHash(prevHash);
        event.setEventHash(AuditChainSupport.computeEventHash(prevHash, event));

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
