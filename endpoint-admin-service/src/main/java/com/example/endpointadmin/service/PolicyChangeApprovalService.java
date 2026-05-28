package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.ApprovalActorDto;
import com.example.endpointadmin.dto.v1.admin.DecisionAttestationDto;
import com.example.endpointadmin.dto.v1.admin.DecisionRecordDto;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.ApproveRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.AttestRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.DelegateRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RejectRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RequestChangesRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.ProposePolicyChangeRequest;
import com.example.endpointadmin.exception.PolicyApprovalActorMismatchException;
import com.example.endpointadmin.exception.PolicyApprovalDelegateConflictException;
import com.example.endpointadmin.exception.PolicyApprovalProposerSelfException;
import com.example.endpointadmin.model.PolicyApprovalDecisionKind;
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeApproval;
import com.example.endpointadmin.model.PolicyChangeApprovalDecision;
import com.example.endpointadmin.repository.PolicyChangeApprovalRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wave-12 PR-5 — back-end for the policy-change approval workflow used by
 * the platform-web {@code mfe-endpoint-admin} pilot. The platform-web
 * {@code ApprovalsRepository} port currently writes to {@code
 * localStorage}; once this service is in place the front-end can switch
 * to the {@code approvalsApiRepository} adapter (separate follow-up PR).
 *
 * <p>Governance invariants enforced here (Codex iter-1 absorb):
 * <ul>
 *   <li><b>Actor identity from auth context</b> — the request body's
 *       {@code actor.id} (and propose's {@code proposer.id}) must equal
 *       the authenticated subject; mismatch returns
 *       {@code 400 actor_mismatch}. This closes the body-trust 4-eyes
 *       bypass — a user can only act as themselves;</li>
 *   <li>4-eyes guard — the proposer cannot {@code APPROVE} their own
 *       request ({@code 403 proposer_self});</li>
 *   <li>Decisions are only accepted while the request is in an open
 *       state ({@code PENDING} or {@code IN_REVIEW});</li>
 *   <li>{@code REJECT} and {@code REQUEST_CHANGES} require a reason;</li>
 *   <li>{@code REQUEST_CHANGES} moves {@code PENDING → IN_REVIEW}; the
 *       request stays open for revision;</li>
 *   <li>{@code APPROVE} / {@code REJECT} are terminal;</li>
 *   <li>{@code DELEGATE} requires the delegating actor to be on the
 *       current approver list (else 400 {@code delegate_conflict}),
 *       atomically swaps that actor with {@code delegateTo}, and does
 *       NOT advance status;</li>
 *   <li>{@code ATTEST} appends a record without advancing status;</li>
 *   <li>Decision read uses a {@code PESSIMISTIC_WRITE} row lock so two
 *       concurrent reviewers serialise cleanly — the loser sees the
 *       up-to-date status post-commit and is rejected with 409 if the
 *       request has become terminal.</li>
 * </ul>
 *
 * <p>Each {@link PolicyChangeApprovalDecision} captures
 * {@code previousStatus} + {@code newStatus} so the design-system
 * {@code DecisionRecordBase} contract round-trips verbatim.
 *
 * <p>The append-only {@code history} on each request IS the audit
 * trail; this service intentionally does NOT write to the device-scoped
 * {@code EndpointAuditService} chain — policy-change approvals are not
 * tied to a single device.
 */
@Service
public class PolicyChangeApprovalService {

    private static final Set<PolicyApprovalStatus> OPEN_STATES =
            EnumSet.of(PolicyApprovalStatus.PENDING, PolicyApprovalStatus.IN_REVIEW);

    private final PolicyChangeApprovalRepository repository;
    private final Clock clock;

    public PolicyChangeApprovalService(PolicyChangeApprovalRepository repository,
                                       Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PolicyChangeApprovalDto> list(AdminTenantContext context,
                                              PolicyApprovalStatus status,
                                              String target,
                                              String proposerSubject) {
        return repository.search(context.tenantId(), status,
                        trimToNull(target), trimToNull(proposerSubject))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyChangeApprovalDto get(AdminTenantContext context, UUID id) {
        return toDto(loadRequired(context, id));
    }

    @Transactional
    public PolicyChangeApprovalDto propose(AdminTenantContext context,
                                           ProposePolicyChangeRequest request) {
        if (request == null) {
            throw badRequest("A propose request body is required.");
        }
        // Codex iter-1 P1: a propose's proposer identity must equal the
        // authenticated subject. Body name + role are echoed verbatim so
        // display data is preserved; only the id is governance-bearing.
        requireActorMatchesSubject(context, request.proposer(), "proposer");
        Instant now = Instant.now(clock);
        if (request.deadline() != null && !request.deadline().isAfter(now)) {
            throw badRequest("Deadline must be in the future.");
        }

        PolicyChangeApproval approval = new PolicyChangeApproval();
        approval.setTenantId(context.tenantId());
        approval.setTitle(request.title().trim());
        approval.setTarget(request.target().trim());
        approval.setProposerSubject(request.proposer().id().trim());
        approval.setProposerName(request.proposer().name().trim());
        approval.setProposerRole(request.proposer().role().trim());
        approval.setReason(request.reason().trim());
        approval.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.setChangeKind(request.changeKind());
        approval.setRiskTier(request.riskTier());
        approval.setBeforeState(request.before());
        approval.setAfterState(request.after());
        approval.setDeadline(request.deadline());
        approval.setStatus(PolicyApprovalStatus.PENDING);
        approval.setCurrentApprovers(actorListToJson(request.currentApprovers()));

        PolicyChangeApproval saved = repository.saveAndFlush(approval);
        return toDto(saved);
    }

    @Transactional
    public PolicyChangeApprovalDto approve(AdminTenantContext context, UUID id,
                                           ApproveRequest request) {
        if (request == null) {
            throw badRequest("An approve request body is required.");
        }
        requireActorMatchesSubject(context, request.actor(), "actor");
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);
        guardProposerSelfApprove(approval, request.actor());

        PolicyApprovalStatus previous = approval.getStatus();
        PolicyApprovalStatus next = PolicyApprovalStatus.APPROVED;
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.APPROVE, request.actor(),
                previous, next);
        decision.setReason(trimToNull(request.reason()));
        decision.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.addDecision(decision);
        approval.setStatus(next);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto reject(AdminTenantContext context, UUID id,
                                          RejectRequest request) {
        if (request == null) {
            throw badRequest("A reject request body is required.");
        }
        requireActorMatchesSubject(context, request.actor(), "actor");
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        PolicyApprovalStatus previous = approval.getStatus();
        PolicyApprovalStatus next = PolicyApprovalStatus.REJECTED;
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.REJECT, request.actor(),
                previous, next);
        decision.setReason(request.reason().trim());
        decision.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.addDecision(decision);
        approval.setStatus(next);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto requestChanges(AdminTenantContext context, UUID id,
                                                  RequestChangesRequest request) {
        if (request == null) {
            throw badRequest("A request-changes request body is required.");
        }
        requireActorMatchesSubject(context, request.actor(), "actor");
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        PolicyApprovalStatus previous = approval.getStatus();
        PolicyApprovalStatus next = PolicyApprovalStatus.IN_REVIEW;
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.REQUEST_CHANGES, request.actor(),
                previous, next);
        decision.setReason(request.reason().trim());
        decision.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.addDecision(decision);
        approval.setStatus(next);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto delegate(AdminTenantContext context, UUID id,
                                            DelegateRequest request) {
        if (request == null) {
            throw badRequest("A delegate request body is required.");
        }
        requireActorMatchesSubject(context, request.actor(), "actor");
        requireCanonicalActorId(request.delegateTo(), "delegateTo");
        if (Objects.equals(request.actor().id(), request.delegateTo().id())) {
            throw badRequest("Delegate target must differ from the delegating actor.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);
        // Codex iter-1 P2 + iter-2 absorb: the delegating actor must
        // currently be on the approver list AND the delegateTo target
        // must NOT already be on the list. The first stops a non-
        // approver from parachuting themselves onto a request; the
        // second stops the in-place swap from creating a duplicate
        // approver entry.
        List<Map<String, Object>> approvers = approval.getCurrentApprovers();
        if (!isCurrentApprover(approvers, request.actor().id())) {
            throw new PolicyApprovalDelegateConflictException(
                    "Delegating actor is not on the current approver list.");
        }
        if (isCurrentApprover(approvers, request.delegateTo().id())) {
            throw new PolicyApprovalDelegateConflictException(
                    "Delegate target is already on the current approver list.");
        }

        PolicyApprovalStatus current = approval.getStatus();
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.DELEGATE, request.actor(),
                current, current);
        decision.setDelegateSubject(request.delegateTo().id().trim());
        decision.setDelegateName(request.delegateTo().name().trim());
        decision.setDelegateRole(request.delegateTo().role().trim());
        decision.setReason(trimToNull(request.reason()));
        decision.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.addDecision(decision);
        approval.setCurrentApprovers(rewriteApproversForDelegate(
                approval.getCurrentApprovers(), request.actor(), request.delegateTo()));

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto attest(AdminTenantContext context, UUID id,
                                          AttestRequest request) {
        if (request == null) {
            throw badRequest("An attest request body is required.");
        }
        requireActorMatchesSubject(context, request.actor(), "actor");
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        PolicyApprovalStatus current = approval.getStatus();
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.ATTEST, request.actor(),
                current, current);
        DecisionAttestationDto att = request.attestation();
        decision.setStatement(att.statement().trim());
        decision.setAcceptedAt(att.acceptedAt());
        decision.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.addDecision(decision);

        return toDto(repository.saveAndFlush(approval));
    }

    private PolicyChangeApproval loadRequired(AdminTenantContext context, UUID id) {
        return repository.findByTenantIdAndId(context.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Policy-change approval request not found."));
    }

    /**
     * Codex iter-1 P1 absorb — pessimistic row lock during the decision
     * path so two concurrent reviewers serialise cleanly. The loser
     * blocks here, re-reads the up-to-date status after the winner
     * commits, and is rejected with 409 if the request has become
     * terminal.
     */
    private PolicyChangeApproval loadRequiredForDecision(AdminTenantContext context, UUID id) {
        PolicyChangeApproval approval = repository
                .findByTenantIdAndIdForUpdate(context.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Policy-change approval request not found."));
        if (!OPEN_STATES.contains(approval.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval request is not open to further decisions.");
        }
        Instant now = Instant.now(clock);
        if (approval.getDeadline() != null && !approval.getDeadline().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval request has expired and can no longer be decided.");
        }
        return approval;
    }

    /**
     * Codex iter-1 P1 absorb + iter-3 canonicalization — guard against
     * body-supplied {@code actor.id} (or propose's {@code proposer.id})
     * being used to spoof identity. The id must be a canonical string:
     *
     * <ul>
     *   <li>non-empty;</li>
     *   <li>no leading / trailing whitespace (governance-bearing field,
     *       reject silently-normalisable forms instead of accepting
     *       them);</li>
     *   <li>equals the authenticated subject ({@code context.subject()})
     *       after the subject's own trim.</li>
     * </ul>
     *
     * <p>Whitespace rejection is iter-3 absorb: silently trimming
     * created a divergence where {@code actor.id="alice "} passed the
     * mismatch guard (compared trimmed) but the persisted
     * {@code proposerSubject="alice"} compared raw against
     * {@code actor.id()} in {@link #guardProposerSelfApprove}, letting
     * the 4-eyes check be skipped. Reject outright — display fields
     * (name, role) are echoed from the body without further
     * verification (Keycloak claims would be the canonical source —
     * left as a future enhancement).
     */
    private void requireActorMatchesSubject(AdminTenantContext context,
                                            ApprovalActorDto actor,
                                            String fieldName) {
        if (actor == null || actor.id() == null) {
            throw badRequest("actor.id is required.");
        }
        String supplied = actor.id();
        if (supplied.isEmpty() || !supplied.equals(supplied.trim())) {
            throw new PolicyApprovalActorMismatchException(
                    fieldName + ".id must not contain leading or trailing "
                            + "whitespace.");
        }
        String authenticated = trimToNull(context.subject());
        if (authenticated == null || !authenticated.equals(supplied)) {
            throw new PolicyApprovalActorMismatchException(
                    fieldName + ".id must equal the authenticated subject.");
        }
    }

    /**
     * Codex iter-3 absorb — applies the same canonical-id rule used
     * for the authenticated actor to the delegateTo target, so the
     * duplicate-approver guard cannot be bypassed by a whitespace-
     * padded {@code delegateTo.id}.
     */
    private void requireCanonicalActorId(ApprovalActorDto actor, String fieldName) {
        if (actor == null || actor.id() == null) {
            throw badRequest(fieldName + ".id is required.");
        }
        String supplied = actor.id();
        if (supplied.isEmpty() || !supplied.equals(supplied.trim())) {
            throw badRequest(fieldName + ".id must not contain leading "
                    + "or trailing whitespace.");
        }
    }

    /**
     * Codex iter-3 absorb — comparison uses {@code trim()} on both
     * sides so the 4-eyes guard cannot be bypassed by a whitespace-
     * padded body {@code actor.id} that slipped past the
     * {@code requireActorMatchesSubject} normaliser (defence in depth).
     */
    private void guardProposerSelfApprove(PolicyChangeApproval approval,
                                          ApprovalActorDto actor) {
        String proposer = trimToNull(approval.getProposerSubject());
        String actorId = trimToNull(actor.id());
        if (proposer != null && proposer.equals(actorId)) {
            throw new PolicyApprovalProposerSelfException(
                    "Proposer cannot approve their own policy-change request.");
        }
    }

    /**
     * Codex iter-3 absorb — membership check normalises both the
     * needle and each stored id with {@code trim()} so the delegate
     * duplicate guard cannot be bypassed by a whitespace-padded
     * {@code delegateTo.id}.
     */
    private boolean isCurrentApprover(List<Map<String, Object>> approvers, String actorId) {
        String needle = trimToNull(actorId);
        if (approvers == null || needle == null) {
            return false;
        }
        for (Map<String, Object> a : approvers) {
            if (a == null) {
                continue;
            }
            Object id = a.get("id");
            if (id != null && needle.equals(String.valueOf(id).trim())) {
                return true;
            }
        }
        return false;
    }

    private PolicyChangeApprovalDecision baseDecision(PolicyApprovalDecisionKind kind,
                                                      ApprovalActorDto actor,
                                                      PolicyApprovalStatus previousStatus,
                                                      PolicyApprovalStatus newStatus) {
        PolicyChangeApprovalDecision decision = new PolicyChangeApprovalDecision();
        decision.setKind(kind);
        decision.setActorSubject(actor.id().trim());
        decision.setActorName(actor.name().trim());
        decision.setActorRole(actor.role().trim());
        decision.setPreviousStatus(previousStatus);
        decision.setNewStatus(newStatus);
        decision.setDecidedAt(Instant.now(clock));
        return decision;
    }

    private List<String> sanitizeEvidenceRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        List<String> sanitized = new ArrayList<>(refs.size());
        for (String ref : refs) {
            String trimmed = trimToNull(ref);
            if (trimmed != null) {
                sanitized.add(trimmed);
            }
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    private List<Map<String, Object>> actorListToJson(List<ApprovalActorDto> actors) {
        List<Map<String, Object>> json = new ArrayList<>();
        if (actors == null) {
            return json;
        }
        for (ApprovalActorDto a : actors) {
            json.add(actorToJson(a));
        }
        return json;
    }

    private static Map<String, Object> actorToJson(ApprovalActorDto actor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", actor.id().trim());
        map.put("name", actor.name().trim());
        map.put("role", actor.role().trim());
        return map;
    }

    private List<Map<String, Object>> rewriteApproversForDelegate(
            List<Map<String, Object>> existing,
            ApprovalActorDto delegating,
            ApprovalActorDto delegateTo) {
        // Codex iter-1 P2: actor is guaranteed in-list by the
        // delegate_conflict guard upstream; this is a straight swap.
        List<Map<String, Object>> result = new ArrayList<>();
        boolean replaced = false;
        if (existing != null) {
            for (Map<String, Object> a : existing) {
                Object id = a == null ? null : a.get("id");
                if (!replaced && Objects.equals(String.valueOf(id), delegating.id())) {
                    result.add(actorToJson(delegateTo));
                    replaced = true;
                } else {
                    result.add(a);
                }
            }
        }
        return result;
    }

    private PolicyChangeApprovalDto toDto(PolicyChangeApproval entity) {
        return new PolicyChangeApprovalDto(
                entity.getId(),
                "policy_change",
                entity.getTitle(),
                entity.getTarget(),
                new ApprovalActorDto(entity.getProposerSubject(),
                        entity.getProposerName(), entity.getProposerRole()),
                entity.getReason(),
                entity.getEvidenceRefs() == null
                        ? List.of()
                        : List.copyOf(entity.getEvidenceRefs()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeadline(),
                entity.getStatus(),
                jsonToActorList(entity.getCurrentApprovers()),
                entity.getHistory().stream().map(this::toDecisionDto).toList(),
                entity.getChangeKind(),
                entity.getRiskTier(),
                entity.getBeforeState(),
                entity.getAfterState()
        );
    }

    private static List<ApprovalActorDto> jsonToActorList(List<Map<String, Object>> json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        List<ApprovalActorDto> actors = new ArrayList<>(json.size());
        for (Map<String, Object> a : json) {
            if (a == null) {
                continue;
            }
            actors.add(new ApprovalActorDto(
                    String.valueOf(a.getOrDefault("id", "")),
                    String.valueOf(a.getOrDefault("name", "")),
                    String.valueOf(a.getOrDefault("role", ""))
            ));
        }
        return List.copyOf(actors);
    }

    private DecisionRecordDto toDecisionDto(PolicyChangeApprovalDecision d) {
        ApprovalActorDto actor = new ApprovalActorDto(
                d.getActorSubject(), d.getActorName(), d.getActorRole());
        List<String> refs = d.getEvidenceRefs() == null
                ? null
                : List.copyOf(d.getEvidenceRefs());
        return switch (d.getKind()) {
            case APPROVE -> new DecisionRecordDto.Approve(d.getId(), actor,
                    d.getActorRole(), d.getReason(), refs,
                    d.getPreviousStatus(), d.getNewStatus(), d.getDecidedAt());
            case REJECT -> new DecisionRecordDto.Reject(d.getId(), actor,
                    d.getActorRole(), d.getReason(), refs,
                    d.getPreviousStatus(), d.getNewStatus(), d.getDecidedAt());
            case REQUEST_CHANGES -> new DecisionRecordDto.RequestChanges(d.getId(), actor,
                    d.getActorRole(), d.getReason(), refs,
                    d.getPreviousStatus(), d.getNewStatus(), d.getDecidedAt());
            case DELEGATE -> new DecisionRecordDto.Delegate(d.getId(), actor,
                    d.getActorRole(), d.getReason(), refs,
                    d.getPreviousStatus(), d.getNewStatus(), d.getDecidedAt(),
                    new ApprovalActorDto(d.getDelegateSubject(),
                            d.getDelegateName(), d.getDelegateRole()));
            case ATTEST -> new DecisionRecordDto.Attest(d.getId(), actor,
                    d.getActorRole(), d.getReason(), refs,
                    d.getPreviousStatus(), d.getNewStatus(), d.getDecidedAt(),
                    new DecisionAttestationDto(d.getStatement(), d.getAcceptedAt()));
        };
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
