package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.service.compliance.EndpointInstallAuditRecordedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-021 — write the {@code endpoint_install_audit} row + BE-016
 * hash-chain audit event from inside the same transaction as the
 * {@code EndpointAgentCommandService.submitResult} call. Publishes
 * {@link EndpointInstallAuditRecordedEvent} so the BE-023 compliance
 * evaluator runs AFTER_COMMIT (Codex 019e6dfb iter-3 P0-3 + P2-1
 * absorb).
 */
@Service
public class EndpointInstallAuditService {

    private final EndpointInstallAuditRepository installAuditRepository;
    private final EndpointAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public EndpointInstallAuditService(
            EndpointInstallAuditRepository installAuditRepository,
            EndpointAuditService auditService,
            ApplicationEventPublisher eventPublisher) {
        this.installAuditRepository = installAuditRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Record the terminal install result. MUST run inside the
     * surrounding {@code submitResult} transaction so a forbidden
     * payload roll-back also rolls back the audit row.
     *
     * @param command            the INSTALL_SOFTWARE command
     * @param result             the persisted {@link EndpointCommandResult}
     *                           (the redacted payload is already in
     *                           {@code result.resultPayload.details})
     * @param request            the original agent submit request
     * @param redactedDetails    the SAME map written to the result row's
     *                           {@code details}; pass {@code null} if the
     *                           agent shipped no details (the audit row
     *                           still records the terminal status).
     * @param now                wall clock (parity with the caller)
     * @return the persisted audit row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EndpointInstallAudit recordInstallResult(
            EndpointCommand command,
            EndpointCommandResult result,
            AgentCommandResultRequest request,
            Map<String, Object> redactedDetails,
            Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(result, "result");
        if (command.getCommandType() != CommandType.INSTALL_SOFTWARE) {
            throw new IllegalStateException(
                    "recordInstallResult invoked for non-INSTALL_SOFTWARE command: "
                            + command.getCommandType());
        }

        Map<String, Object> payload = command.getPayload() == null ? Map.of() : command.getPayload();
        UUID catalogItemId = parseUuid(payload.get("catalogItemUuid"));
        if (catalogItemId == null) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command is missing catalogItemUuid in payload (commandId="
                            + command.getId() + ")");
        }
        String catalogPackageId = asString(payload.get("catalogPackageId"));
        if (catalogPackageId == null) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command is missing catalogPackageId in payload (commandId="
                            + command.getId() + ")");
        }
        Long catalogRowVersion = asLong(payload.get("catalogRowVersion"));
        if (catalogRowVersion == null) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command is missing catalogRowVersion in payload (commandId="
                            + command.getId() + ")");
        }
        // Codex iter-4 P1-3: preflight snapshot fields are written by
        // the dedicated install path; an absent / invalid value here is
        // a programmatic bug, not an agent issue. Fail closed so the
        // surrounding submitResult transaction rolls back rather than
        // persisting an audit row that overstates the preflight outcome.
        InstallPreflightDecisionRecorded preflightDecision =
                requirePreflightDecision(payload.get("preflightDecision"), command.getId());
        Instant preflightDecisionAt =
                requireInstant(payload.get("preflightDecisionAt"), command.getId());
        List<String> preflightWarnCodes = asStringList(payload.get("preflightWarnCodes"));

        Map<String, Object> safeRedacted = redactedDetails == null
                ? new HashMap<>() : redactedDetails;
        DetectionReadout readout = extractDetection(safeRedacted);

        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(command.getTenantId());
        audit.setDeviceId(command.getDevice().getId());
        audit.setCommandId(command.getId());
        audit.setCatalogItemId(catalogItemId);
        audit.setCatalogPackageId(catalogPackageId);
        audit.setCatalogRowVersion(catalogRowVersion);
        audit.setPreflightDecision(preflightDecision);
        audit.setPreflightDecisionAt(preflightDecisionAt);
        audit.setPreflightWarnCodes(preflightWarnCodes);
        audit.setActorSubject(command.getIssuedBySubject());
        audit.setApprovalSubject(null);
        audit.setResultStatus(request.status() == null ? CommandResultStatus.FAILED : request.status());
        audit.setExitCode(request.exitCode());
        audit.setReportedAt(now);
        audit.setStartedAt(request.startedAt());
        audit.setFinishedAt(request.finishedAt() == null ? now : request.finishedAt());
        audit.setPostVerification(readout.postVerification());
        audit.setDetectedPackageId(readout.detectedPackageId());
        audit.setDetectedVersion(readout.detectedVersion());
        audit.setPostVerificationEvidence(readout.postVerificationEvidence());
        audit.setRedactedPayload(safeRedacted);

        EndpointInstallAudit saved = installAuditRepository.save(audit);

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("auditId", saved.getId().toString());
        auditMetadata.put("catalogItemId", saved.getCatalogItemId().toString());
        auditMetadata.put("catalogPackageId", saved.getCatalogPackageId());
        auditMetadata.put("preflightDecision", saved.getPreflightDecision().name());
        auditMetadata.put("resultStatus", saved.getResultStatus().name());
        auditMetadata.put("postVerification", saved.getPostVerification().name());
        if (saved.getDetectedVersion() != null) {
            auditMetadata.put("detectedVersion", saved.getDetectedVersion());
        }
        if (saved.getExitCode() != null) {
            auditMetadata.put("exitCode", saved.getExitCode());
        }
        auditService.record(
                command.getTenantId(),
                command.getDevice(),
                command,
                "ENDPOINT_INSTALL_RESULT_RECORDED",
                "RECORD_INSTALL_RESULT",
                command.getIssuedBySubject(),
                command.getIdempotencyKey(),
                auditMetadata,
                null,
                Map.of("auditId", saved.getId().toString(),
                        "resultStatus", saved.getResultStatus().name()));

        eventPublisher.publishEvent(new EndpointInstallAuditRecordedEvent(
                saved.getTenantId(),
                saved.getDeviceId(),
                saved.getId(),
                saved.getReportedAt()));

        return saved;
    }

    private static DetectionReadout extractDetection(Map<String, Object> redactedDetails) {
        // BE-028: the agent ships the AG-027 InstallResult under
        // `details.install` (COMMAND-CONTRACT §11.2). The post-verification —
        // and the detected package/version (postVerification.matchedPackageId /
        // matchedVersion; the contract carries no separate top-level
        // `detection`) — live there. Reading the old flat `details.detection` /
        // `details.postVerification` always missed and recorded UNKNOWN.
        Map<String, Object> install = asMap(redactedDetails.get("install"));
        Map<String, Object> postVerification = install == null
                ? null : asMap(install.get("postVerification"));
        Map<String, Object> evidence = postVerification == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(postVerification);
        InstallPostVerification verdict = parseVerdict(install, postVerification);
        String detectedPackageId = postVerification == null
                ? null : asString(postVerification.get("matchedPackageId"));
        String detectedVersion = postVerification == null
                ? null : asString(postVerification.get("matchedVersion"));
        return new DetectionReadout(verdict, detectedPackageId, detectedVersion, evidence);
    }

    private record DetectionReadout(
            InstallPostVerification postVerification,
            String detectedPackageId,
            String detectedVersion,
            Map<String, Object> postVerificationEvidence) {
    }

    // ────────────────────────────────────────────────────────────────
    // Coercion helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String asString(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return String.valueOf(node);
    }

    private static Long asLong(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(node).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static UUID parseUuid(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof UUID u) {
            return u;
        }
        try {
            return UUID.fromString(String.valueOf(node).trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Instant parseInstant(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Instant i) {
            return i;
        }
        try {
            return Instant.parse(String.valueOf(node).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static InstallPreflightDecisionRecorded requirePreflightDecision(Object node, UUID commandId) {
        if (node == null) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command is missing preflightDecision in payload (commandId="
                            + commandId + ")");
        }
        try {
            return InstallPreflightDecisionRecorded.valueOf(
                    String.valueOf(node).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command preflightDecision is not PASS|WARN (commandId="
                            + commandId + ", value=" + node + ")");
        }
    }

    private static Instant requireInstant(Object node, UUID commandId) {
        Instant parsed = parseInstant(node);
        if (parsed == null) {
            throw new IllegalStateException(
                    "INSTALL_SOFTWARE command is missing/invalid preflightDecisionAt in payload (commandId="
                            + commandId + ", value=" + node + ")");
        }
        return parsed;
    }

    /**
     * BE-028 (Codex 019e7f93 #1): derive the backend post-verification verdict
     * from the install wrapper. The agent's {@code PostVerificationResult}
     * (install_winget.go) ships ONLY a boolean {@code satisfied} that is NOT
     * {@code omitempty}: on every early-failure path where post-verify never ran
     * (FAILED_INSTALL / FAILED_EGRESS / FAILED_TIMEOUT / FAILED_UNSUPPORTED_* /
     * FAILED_INTERNAL) the wire still carries {@code satisfied=false} (the zero
     * value). Mapping that blindly to UNSATISFIED would fabricate an
     * authoritative detection denial. The reliability decision is carried by
     * {@code finalStatus}: an authoritative miss surfaces as
     * {@code FAILED_VERIFICATION}.
     *
     * <ul>
     *   <li>{@code status} present (forward-compat 3-way) → mapped directly</li>
     *   <li>{@code satisfied=true} → SATISFIED</li>
     *   <li>{@code satisfied=false} + {@code finalStatus=FAILED_VERIFICATION}
     *       → UNSATISFIED (genuine post-verify negative — e.g. a reliable
     *       REGISTRY_UNINSTALL miss)</li>
     *   <li>{@code satisfied=false} otherwise (post-verify never ran /
     *       confirm-only miss under a SUCCEEDED) → UNKNOWN</li>
     *   <li>malformed / absent {@code satisfied} → UNKNOWN</li>
     * </ul>
     */
    private static InstallPostVerification parseVerdict(
            Map<String, Object> install, Map<String, Object> postVerification) {
        if (postVerification == null) {
            return InstallPostVerification.UNKNOWN;
        }
        // Forward-compat: a future agent may emit a 3-way `status`. Prefer it.
        // (The current agent contract does not ship `status`; the live verdict
        // resolves through the `satisfied` branch below.)
        Object statusNode = postVerification.get("status");
        if (statusNode != null) {
            return switch (String.valueOf(statusNode).trim().toUpperCase(Locale.ROOT)) {
                case "SATISFIED" -> InstallPostVerification.SATISFIED;
                case "UNSATISFIED", "NOT_SATISFIED" -> InstallPostVerification.UNSATISFIED;
                case "INCONCLUSIVE", "UNKNOWN" -> InstallPostVerification.UNKNOWN;
                default -> InstallPostVerification.UNKNOWN;
            };
        }
        Boolean satisfied = asBoolean(postVerification.get("satisfied"));
        if (satisfied == null) {
            return InstallPostVerification.UNKNOWN;   // malformed / absent
        }
        if (satisfied) {
            return InstallPostVerification.SATISFIED;
        }
        String finalStatus = install == null ? null : asString(install.get("finalStatus"));
        if ("FAILED_VERIFICATION".equalsIgnoreCase(finalStatus)) {
            return InstallPostVerification.UNSATISFIED;   // authoritative negative
        }
        return InstallPostVerification.UNKNOWN;           // post-verify never ran
    }

    /** Strict tri-state boolean parse: {@code null} on malformed/absent. */
    private static Boolean asBoolean(Object node) {
        if (node instanceof Boolean b) {
            return b;
        }
        if (node instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(t)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static List<String> asStringList(Object node) {
        if (node == null) {
            return new ArrayList<>();
        }
        if (node instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object e : iterable) {
                String s = asString(e);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }
        String s = asString(node);
        if (s == null) {
            return new ArrayList<>();
        }
        List<String> single = new ArrayList<>(1);
        single.add(s);
        return single;
    }

    // ────────────────────────────────────────────────────────────────
    // Read paths consumed by REST + compliance evaluator

    @Transactional(readOnly = true)
    public Optional<EndpointInstallAudit> findById(UUID tenantId, UUID auditId) {
        return installAuditRepository.findByTenantIdAndId(tenantId, auditId);
    }
}
