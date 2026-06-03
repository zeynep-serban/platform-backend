package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointUninstallAudit;
import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.model.UninstallResultStatus;
import com.example.endpointadmin.model.UninstallVerification;
import com.example.endpointadmin.repository.EndpointUninstallAuditRepository;
import com.example.endpointadmin.repository.EndpointUninstallRequestRepository;
import com.example.endpointadmin.security.UninstallEvidencePayloadPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AG-028 Phase 2B — terminal-result append-only audit for completed uninstalls
 * (Faz 22.5.6). Destructive-side counterpart to {@link EndpointInstallAuditService}.
 *
 * <p>Writes the {@code endpoint_uninstall_audit} row + BE-016 hash-chain audit
 * event from inside the same transaction as the
 * {@code EndpointAgentCommandService.submitResult} call so a forbidden-payload
 * roll-back also rolls back the audit row. ALSO finalises the request lifecycle
 * by transitioning {@code EndpointUninstallRequest.state} to
 * {@link UninstallRequestState#TERMINAL}.
 *
 * <p>Verification is a DETERMINISTIC function of {@code resultStatus} (a
 * cross-field consistency matrix), NOT an independent read of the agent
 * {@code probeState}. The agent's {@code finalStatus} is itself computed from
 * {@code probeState} + exit code (Phase 2A exit-code decision tree), so deriving
 * verification independently would, under agent drift / wire tampering, produce a
 * semantically contradictory pair — e.g. {@code FAILED_VERIFY_GHOST /
 * ABSENT_VERIFIED} or an unknown-status {@code ABSENT_VERIFIED} (Codex post-impl
 * thread {@code 019e8f9c} REVISE absorb). Forcing verification from the
 * authoritative {@code resultStatus} is lossless in correct operation (each
 * status maps 1:1 to a verification) and fail-closed under drift (never certifies
 * a strong verdict that contradicts the status).
 *
 * <table>
 *   <tr><th>agent finalStatus</th><th>resultStatus</th><th>verification (forced from resultStatus)</th></tr>
 *   <tr><td>SUCCEEDED_VERIFIED</td><td>SUCCEEDED_VERIFIED</td><td>ABSENT_VERIFIED</td></tr>
 *   <tr><td>SKIP_ALREADY_ABSENT</td><td>SKIP_ALREADY_ABSENT</td><td>ABSENT_VERIFIED</td></tr>
 *   <tr><td>PARTIAL_RESIDUE</td><td>PARTIAL_RESIDUE</td><td>RESIDUE_PRESENT</td></tr>
 *   <tr><td>PARTIAL_INCONCLUSIVE</td><td>PARTIAL_INCONCLUSIVE</td><td>VERIFY_INCONCLUSIVE</td></tr>
 *   <tr><td>FAILED_VERIFY_GHOST</td><td>FAILED_VERIFY_GHOST</td><td>PRESENT_VERIFIED</td></tr>
 *   <tr><td>FAILED_EXIT</td><td>FAILED_EXIT</td><td>probeState MATCHED→PRESENT_VERIFIED, else VERIFY_INCONCLUSIVE (clamp: a non-zero exit NEVER reads ABSENT)</td></tr>
 *   <tr><td>FAILED_PRECHECK_INCONCLUSIVE</td><td>FAILED_PRECHECK_INCONCLUSIVE</td><td>VERIFY_INCONCLUSIVE</td></tr>
 *   <tr><td>FAILED_UNSUPPORTED_PLATFORM</td><td>FAILED_UNSUPPORTED_PLATFORM</td><td>NOT_RUN</td></tr>
 *   <tr><td>FAILED_UNSUPPORTED_VERIFICATION</td><td>FAILED_UNSUPPORTED_VERIFICATION</td><td>NOT_RUN</td></tr>
 *   <tr><td>unknown / missing</td><td>{@link #FALLBACK_RESULT_STATUS}</td><td>VERIFY_INCONCLUSIVE (fail-closed)</td></tr>
 * </table>
 *
 * <p>Cross-AI plan-time Codex consensus thread {@code 019e8de2-cf3c-7d80-8a31-823fafcbc3ed}
 * iter-2 AGREE; post-impl consistency-matrix REVISE absorb thread
 * {@code 019e8f9c-2a1b-77e0-a426-4757796c8495}.
 */
@Service
public class EndpointUninstallAuditService {

    /**
     * Fail-closed default when the agent ships a finalStatus that is not
     * in the closed {@link UninstallResultStatus} allow-list. Mirrors the
     * install-side fail-closed contract: an unknown / drifted finalStatus
     * MUST NOT certify {@code ABSENT_VERIFIED}. Distinct from {@code
     * FAILED_PRECHECK_INCONCLUSIVE} (which is a known agent-side decision)
     * — this is for protocol drift, so it should be visibly distinct in the
     * audit view.
     */
    static final UninstallResultStatus FALLBACK_RESULT_STATUS =
            UninstallResultStatus.PARTIAL_INCONCLUSIVE;

    static final String EVENT_TYPE = "ENDPOINT_UNINSTALL_RESULT_RECORDED";
    static final String ACTION = "RECORD_UNINSTALL_RESULT";

    private final EndpointUninstallAuditRepository auditRepository;
    private final EndpointUninstallRequestRepository requestRepository;
    private final UninstallEvidencePayloadPolicy policy;
    private final EndpointAuditService auditService;

    public EndpointUninstallAuditService(
            EndpointUninstallAuditRepository auditRepository,
            EndpointUninstallRequestRepository requestRepository,
            UninstallEvidencePayloadPolicy policy,
            EndpointAuditService auditService) {
        this.auditRepository = auditRepository;
        this.requestRepository = requestRepository;
        this.policy = policy;
        this.auditService = auditService;
    }

    /**
     * Record the terminal uninstall result. MUST run inside the surrounding
     * {@code submitResult} transaction so a forbidden-payload roll-back also
     * rolls back the audit row + the request-state transition.
     *
     * @param command         the UNINSTALL_SOFTWARE command
     * @param result          the persisted {@link EndpointCommandResult}
     *                        (redacted payload is in
     *                        {@code result.resultPayload.details})
     * @param request         the original agent submit request
     * @param redactedDetails the SAME map written to the result row's
     *                        {@code details}; pass {@code null} if the agent
     *                        shipped no details (the audit row still records
     *                        the terminal status with a fail-closed verdict)
     * @param now             wall clock (parity with the caller)
     * @return the persisted audit row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EndpointUninstallAudit recordUninstallResult(
            EndpointCommand command,
            EndpointCommandResult result,
            AgentCommandResultRequest request,
            Map<String, Object> redactedDetails,
            Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(request, "request");
        if (command.getCommandType() != CommandType.UNINSTALL_SOFTWARE) {
            throw new IllegalStateException(
                    "recordUninstallResult invoked for non-UNINSTALL_SOFTWARE command: "
                            + command.getCommandType());
        }

        Map<String, Object> payload = command.getPayload() == null
                ? Map.of() : command.getPayload();
        UUID requestId = parseUuid(payload.get("requestId"));
        if (requestId == null) {
            throw new IllegalStateException(
                    "UNINSTALL_SOFTWARE command is missing requestId in payload (commandId="
                            + command.getId() + ")");
        }
        UUID catalogItemId = parseUuid(payload.get("catalogItemUuid"));
        if (catalogItemId == null) {
            throw new IllegalStateException(
                    "UNINSTALL_SOFTWARE command is missing catalogItemUuid in payload (commandId="
                            + command.getId() + ")");
        }

        UUID tenantId = command.getTenantId();
        EndpointUninstallRequest req = requestRepository
                .findByTenantIdAndId(tenantId, requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "UNINSTALL_SOFTWARE command references missing request "
                                + "(commandId=" + command.getId()
                                + ", requestId=" + requestId + ")"));

        Map<String, Object> safeRedacted = redactedDetails == null
                ? new LinkedHashMap<>() : redactedDetails;

        Map<String, Object> uninstall = asMap(safeRedacted.get("uninstall"));
        String finalStatusWire = uninstall == null
                ? null : asString(uninstall.get("finalStatus"));

        UninstallResultStatus resultStatus = mapResultStatus(finalStatusWire);
        UninstallVerification verification =
                resolveVerification(resultStatus, safeRedacted);

        Map<String, Object> detectionEvidence = extractDetectionEvidence(uninstall);

        EndpointUninstallAudit audit = new EndpointUninstallAudit();
        audit.setRequestId(req.getId());
        audit.setTenantId(tenantId);
        audit.setDeviceId(command.getDevice().getId());
        audit.setCatalogItemId(catalogItemId);
        audit.setCommandId(command.getId());
        audit.setResultStatus(resultStatus);
        audit.setVerification(verification);
        audit.setExitCode(request.exitCode());
        audit.setReportedAt(now);
        audit.setRedactedPayload(new LinkedHashMap<>(safeRedacted));
        audit.setDetectionEvidence(detectionEvidence);

        EndpointUninstallAudit saved = auditRepository.save(audit);

        // Finalise the request lifecycle (PENDING_APPROVAL → … → TERMINAL).
        // V32 CHECK enforces the closed state allow-list; @PreUpdate stamps
        // state_updated_at.
        if (req.getState() != UninstallRequestState.TERMINAL) {
            req.setState(UninstallRequestState.TERMINAL);
            req.setStateUpdatedAt(now);
            requestRepository.save(req);
        }

        auditService.record(
                tenantId,
                command.getDevice(),
                command,
                EVENT_TYPE,
                ACTION,
                command.getIssuedBySubject(),
                command.getIdempotencyKey(),
                buildAuditMetadata(saved, finalStatusWire),
                null,
                Map.of(
                        "auditId", saved.getId().toString(),
                        "resultStatus", saved.getResultStatus().name(),
                        "verification", saved.getVerification().name(),
                        "requestState", UninstallRequestState.TERMINAL.name()));

        return saved;
    }

    // ────────────────────────────────────────────────────────────────
    // Mapping helpers

    /**
     * Map the agent {@code finalStatus} wire literal onto the closed
     * {@link UninstallResultStatus} allow-list. Unknown / null values fall
     * to {@link #FALLBACK_RESULT_STATUS} so a future contract drift cannot
     * sneak through the V32 CHECK constraint.
     */
    static UninstallResultStatus mapResultStatus(String finalStatusWire) {
        if (finalStatusWire == null) {
            return FALLBACK_RESULT_STATUS;
        }
        try {
            return UninstallResultStatus.valueOf(
                    finalStatusWire.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return FALLBACK_RESULT_STATUS;
        }
    }

    /**
     * Resolve {@link UninstallVerification} as a DETERMINISTIC function of the
     * authoritative {@code resultStatus} (cross-field consistency matrix), NOT
     * an independent read of {@code probeState} (Codex post-impl thread
     * {@code 019e8f9c} REVISE absorb).
     *
     * <p>Rationale: the agent's {@code finalStatus} (→ {@code resultStatus}) is
     * itself derived from {@code probeState} + exit code on the agent side, so
     * the two signals cannot legitimately disagree. Reading {@code probeState}
     * independently would, under drift / agent-bug / wire tampering, emit a
     * contradictory audit pair — e.g. {@code FAILED_VERIFY_GHOST /
     * ABSENT_VERIFIED} (status says "still present", verification says "gone") or
     * an unknown-status {@code ABSENT_VERIFIED}. Forcing verification from
     * {@code resultStatus} is lossless in correct operation and fail-closed under
     * drift: a destructive-op audit NEVER certifies {@code ABSENT_VERIFIED}
     * unless the agent authoritatively classified the operation as absent
     * (SUCCEEDED_VERIFIED / SKIP_ALREADY_ABSENT).
     *
     * <p>{@code FAILED_EXIT} is the only status that delegates to
     * {@link UninstallEvidencePayloadPolicy#deriveVerification(Map)}, because a
     * non-zero exit legitimately carries two distinguishable sub-states — the
     * package was still detected ({@code PRESENT_VERIFIED}) vs. post-verify could
     * not tell ({@code VERIFY_INCONCLUSIVE}). The result is CLAMPED: anything
     * that would read as absence / residue / not-run is downgraded to
     * {@code VERIFY_INCONCLUSIVE}, so a failed exit can never be certified as
     * {@code ABSENT_VERIFIED}.
     */
    UninstallVerification resolveVerification(
            UninstallResultStatus resultStatus,
            Map<String, Object> redactedDetails) {
        switch (resultStatus) {
            case SUCCEEDED_VERIFIED:
            case SKIP_ALREADY_ABSENT:
                return UninstallVerification.ABSENT_VERIFIED;
            case FAILED_VERIFY_GHOST:
                return UninstallVerification.PRESENT_VERIFIED;
            case PARTIAL_RESIDUE:
                return UninstallVerification.RESIDUE_PRESENT;
            case PARTIAL_INCONCLUSIVE:
            case FAILED_PRECHECK_INCONCLUSIVE:
                return UninstallVerification.VERIFY_INCONCLUSIVE;
            case FAILED_UNSUPPORTED_PLATFORM:
            case FAILED_UNSUPPORTED_VERIFICATION:
                return UninstallVerification.NOT_RUN;
            case FAILED_EXIT:
                // Post-verify ran but could not confirm ABSENT. probeState
                // distinguishes "still present" from "could not tell". Clamp
                // anything that would certify absence / residue / not-run to
                // the fail-closed VERIFY_INCONCLUSIVE — a non-zero exit must
                // NEVER read as ABSENT_VERIFIED.
                UninstallVerification derived = policy.deriveVerification(redactedDetails);
                return derived == UninstallVerification.PRESENT_VERIFIED
                        ? UninstallVerification.PRESENT_VERIFIED
                        : UninstallVerification.VERIFY_INCONCLUSIVE;
            default:
                // Unreachable today (all enum values + FALLBACK covered), but a
                // defensive fail-closed for a future UninstallResultStatus add.
                return UninstallVerification.VERIFY_INCONCLUSIVE;
        }
    }

    /**
     * Project the {@code uninstall.safeEvidence} sub-tree as the audit's
     * {@code detectionEvidence} JSONB column. The policy's
     * {@code redactUninstall} already projected {@code safeEvidence} to the
     * scalar allow-list ({@code ruleType}, {@code matchedPackageId},
     * {@code matchedVersion}, {@code matchedProductCode},
     * {@code matchedDisplayName}, {@code matchedPublisher},
     * {@code candidateCount}, {@code absentReason}) so we just copy here.
     */
    private static Map<String, Object> extractDetectionEvidence(
            Map<String, Object> uninstall) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (uninstall == null) {
            return out;
        }
        Map<String, Object> safeEvidence = asMap(uninstall.get("safeEvidence"));
        if (safeEvidence != null && !safeEvidence.isEmpty()) {
            out.putAll(safeEvidence);
        }
        // Mirror the install audit's `postVerification` semantics: surface the
        // probeState + authority hints alongside the safe-evidence scalars so a
        // single column drives the UI without re-reading redactedPayload.
        Object probeState = uninstall.get("probeState");
        if (probeState != null) {
            out.put("probeState", probeState);
        }
        Object authority = uninstall.get("authority");
        if (authority != null) {
            out.put("authority", authority);
        }
        return out;
    }

    private static Map<String, Object> buildAuditMetadata(
            EndpointUninstallAudit saved, String finalStatusWire) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("auditId", saved.getId().toString());
        meta.put("requestId", saved.getRequestId().toString());
        meta.put("catalogItemId", saved.getCatalogItemId().toString());
        meta.put("resultStatus", saved.getResultStatus().name());
        meta.put("verification", saved.getVerification().name());
        if (saved.getExitCode() != null) {
            meta.put("exitCode", saved.getExitCode());
        }
        if (finalStatusWire != null) {
            meta.put("agentFinalStatus", finalStatusWire);
        }
        return meta;
    }

    // ────────────────────────────────────────────────────────────────
    // Coercion helpers (parity with EndpointInstallAuditService)

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

    /**
     * Predicate used by {@link EndpointAgentCommandService} to decide whether
     * to invoke the audit recorder. Mirrors the install-side branch.
     */
    public static boolean isTerminalResult(CommandResultStatus status) {
        return status == CommandResultStatus.SUCCEEDED
                || status == CommandResultStatus.FAILED
                || status == CommandResultStatus.PARTIAL
                || status == CommandResultStatus.UNSUPPORTED;
    }
}
