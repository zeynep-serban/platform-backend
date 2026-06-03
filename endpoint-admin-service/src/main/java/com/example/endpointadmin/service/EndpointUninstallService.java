package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminUninstallAuditResponse;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestResponse;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.repository.EndpointUninstallAuditRepository;
import com.example.endpointadmin.repository.EndpointUninstallRequestRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * AG-028 Phase 1b — managed uninstall propose/approve flow service
 * (Faz 22.5.6).
 *
 * <p>Destructive-side counterpart to {@code EndpointAdminCommandService}'s
 * dedicated install path ({@code createInstall}). Key contract points
 * (Codex plan-time thread {@code 019e8d81} iter-2 AGREE):
 *
 * <ol>
 *   <li><b>Feature flag</b> ({@code endpoint-admin.uninstall.enabled}) — default
 *       {@code false} → 503. Lets the surface ship in dark mode and flip on
 *       per-tenant via overlay.</li>
 *   <li><b>Idempotency replay FIRST</b> (Codex iter-1 absorb). The replay
 *       short-circuit runs BEFORE the catalog/provenance/in-flight guards so a
 *       legitimate retry never fails on transient drift.</li>
 *   <li><b>Catalog gates</b> — 422 if status != APPROVED,
 *       {@code !uninstall_supported}, or {@code uninstall_protected}.</li>
 *   <li><b>Provenance gate</b> — 422 NO_PROVENANCE unless a prior SUCCEEDED +
 *       SATISFIED install audit row exists for {@code (tenant, device, catalog)}.
 *       Prevents uninstalling something the platform never installed.</li>
 *   <li><b>In-flight guard</b> — soft read + DB partial unique race → 409.</li>
 *   <li><b>Approve maker-checker</b> — approver subject MUST differ from
 *       createdBy (the proposer). Violation emits a durable audit row before
 *       throwing under {@code @Transactional(noRollbackFor = …)} (BE-014A pattern
 *       — Phase 0 already proven).</li>
 *   <li><b>Capability + heartbeat guard</b> — at approve time, the device must
 *       have a recent heartbeat (TTL configurable, default 5min → 424) and the
 *       heartbeat payload must advertise the {@code UNINSTALL_SOFTWARE}
 *       capability (→ 422 retryable, non-terminal — the agent can re-advertise
 *       on next heartbeat and approver can retry).</li>
 *   <li><b>Dispatch payload server-derived</b> — caller never controls the
 *       payload; same catalog-derived shape the install path uses, plus an
 *       {@code intent: UNINSTALL} marker + the request id.</li>
 * </ol>
 */
@Service
public class EndpointUninstallService {

    static final String EVENT_PROPOSED = "ENDPOINT_UNINSTALL_REQUEST_PROPOSED";
    static final String EVENT_APPROVED_DISPATCHED = "ENDPOINT_UNINSTALL_REQUEST_APPROVED_DISPATCHED";
    static final String EVENT_APPROVAL_REJECTED_MAKER_CHECKER =
            "ENDPOINT_UNINSTALL_REQUEST_APPROVAL_REJECTED_MAKER_CHECKER";
    // Codex iter-1 nit #4 absorb (thread `019e8dcd`): no event constants for
    // capability / heartbeat rejects — those audits would roll back with the
    // validation throw under default `@Transactional` semantics. Operators
    // observe these rejects via the API response code (422 / 424) and the
    // request stays in PENDING_APPROVAL for retry. Only the adversarial
    // maker-checker reject is made durable (BE-014A {@code noRollbackFor}).

    private static final String ACTION_PROPOSE = "PROPOSE_ENDPOINT_UNINSTALL";
    private static final String ACTION_APPROVE = "APPROVE_ENDPOINT_UNINSTALL";

    /**
     * Heartbeat payload key under which the agent advertises capabilities.
     * Phase 2 will land the agent-side write. Until then a missing key is
     * treated as "capability unknown" → fail-closed 422.
     */
    private static final String HEARTBEAT_CAPABILITIES_KEY = "capabilities";

    private final EndpointUninstallRequestRepository requestRepository;
    private final EndpointUninstallAuditRepository auditRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointInstallAuditRepository installAuditRepository;
    private final EndpointCommandRepository commandRepository;
    private final EndpointHeartbeatRepository heartbeatRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    private final boolean featureEnabled;
    private final Duration heartbeatFreshnessTtl;
    private final String requiredCapability;

    public EndpointUninstallService(
            EndpointUninstallRequestRepository requestRepository,
            EndpointUninstallAuditRepository auditRepository,
            EndpointDeviceRepository deviceRepository,
            EndpointSoftwareCatalogItemRepository catalogRepository,
            EndpointInstallAuditRepository installAuditRepository,
            EndpointCommandRepository commandRepository,
            EndpointHeartbeatRepository heartbeatRepository,
            EndpointAuditService auditService,
            Clock clock,
            @Value("${endpoint-admin.uninstall.enabled:false}") boolean featureEnabled,
            @Value("${endpoint-admin.uninstall.heartbeat-freshness-ttl:PT5M}")
                    Duration heartbeatFreshnessTtl,
            @Value("${endpoint-admin.uninstall.required-capability:UNINSTALL_SOFTWARE}")
                    String requiredCapability) {
        this.requestRepository = requestRepository;
        this.auditRepository = auditRepository;
        this.deviceRepository = deviceRepository;
        this.catalogRepository = catalogRepository;
        this.installAuditRepository = installAuditRepository;
        this.commandRepository = commandRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.featureEnabled = featureEnabled;
        this.heartbeatFreshnessTtl = heartbeatFreshnessTtl == null
                ? Duration.ofMinutes(5) : heartbeatFreshnessTtl;
        this.requiredCapability = requiredCapability == null
                ? "UNINSTALL_SOFTWARE" : requiredCapability;
    }

    // ─────────────────────────────────────────────────────────────
    // PROPOSE

    @Transactional
    public AdminUninstallRequestResponse propose(AdminTenantContext context,
                                                 UUID deviceId,
                                                 AdminUninstallRequestCreate request) {
        assertFeatureEnabled();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uninstall request body is required.");
        }
        String slug = trimToNull(request.catalogItemId());
        if (slug == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "catalogItemId is required.");
        }
        UUID tenantId = context.tenantId();

        // Device lookup is needed for the idempotency-replay equality check; if
        // the device is missing, the existing-key path would silently accept a
        // stale request.
        EndpointDevice device = deviceRepository
                .findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));
        EndpointSoftwareCatalogItem catalogItem = catalogRepository
                .findByTenantIdAndCatalogItemId(tenantId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog item not found."));

        String idempotencyKey = resolveIdempotencyKey(deviceId, catalogItem.getId(),
                request.idempotencyKey());

        // (1) Idempotency replay FIRST — Codex iter-1 must-fix #1 absorb.
        // Mirror of EndpointAdminCommandService.createInstall Codex iter-4 P1-1.
        Optional<EndpointUninstallRequest> existing =
                requestRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            EndpointUninstallRequest e = existing.get();
            if (!Objects.equals(e.getDeviceId(), deviceId)
                    || !Objects.equals(e.getCatalogItemId(), catalogItem.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Uninstall idempotency key already used by another (device, catalog) pair.");
            }
            return AdminUninstallRequestResponse.from(e);
        }

        // (2) Catalog gates — 422 on each violation. Order matters for the
        // operator message: status → protected → supported. {@code APPROVED}
        // + {@code uninstall_supported} are the gates the Phase 0 change-request
        // flow flips; {@code uninstall_protected} is the hard guard.
        if (catalogItem.getStatus() != CatalogItemStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog item is not APPROVED; current status="
                            + catalogItem.getStatus()
                            + ". Approve the catalog row before requesting uninstall.");
        }
        if (catalogItem.isUninstallProtected()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog item is uninstall-protected; unprotect via the Phase 0 "
                            + "change-request flow first.");
        }
        if (!catalogItem.isUninstallSupported()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog item does not have uninstall_supported set; opt in via "
                            + "the Phase 0 change-request flow first.");
        }

        // (3) Provenance gate — 422 NO_PROVENANCE if the platform never
        // observed a SUCCEEDED + SATISFIED install for this (device, catalog).
        // Uses a dedicated existence query (Codex post-impl iter-1 absorb,
        // thread `019e8dcd` must-fix #1) — the prior
        // `findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(Instant.MAX)`
        // was unsafe because {@code Instant.MAX} (year 1000000000) is outside
        // Postgres {@code timestamptz} range and risks bind-time overflow.
        boolean hasProvenance = installAuditRepository
                .existsSucceededSatisfiedByTenantDeviceCatalog(
                        tenantId, deviceId, catalogItem.getId());
        if (!hasProvenance) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No prior install provenance for this device + catalog. "
                            + "AG-028 only uninstalls software the platform installed.");
        }

        // (4) In-flight guard — soft read for a clear operator message; DB
        // partial unique race below for the hard guarantee.
        if (requestRepository.findOpenForDeviceAndCatalog(
                tenantId, deviceId, catalogItem.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An in-flight uninstall already exists for this device + catalog.");
        }

        String subject = resolveSubject(context);
        Instant now = Instant.now(clock);

        EndpointUninstallRequest entity = new EndpointUninstallRequest();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setDeviceId(deviceId);
        entity.setCatalogItemId(catalogItem.getId());
        entity.setState(UninstallRequestState.PENDING_APPROVAL);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setReason(trimToNull(request.reason()));
        entity.setCreatedBy(subject);
        entity.setCreatedAt(now);
        entity.setStateUpdatedAt(now);

        EndpointUninstallRequest saved;
        try {
            saved = requestRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dive) {
            // Lost the race to a parallel propose — DB partial unique fired.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An in-flight uninstall already exists for this device + catalog.");
        }

        auditService.record(
                tenantId,
                device,
                null,
                EVENT_PROPOSED,
                ACTION_PROPOSE,
                subject,
                saved.getIdempotencyKey(),
                proposeAuditMetadata(saved, catalogItem, slug, request.reason()),
                null,
                stateSnapshot(saved));

        return AdminUninstallRequestResponse.from(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // APPROVE

    /**
     * Maker-checker approval — transitions PENDING_APPROVAL → APPROVED and
     * dispatches the {@code UNINSTALL_SOFTWARE} command in the same
     * transaction.
     *
     * <p>BE-014A {@code noRollbackFor} pattern (Codex Phase 0 iter-1 absorb):
     * the maker-checker rejection writes a durable audit row before throwing.
     * Without the exclusion the audit insert would roll back with the
     * rejected transaction. Capability / heartbeat / catalog revalidation
     * rejects are NOT under the exclusion — those are validation rejects, not
     * adversarial security events, so a rollback is acceptable; the
     * compliance evaluator will re-trigger when the agent's next heartbeat
     * arrives.
     */
    @Transactional(noRollbackFor = EndpointUninstallMakerCheckerViolationException.class)
    public AdminUninstallRequestResponse approve(AdminTenantContext context,
                                                 UUID deviceId,
                                                 UUID requestId,
                                                 AdminUninstallRequestApproval body) {
        assertFeatureEnabled();
        UUID tenantId = context.tenantId();
        String subject = resolveSubject(context);

        // PESSIMISTIC_WRITE on the request row — Codex post-impl iter-1
        // absorb (thread `019e8dcd` must-fix #2). Serialises concurrent
        // approvers so the second one observes the state already advanced
        // past PENDING_APPROVAL and rejects with a clean 409, instead of
        // racing through to the command idempotency-key unique constraint
        // and surfacing as a 500.
        EndpointUninstallRequest req = requestRepository
                .findByTenantIdAndIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Uninstall request not found in tenant scope."));
        if (!Objects.equals(req.getDeviceId(), deviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "deviceId in path does not match the request's device.");
        }
        if (req.getState() != UninstallRequestState.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Uninstall request is not in PENDING_APPROVAL state; current state="
                            + req.getState());
        }
        if (Objects.equals(req.getCreatedBy(), subject)) {
            // Maker-checker violation — durable audit BEFORE throwing.
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_APPROVAL_REJECTED_MAKER_CHECKER,
                    ACTION_APPROVE,
                    subject,
                    req.getIdempotencyKey(),
                    Map.of("requestId", req.getId().toString(),
                            "createdBy", req.getCreatedBy(),
                            "approverSubject", subject),
                    null,
                    null);
            throw new EndpointUninstallMakerCheckerViolationException(
                    req.getId(), req.getCreatedBy(), subject);
        }

        // Re-fetch the catalog + device under the same transaction so a
        // propose-then-revoke race or a Phase 0 flag-flip race cannot let
        // an approve through stale state.
        EndpointDevice device = deviceRepository
                .findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found at approve time."));
        EndpointSoftwareCatalogItem catalogItem = catalogRepository
                .findByTenantIdAndId(tenantId, req.getCatalogItemId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog item no longer present at approve time."));

        if (catalogItem.getStatus() != CatalogItemStatus.APPROVED
                || !catalogItem.isUninstallSupported()
                || catalogItem.isUninstallProtected()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog gates no longer satisfied at approve time "
                            + "(status / uninstall_supported / uninstall_protected drifted). "
                            + "Re-propose after Phase 0 flag-flip flow.");
        }

        // (5) Capability + heartbeat guard — Codex post-impl iter-1 absorb
        // (thread `019e8dcd` must-fix #3). Both freshness AND capability are
        // read from the SAME single most-recent heartbeat row, so stale
        // capability data cannot be paired with a fresh-via-enrollment
        // {@code device.lastSeenAt} timestamp.
        Instant now = Instant.now(clock);
        assertHeartbeatFreshAndCapable(req, device, now, subject);

        // (6) Transition request state BEFORE building the dispatch payload
        // so {@code approvedBy} is non-null in the payload Codex iter-1
        // (must-fix #4).
        req.setState(UninstallRequestState.APPROVED);
        req.setApprovedBy(subject);
        req.setStateUpdatedAt(now);

        // (7) Build dispatch payload — server-derived. Uses the SHARED
        // {@code EndpointAdminCommandService.buildAgentDetectionRule} so the
        // catalog-shape → agent-wire-shape translation (FILE_*
        // {@code absolutePath → path}, WINGET identity invariant) is
        // identical to the install path (Codex iter-1 must-fix #5).
        Map<String, Object> payload = buildUninstallPayload(catalogItem, req,
                trimToNull(body == null ? null : body.reason()));

        // (8) Create the UNINSTALL_SOFTWARE command — the agent will claim it
        // via the existing endpoint_commands queue + lifecycle. Approval gate
        // already satisfied at this layer so the command is NOT_REQUIRED.
        String commandIdempotencyKey = "admin-uninstall-cmd:" + req.getId();
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.UNINSTALL_SOFTWARE);
        command.setIdempotencyKey(commandIdempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setVisibleAfterAt(now);
        command.setExpiresAt(null);
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);
        EndpointCommand savedCommand = commandRepository.saveAndFlush(command);

        // (9) Persist the command ref on the request row.
        req.setCommandId(savedCommand.getId());
        EndpointUninstallRequest savedReq = requestRepository.save(req);

        auditService.record(
                tenantId,
                device,
                savedCommand,
                EVENT_APPROVED_DISPATCHED,
                ACTION_APPROVE,
                subject,
                savedReq.getIdempotencyKey(),
                approveAuditMetadata(savedReq, catalogItem, savedCommand,
                        body == null ? null : trimToNull(body.reason())),
                null,
                stateSnapshot(savedReq));

        return AdminUninstallRequestResponse.from(savedReq);
    }

    // ─────────────────────────────────────────────────────────────
    // READS

    @Transactional(readOnly = true)
    public AdminUninstallRequestResponse get(AdminTenantContext context,
                                             UUID deviceId,
                                             UUID requestId) {
        assertFeatureEnabled();
        UUID tenantId = context.tenantId();
        EndpointUninstallRequest req = requestRepository
                .findByTenantIdAndId(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Uninstall request not found in tenant scope."));
        if (!Objects.equals(req.getDeviceId(), deviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Uninstall request not found for this device.");
        }
        return AdminUninstallRequestResponse.from(req);
    }

    @Transactional(readOnly = true)
    public List<AdminUninstallRequestResponse> listForDevice(AdminTenantContext context,
                                                              UUID deviceId,
                                                              int page,
                                                              int size) {
        assertFeatureEnabled();
        UUID tenantId = context.tenantId();
        // Validate device visibility (NOT_FOUND if cross-tenant).
        deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200));
        return requestRepository
                .findByTenantIdAndDeviceIdOrderByCreatedAtDesc(tenantId, deviceId, pageable)
                .map(AdminUninstallRequestResponse::from)
                .getContent();
    }

    @Transactional(readOnly = true)
    public List<AdminUninstallAuditResponse> getHistory(AdminTenantContext context,
                                                         UUID deviceId,
                                                         int page,
                                                         int size) {
        assertFeatureEnabled();
        UUID tenantId = context.tenantId();
        deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200));
        return auditRepository
                .findByTenantIdAndDeviceIdOrderByReportedAtDesc(tenantId, deviceId, pageable)
                .map(AdminUninstallAuditResponse::from)
                .getContent();
    }

    // ─────────────────────────────────────────────────────────────
    // Capability + heartbeat helpers

    /**
     * Verify both heartbeat freshness AND capability advertisement from the
     * SAME most-recent heartbeat row (Codex post-impl iter-1 absorb, thread
     * `019e8dcd` must-fix #3).
     *
     * <p>Reading {@code device.lastSeenAt} would be misleading because the
     * field is updated by non-heartbeat paths too (enrollment / cert rotate
     * via {@code MachineCertAutoEnrollService} + {@code EndpointDeviceService}
     * etc.). Pairing a fresh-via-enrollment timestamp with an old
     * capability-advertising heartbeat would let an unsupported agent
     * accidentally pass the gate.
     *
     * <p>The audit emits are intentionally OMITTED here because they would
     * roll back with the throw (no {@code noRollbackFor} clause). For
     * adversarial events (maker-checker) we use BE-014A; for validation
     * rejects (capability / heartbeat) the operator's retry path is the
     * observability signal — they see the 422/424 and the request stays in
     * PENDING_APPROVAL for retry once the agent reconnects.
     */
    private void assertHeartbeatFreshAndCapable(EndpointUninstallRequest req,
                                                EndpointDevice device,
                                                Instant now,
                                                String approver) {
        Optional<EndpointHeartbeat> latest = heartbeatRepository
                .findFirstByDevice_IdOrderByReceivedAtDesc(device.getId());
        Instant receivedAt = latest.map(EndpointHeartbeat::getReceivedAt).orElse(null);
        boolean fresh = receivedAt != null
                && Duration.between(receivedAt, now).compareTo(heartbeatFreshnessTtl) <= 0;
        if (!fresh) {
            // 424 FAILED_DEPENDENCY — upstream agent not reachable; retryable
            // non-terminal.
            throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                    "Agent heartbeat is stale (receivedAt="
                            + receivedAt + ", ttl=" + heartbeatFreshnessTtl
                            + "). Retry after the agent reconnects.");
        }
        boolean advertised = latest
                .map(EndpointHeartbeat::getPayload)
                .map(payload -> payload.get(HEARTBEAT_CAPABILITIES_KEY))
                .map(this::containsRequiredCapability)
                .orElse(false);
        if (!advertised) {
            // 422 retryable non-terminal — the agent might advertise the
            // capability on its next heartbeat (Phase 2 lands the agent-side
            // write).
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Agent does not advertise the '" + requiredCapability
                            + "' capability on the most recent heartbeat. "
                            + "Upgrade the agent and retry approve.");
        }
    }

    private boolean containsRequiredCapability(Object capabilitiesNode) {
        if (capabilitiesNode == null) {
            return false;
        }
        if (capabilitiesNode instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && requiredCapability.equalsIgnoreCase(
                        String.valueOf(item).trim())) {
                    return true;
                }
            }
            return false;
        }
        if (capabilitiesNode instanceof Map<?, ?> map) {
            // Tolerate a future shape like `{capabilities: {UNINSTALL_SOFTWARE: true}}`.
            Object val = map.get(requiredCapability);
            return val instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(val));
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Dispatch payload

    private Map<String, Object> buildUninstallPayload(EndpointSoftwareCatalogItem catalogItem,
                                                      EndpointUninstallRequest req,
                                                      String approverReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "UNINSTALL");
        payload.put("requestId", req.getId().toString());
        payload.put("catalogItemId", catalogItem.getCatalogItemId());
        payload.put("catalogItemUuid", catalogItem.getId().toString());
        payload.put("catalogPackageId", catalogItem.getPackageId());
        payload.put("catalogRowVersion", catalogItem.getVersion());
        payload.put("packageProvider", catalogItem.getProvider() == null
                ? null : catalogItem.getProvider().name());
        payload.put("sourceType", catalogItem.getSourceType() == null
                ? null : catalogItem.getSourceType().name());
        payload.put("installerType", catalogItem.getInstallerType() == null
                ? null : catalogItem.getInstallerType().name());
        // SHARED catalog-shape → agent-wire-shape translation (Codex post-impl
        // iter-1 must-fix #5 absorb, thread `019e8dcd`). The uninstall agent
        // uses identical detection-rule semantics for the authoritative
        // absence probe; forwarding the raw catalog rule would skip the
        // FILE_* {@code absolutePath → path} rename and the WINGET identity
        // invariant (Codex 019e77bd). Sharing the install helper guarantees
        // both surfaces stay aligned on the wire contract.
        payload.put("detectionRule", EndpointAdminCommandService
                .buildAgentDetectionRule(catalogItem, catalogItem.getPackageId()));
        payload.put("uninstallSupported", catalogItem.isUninstallSupported());
        payload.put("uninstallProtected", catalogItem.isUninstallProtected());
        payload.put("createdBy", req.getCreatedBy());
        payload.put("approvedBy", req.getApprovedBy());
        if (req.getReason() != null) {
            payload.put("proposeReason", req.getReason());
        }
        if (approverReason != null) {
            payload.put("approveReason", approverReason);
        }
        return payload;
    }

    // ─────────────────────────────────────────────────────────────
    // Misc helpers

    private void assertFeatureEnabled() {
        if (!featureEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AG-028 managed uninstall surface is disabled "
                            + "(endpoint-admin.uninstall.enabled=false).");
        }
    }

    private String resolveIdempotencyKey(UUID deviceId, UUID catalogUuid, String requestedKey) {
        // Canonical key shape: `admin-uninstall:{deviceId(36)}:{catalogUuid(36)}:{body}`.
        // Fixed prefix = 90 chars; body MUST fit 38 chars so the canonical
        // string stays ≤ 128 (endpoint_uninstall_requests.idempotency_key
        // VARCHAR(128)). DTO @Size(max=40) covers normal callers; the >38
        // fall-through here covers programmatic callers (parity with the
        // install path's SHA-256-prefix-hash).
        String key = trimToNull(requestedKey);
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else if (key.length() > 38) {
            key = sha256Prefix(key);
        }
        return "admin-uninstall:" + deviceId + ":" + catalogUuid + ":" + key;
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolveSubject(AdminTenantContext context) {
        String subject = trimToNull(context == null ? null : context.subject());
        return subject == null ? "unknown-admin" : subject;
    }

    private static Map<String, Object> proposeAuditMetadata(EndpointUninstallRequest req,
                                                            EndpointSoftwareCatalogItem catalog,
                                                            String slug,
                                                            String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", req.getId().toString());
        meta.put("deviceId", req.getDeviceId().toString());
        meta.put("catalogItemId", slug);
        meta.put("catalogItemUuid", catalog.getId().toString());
        meta.put("catalogPackageId", catalog.getPackageId());
        meta.put("catalogRowVersion", catalog.getVersion());
        meta.put("idempotencyKey", req.getIdempotencyKey());
        meta.put("createdBy", req.getCreatedBy());
        if (reason != null) {
            meta.put("reason", reason);
        }
        return meta;
    }

    private static Map<String, Object> approveAuditMetadata(EndpointUninstallRequest req,
                                                            EndpointSoftwareCatalogItem catalog,
                                                            EndpointCommand command,
                                                            String approveReason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", req.getId().toString());
        meta.put("commandId", command.getId().toString());
        meta.put("deviceId", req.getDeviceId().toString());
        meta.put("catalogItemId", catalog.getCatalogItemId());
        meta.put("catalogItemUuid", catalog.getId().toString());
        meta.put("createdBy", req.getCreatedBy());
        meta.put("approvedBy", req.getApprovedBy());
        if (approveReason != null) {
            meta.put("approveReason", approveReason);
        }
        return meta;
    }

    private static Map<String, Object> stateSnapshot(EndpointUninstallRequest req) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("requestId", req.getId().toString());
        snap.put("state", req.getState().name());
        snap.put("commandId", req.getCommandId() == null ? null : req.getCommandId().toString());
        snap.put("createdBy", req.getCreatedBy());
        snap.put("approvedBy", req.getApprovedBy());
        return snap;
    }
}
