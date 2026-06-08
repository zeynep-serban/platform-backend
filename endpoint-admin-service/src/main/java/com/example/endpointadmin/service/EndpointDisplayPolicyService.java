package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyResponse;
import com.example.endpointadmin.dto.v1.admin.ClearDisplayPolicyRequest;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.WallpaperStyle;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.DisplayPolicyValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * #508 slice-2b — dedicated dispatch service for the Endpoint Display Policy
 * surface (screensaver + wallpaper Group-Policy enforcement).
 *
 * <p><b>Maker-checker model (Codex 019ea911):</b> a PUT (ENFORCE) / DELETE
 * (CLEAR) writes ONLY an append-only revision + a PENDING
 * {@code SET_DISPLAY_POLICY} maker-checker command (issuedBy = proposer). The
 * EXISTING dual-control approve surface
 * ({@code EndpointAdminCommandService.approveCommand}) is the checker (approver
 * ≠ issuer). The current desired-state row is promoted from the backing
 * revision ONLY on APPROVE, by {@code DisplayPolicyApprovalListener} — so a
 * REJECTED proposal never becomes current truth. This service therefore NEVER
 * writes {@code endpoint_display_policies}.
 *
 * <p><b>Idempotency / one-open-proposal (Codex must-fix #1/#4):</b> all per-
 * device mutations serialise on the device write-lock
 * ({@code EndpointDeviceWriteGuard.loadActiveForUpdate}). The dedup key is the
 * device's OPEN proposal (latest revision with a non-terminal backing command),
 * NOT the current row: an identical open proposal replays; a different one is
 * rejected 409; a terminal/rejected prior proposal never blocks a fresh one.
 *
 * <p><b>Capability guard (Codex must-fix #2):</b> the heartbeat-freshness +
 * {@code SET_DISPLAY_POLICY}-capability check at PUT/DELETE time is
 * <em>best-effort early operator feedback only</em>. Dispatch happens at the
 * delegated generic approve, and the agent claim query does NOT filter
 * capability — an incapable agent that claims the command fails closed at apply
 * time (reports UNSUPPORTED). It is not an authoritative dispatch gate.
 */
@Service
public class EndpointDisplayPolicyService {

    static final String EVENT_ENFORCE_PROPOSED = "ENDPOINT_DISPLAY_POLICY_ENFORCE_PROPOSED";
    static final String EVENT_CLEAR_PROPOSED = "ENDPOINT_DISPLAY_POLICY_CLEAR_PROPOSED";
    private static final String ACTION_ENFORCE = "PROPOSE_ENDPOINT_DISPLAY_POLICY_ENFORCE";
    private static final String ACTION_CLEAR = "PROPOSE_ENDPOINT_DISPLAY_POLICY_CLEAR";

    /** Heartbeat payload key under which the agent advertises capabilities. */
    private static final String HEARTBEAT_CAPABILITIES_KEY = "capabilities";

    private final EndpointDisplayPolicyRepository policyRepository;
    private final EndpointDisplayPolicyRevisionRepository revisionRepository;
    private final EndpointCommandRepository commandRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointHeartbeatRepository heartbeatRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    private final boolean featureEnabled;
    private final Duration heartbeatFreshnessTtl;
    private final String requiredCapability;

    public EndpointDisplayPolicyService(
            EndpointDisplayPolicyRepository policyRepository,
            EndpointDisplayPolicyRevisionRepository revisionRepository,
            EndpointCommandRepository commandRepository,
            EndpointDeviceRepository deviceRepository,
            EndpointHeartbeatRepository heartbeatRepository,
            EndpointAuditService auditService,
            Clock clock,
            @Value("${endpoint-admin.display-policy.enabled:false}") boolean featureEnabled,
            @Value("${endpoint-admin.display-policy.heartbeat-freshness-ttl:PT5M}")
                    Duration heartbeatFreshnessTtl,
            @Value("${endpoint-admin.display-policy.required-capability:SET_DISPLAY_POLICY}")
                    String requiredCapability) {
        this.policyRepository = policyRepository;
        this.revisionRepository = revisionRepository;
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.featureEnabled = featureEnabled;
        this.heartbeatFreshnessTtl = heartbeatFreshnessTtl == null
                ? Duration.ofMinutes(5) : heartbeatFreshnessTtl;
        this.requiredCapability = requiredCapability == null || requiredCapability.isBlank()
                ? "SET_DISPLAY_POLICY" : requiredCapability;
    }

    // ─────────────────────────────────────────────────────────────
    // ENFORCE (PUT)

    @Transactional
    public AdminDisplayPolicyResponse enforce(AdminTenantContext context,
                                              UUID deviceId,
                                              SetDisplayPolicyRequest request) {
        assertFeatureEnabled();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Display policy request body is required.");
        }
        if (request.operation() != DisplayPolicyOperation.ENFORCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PUT requires operation=ENFORCE; use DELETE to clear a managed policy.");
        }
        // Fail-closed value validation (slice-2a) → 400.
        try {
            DisplayPolicyValidator.validate(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        UUID tenantId = context.tenantId();
        String subject = resolveSubject(context);
        // Serialise all per-device policy mutations + fail-close decommissioned.
        EndpointDevice device =
                EndpointDeviceWriteGuard.loadActiveForUpdate(deviceRepository, tenantId, deviceId);
        Instant now = Instant.now(clock);

        // Build the (in-memory) revision so its persisted values drive the hash.
        EndpointDisplayPolicyRevision revision = buildEnforceRevision(
                tenantId, deviceId, request, subject, now);
        String hash = revision.getPolicyHashSha256();

        // Dedup against the device's OPEN proposal (not the current row).
        Optional<EndpointDisplayPolicyRevision> open = findOpenProposal(tenantId, deviceId);
        if (open.isPresent()) {
            EndpointDisplayPolicyRevision openRev = open.get();
            if (openRev.getOperation() == DisplayPolicyOperation.ENFORCE
                    && hash.equals(openRev.getPolicyHashSha256())) {
                return replay(deviceId, tenantId, openRev);
            }
            // A different op/hash: block ONLY while the open proposal is still
            // awaiting maker-checker approval (PENDING) — two pending approvals
            // for one device are ambiguous. An already-APPROVED proposal is the
            // enacted current truth; this new operation is a fresh maker-checker
            // proposal and DOES NOT cancel anything here. Per-device single-flight
            // is enforced at APPROVAL time by DisplayPolicyApprovalListener (it
            // supersedes the prior approved-queued command, or 409s if it is
            // mid-apply). Doing it here would let an unapproved/rejected proposal
            // suppress delivery of the currently-approved desired state
            // (Codex 019ea92f).
            if (isAwaitingApproval(tenantId, openRev)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A different display-policy proposal is pending approval for this device; "
                                + "approve or reject it first.");
            }
        }

        // No open proposal → idempotent no-op if the already-approved current
        // state is byte-identical (safe: current reflects approved truth only).
        EndpointDisplayPolicy current =
                policyRepository.findByTenantIdAndDeviceId(tenantId, deviceId).orElse(null);
        if (current != null
                && current.getOperation() == DisplayPolicyOperation.ENFORCE
                && current.getClearedAt() == null
                && hash.equals(current.getPolicyHashSha256())) {
            return AdminDisplayPolicyResponse.of(deviceId, current, null, null);
        }

        // Best-effort early operator feedback (NOT an authoritative dispatch
        // gate) — only when we are about to create a NEW proposal, so a replay /
        // idempotent no-op never fails on a transiently-stale agent.
        assertHeartbeatFreshAndCapable(device, now);

        EndpointCommand command = persistProposal(tenantId, device, revision, subject, now,
                buildEnforcePayload(revision));

        auditService.record(tenantId, device, command, EVENT_ENFORCE_PROPOSED, ACTION_ENFORCE,
                subject, command.getIdempotencyKey(),
                proposalAuditMetadata(revision, command), null, stateSnapshot(revision, command));

        return AdminDisplayPolicyResponse.of(deviceId, current, revision, command);
    }

    // ─────────────────────────────────────────────────────────────
    // CLEAR (DELETE)

    @Transactional
    public AdminDisplayPolicyResponse clear(AdminTenantContext context,
                                            UUID deviceId,
                                            ClearDisplayPolicyRequest request) {
        assertFeatureEnabled();
        String reason = request == null ? null : trimToNull(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason is required to clear a managed display policy.");
        }
        if (reason.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason must be at most 512 chars.");
        }

        UUID tenantId = context.tenantId();
        String subject = resolveSubject(context);
        EndpointDevice device =
                EndpointDeviceWriteGuard.loadActiveForUpdate(deviceRepository, tenantId, deviceId);
        Instant now = Instant.now(clock);

        // Open-proposal dedup first.
        Optional<EndpointDisplayPolicyRevision> open = findOpenProposal(tenantId, deviceId);
        if (open.isPresent()) {
            EndpointDisplayPolicyRevision openRev = open.get();
            if (openRev.getOperation() == DisplayPolicyOperation.CLEAR) {
                return replay(deviceId, tenantId, openRev);
            }
            // Open ENFORCE proposal: block only while still PENDING; an approved
            // ENFORCE is superseded at the CLEAR's approval time (see enforce()).
            if (isAwaitingApproval(tenantId, openRev)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A different display-policy proposal is pending approval for this device; "
                                + "approve or reject it first.");
            }
        }

        EndpointDisplayPolicy current =
                policyRepository.findByTenantIdAndDeviceId(tenantId, deviceId).orElse(null);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No managed display policy to clear for this device.");
        }
        if (current.getOperation() == DisplayPolicyOperation.CLEAR) {
            // Already cleared → idempotent no-op.
            return AdminDisplayPolicyResponse.of(deviceId, current, null, null);
        }

        assertHeartbeatFreshAndCapable(device, now);

        EndpointDisplayPolicyRevision revision = buildClearRevision(tenantId, deviceId, reason, subject, now);
        EndpointCommand command = persistProposal(tenantId, device, revision, subject, now,
                buildClearPayload(revision));

        auditService.record(tenantId, device, command, EVENT_CLEAR_PROPOSED, ACTION_CLEAR,
                subject, command.getIdempotencyKey(),
                proposalAuditMetadata(revision, command), null, stateSnapshot(revision, command));

        return AdminDisplayPolicyResponse.of(deviceId, current, revision, command);
    }

    // ─────────────────────────────────────────────────────────────
    // GET

    @Transactional(readOnly = true)
    public AdminDisplayPolicyResponse get(AdminTenantContext context, UUID deviceId) {
        assertFeatureEnabled();
        UUID tenantId = context.tenantId();
        deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));

        EndpointDisplayPolicy current =
                policyRepository.findByTenantIdAndDeviceId(tenantId, deviceId).orElse(null);
        EndpointDisplayPolicyRevision openRev = findOpenProposal(tenantId, deviceId).orElse(null);
        if (current == null && openRev == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No display policy for this device.");
        }
        EndpointCommand openCmd = openRev == null ? null : loadCommand(tenantId, openRev.getCommandId());
        return AdminDisplayPolicyResponse.of(deviceId, current, openRev, openCmd);
    }

    // ─────────────────────────────────────────────────────────────
    // Internals

    private Optional<EndpointDisplayPolicyRevision> findOpenProposal(UUID tenantId, UUID deviceId) {
        List<EndpointDisplayPolicyRevision> open =
                revisionRepository.findOpenProposals(tenantId, deviceId);
        return open.isEmpty() ? Optional.empty() : Optional.of(open.get(0));
    }

    private AdminDisplayPolicyResponse replay(UUID deviceId, UUID tenantId,
                                              EndpointDisplayPolicyRevision openRev) {
        EndpointDisplayPolicy current =
                policyRepository.findByTenantIdAndDeviceId(tenantId, deviceId).orElse(null);
        EndpointCommand openCmd = loadCommand(tenantId, openRev.getCommandId());
        return AdminDisplayPolicyResponse.of(deviceId, current, openRev, openCmd);
    }

    private EndpointCommand loadCommand(UUID tenantId, UUID commandId) {
        if (commandId == null) {
            return null;
        }
        return commandRepository.findByTenantIdAndId(tenantId, commandId).orElse(null);
    }

    /**
     * True if the open proposal's backing command is still awaiting maker-checker
     * approval (PENDING). An open proposal whose command is APPROVED is the
     * enacted current truth and does NOT block a new (superseding) proposal here
     * — per-device single-flight is enforced at APPROVAL time by
     * {@code DisplayPolicyApprovalListener}. Fail-closed: an unresolvable command
     * is treated as pending so we never silently bypass an unverifiable proposal.
     */
    private boolean isAwaitingApproval(UUID tenantId, EndpointDisplayPolicyRevision openRev) {
        EndpointCommand cmd = loadCommand(tenantId, openRev.getCommandId());
        return cmd == null || cmd.getApprovalStatus() == ApprovalStatus.PENDING;
    }

    /**
     * Persist command-then-revision (Codex must-fix #5): EndpointCommand uses a
     * DB-generated UUID, so we cannot pre-assign its id. We pre-generate ONLY the
     * revision id (assigned-@Id entity), reference it in the payload, save the
     * command first (FK target for the revision's {@code command_id}), then save
     * the append-only revision with {@code command_id} already populated.
     */
    private EndpointCommand persistProposal(UUID tenantId, EndpointDevice device,
                                            EndpointDisplayPolicyRevision revision, String subject,
                                            Instant now, Map<String, Object> payload) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.SET_DISPLAY_POLICY);
        // Correlation key (NOT the dedup mechanism — open-proposal lookup is).
        command.setIdempotencyKey("display-policy:" + revision.getId());
        command.setStatus(CommandStatus.QUEUED);
        // Always maker-checker: parked PENDING, invisible to the agent claim path
        // until a second admin approves via the existing dual-control surface.
        command.setApprovalStatus(ApprovalStatus.PENDING);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setVisibleAfterAt(now);
        command.setExpiresAt(null);
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);
        EndpointCommand saved = commandRepository.saveAndFlush(command);

        revision.setCommandId(saved.getId());
        revisionRepository.saveAndFlush(revision);
        return saved;
    }

    private EndpointDisplayPolicyRevision buildEnforceRevision(UUID tenantId, UUID deviceId,
                                                              SetDisplayPolicyRequest req,
                                                              String subject, Instant now) {
        EndpointDisplayPolicyRevision r = new EndpointDisplayPolicyRevision();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setDeviceId(deviceId);
        r.setScopeType(EndpointDisplayPolicyRevision.SCOPE_DEVICE);
        r.setOperation(DisplayPolicyOperation.ENFORCE);
        if (req.screensaver() != null) {
            SetDisplayPolicyRequest.Screensaver s = req.screensaver();
            r.setScreensaverEnabled(s.enabled());
            r.setScreensaverTimeoutSeconds(s.timeoutSeconds());
            r.setScreensaverSecure(s.secureOnResume());
            r.setScreensaverScrPath(trimToNull(s.scrPath()));
        }
        if (req.wallpaper() != null) {
            SetDisplayPolicyRequest.Wallpaper w = req.wallpaper();
            r.setWallpaperEnabled(w.enabled());
            // Canonicalize to the WallpaperStyle enum so the stored value matches
            // the V58 uppercase style CHECK domain.
            r.setWallpaperStyle(w.style() == null ? null : DisplayPolicyValidator.parseStyle(w.style()));
            r.setWallpaperUserCannotChange(w.userCannotChange());
            r.setWallpaperAssetRef(trimToNull(w.assetRef()));
            r.setWallpaperAssetSha256(w.assetSha256() == null
                    ? null : w.assetSha256().toLowerCase(Locale.ROOT));
            r.setWallpaperContentType(w.contentType() == null
                    ? null : w.contentType().toLowerCase(Locale.ROOT));
        }
        r.setReason(trimToNull(req.reason()));
        r.setCreatedBySubject(subject);
        r.setCreatedAt(now);
        r.setPolicyHashSha256(computeHash(r));
        return r;
    }

    private EndpointDisplayPolicyRevision buildClearRevision(UUID tenantId, UUID deviceId,
                                                            String reason, String subject, Instant now) {
        EndpointDisplayPolicyRevision r = new EndpointDisplayPolicyRevision();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setDeviceId(deviceId);
        r.setScopeType(EndpointDisplayPolicyRevision.SCOPE_DEVICE);
        r.setOperation(DisplayPolicyOperation.CLEAR);
        r.setReason(reason);
        r.setCreatedBySubject(subject);
        r.setCreatedAt(now);
        r.setPolicyHashSha256(computeHash(r));
        return r;
    }

    /**
     * Deterministic SHA-256 over a canonical, null-omitted serialization of the
     * desired-state (sorted fixed key order). Stable across requests so the
     * open-proposal replay compare is reliable. CLEAR hashes a constant marker.
     */
    static String computeHash(EndpointDisplayPolicyRevision r) {
        StringBuilder sb = new StringBuilder();
        sb.append("op=").append(r.getOperation() == null ? "" : r.getOperation().name());
        if (r.getOperation() == DisplayPolicyOperation.ENFORCE) {
            appendKv(sb, "ss.enabled", r.getScreensaverEnabled());
            appendKv(sb, "ss.timeout", r.getScreensaverTimeoutSeconds());
            appendKv(sb, "ss.secure", r.getScreensaverSecure());
            appendKv(sb, "ss.scr", r.getScreensaverScrPath());
            appendKv(sb, "wp.enabled", r.getWallpaperEnabled());
            appendKv(sb, "wp.style", r.getWallpaperStyle() == null ? null : r.getWallpaperStyle().name());
            appendKv(sb, "wp.userCannotChange", r.getWallpaperUserCannotChange());
            appendKv(sb, "wp.assetRef", r.getWallpaperAssetRef());
            appendKv(sb, "wp.sha256", r.getWallpaperAssetSha256());
            appendKv(sb, "wp.contentType", r.getWallpaperContentType());
        }
        return sha256Hex(sb.toString());
    }

    private static void appendKv(StringBuilder sb, String key, Object value) {
        if (value != null) {
            sb.append('\n').append(key).append('=').append(value);
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by every JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> buildEnforcePayload(EndpointDisplayPolicyRevision r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "ENFORCE");
        payload.put("revisionId", r.getId().toString());
        payload.put("deviceId", r.getDeviceId().toString());
        payload.put("policyHash", r.getPolicyHashSha256());
        Map<String, Object> screensaver = new LinkedHashMap<>();
        putIfNotNull(screensaver, "enabled", r.getScreensaverEnabled());
        putIfNotNull(screensaver, "timeoutSeconds", r.getScreensaverTimeoutSeconds());
        putIfNotNull(screensaver, "secureOnResume", r.getScreensaverSecure());
        putIfNotNull(screensaver, "scrPath", r.getScreensaverScrPath());
        if (!screensaver.isEmpty()) {
            payload.put("screensaver", screensaver);
        }
        Map<String, Object> wallpaper = new LinkedHashMap<>();
        putIfNotNull(wallpaper, "enabled", r.getWallpaperEnabled());
        putIfNotNull(wallpaper, "style", r.getWallpaperStyle() == null ? null : r.getWallpaperStyle().name());
        putIfNotNull(wallpaper, "registryValue",
                r.getWallpaperStyle() == null ? null : r.getWallpaperStyle().registryValue());
        putIfNotNull(wallpaper, "userCannotChange", r.getWallpaperUserCannotChange());
        putIfNotNull(wallpaper, "assetRef", r.getWallpaperAssetRef());
        putIfNotNull(wallpaper, "assetSha256", r.getWallpaperAssetSha256());
        putIfNotNull(wallpaper, "contentType", r.getWallpaperContentType());
        if (!wallpaper.isEmpty()) {
            payload.put("wallpaper", wallpaper);
        }
        return payload;
    }

    private Map<String, Object> buildClearPayload(EndpointDisplayPolicyRevision r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "CLEAR");
        payload.put("revisionId", r.getId().toString());
        payload.put("deviceId", r.getDeviceId().toString());
        payload.put("policyHash", r.getPolicyHashSha256());
        return payload;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Capability + heartbeat (best-effort early feedback)

    private void assertHeartbeatFreshAndCapable(EndpointDevice device, Instant now) {
        Optional<EndpointHeartbeat> latest = heartbeatRepository
                .findFirstByDevice_IdOrderByReceivedAtDesc(device.getId());
        Instant receivedAt = latest.map(EndpointHeartbeat::getReceivedAt).orElse(null);
        boolean fresh = receivedAt != null
                && Duration.between(receivedAt, now).compareTo(heartbeatFreshnessTtl) <= 0;
        if (!fresh) {
            throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                    "Agent heartbeat is stale (receivedAt=" + receivedAt + ", ttl="
                            + heartbeatFreshnessTtl + "). Retry after the agent reconnects.");
        }
        boolean advertised = latest
                .map(EndpointHeartbeat::getPayload)
                .map(payload -> payload.get(HEARTBEAT_CAPABILITIES_KEY))
                .map(this::containsRequiredCapability)
                .orElse(false);
        if (!advertised) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Agent does not advertise the '" + requiredCapability
                            + "' capability on the most recent heartbeat. Upgrade the agent and retry.");
        }
    }

    private boolean containsRequiredCapability(Object capabilitiesNode) {
        if (capabilitiesNode instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && requiredCapability.equalsIgnoreCase(String.valueOf(item).trim())) {
                    return true;
                }
            }
            return false;
        }
        if (capabilitiesNode instanceof Map<?, ?> map) {
            Object val = map.get(requiredCapability);
            return val instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(val));
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Misc

    private void assertFeatureEnabled() {
        if (!featureEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "#508 endpoint display-policy surface is disabled "
                            + "(endpoint-admin.display-policy.enabled=false).");
        }
    }

    private static Map<String, Object> proposalAuditMetadata(EndpointDisplayPolicyRevision r,
                                                             EndpointCommand command) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("revisionId", r.getId().toString());
        meta.put("commandId", command.getId().toString());
        meta.put("deviceId", r.getDeviceId().toString());
        meta.put("operation", r.getOperation().name());
        meta.put("policyHash", r.getPolicyHashSha256());
        meta.put("reason", r.getReason());
        return meta;
    }

    private static Map<String, Object> stateSnapshot(EndpointDisplayPolicyRevision r,
                                                     EndpointCommand command) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("revisionId", r.getId().toString());
        snap.put("commandId", command.getId().toString());
        snap.put("operation", r.getOperation().name());
        snap.put("approvalStatus", command.getApprovalStatus().name());
        snap.put("commandStatus", command.getStatus().name());
        return snap;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolveSubject(AdminTenantContext context) {
        String subject = context == null ? null : trimToNull(context.subject());
        return subject == null ? "unknown-admin" : subject;
    }
}
