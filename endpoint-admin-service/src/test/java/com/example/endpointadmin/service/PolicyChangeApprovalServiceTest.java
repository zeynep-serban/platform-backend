package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
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
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import com.example.endpointadmin.security.AdminTenantContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave-12 PR-5 — service-layer tests for the policy-change approval
 * workflow. Covers the happy path through {@code propose} + all five
 * decision endpoints, the 4-eyes guard, the Codex iter-1 actor-identity
 * guard ({@code actor_mismatch}), delegate-not-in-list
 * ({@code delegate_conflict}), and status-transition conflict cases.
 *
 * <p>Each test threads its own {@code AdminTenantContext} for the actor
 * making the call, so the server-side {@code actor.id == context.subject()}
 * guard is genuinely exercised (not blanket-bypassed by a single test
 * subject).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TimeConfig.class, PolicyChangeApprovalService.class})
class PolicyChangeApprovalServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final ApprovalActorDto PROPOSER = new ApprovalActorDto(
            "alice", "Alice Aerni", "policy_author");

    private static final ApprovalActorDto APPROVER = new ApprovalActorDto(
            "bob", "Bob Berkeley", "security_reviewer");

    private static final ApprovalActorDto APPROVER_TWO = new ApprovalActorDto(
            "carol", "Carol Chen", "compliance_lead");

    private static final ApprovalActorDto NON_APPROVER = new ApprovalActorDto(
            "mallory", "Mallory Malice", "manager");

    @Autowired
    private PolicyChangeApprovalService service;

    @Test
    void proposePersistsApprovalAndReturnsPendingDto() {
        PolicyChangeApprovalDto dto = service.propose(asProposer(),
                proposeRequest("pol-001"));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.type()).isEqualTo("policy_change");
        assertThat(dto.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(dto.target()).isEqualTo("pol-001");
        assertThat(dto.changeKind()).isEqualTo(PolicyChangeKind.UPDATE);
        assertThat(dto.riskTier()).isEqualTo(PolicyRiskTier.MEDIUM);
        assertThat(dto.history()).isEmpty();
        assertThat(dto.currentApprovers())
                .extracting(ApprovalActorDto::id)
                .containsExactly(APPROVER.id(), APPROVER_TWO.id());
        assertThat(dto.proposer().id()).isEqualTo(PROPOSER.id());
    }

    @Test
    void proposeWithMismatchedProposerIdYieldsActorMismatch() {
        // Codex iter-1 P1 + iter-2 — Mallory authenticated but the body
        // claims Alice. Typed exception lets the global handler emit
        // a stable {@code error=actor_mismatch} body (Codex iter-2 P1).
        ProposePolicyChangeRequest spoofed = new ProposePolicyChangeRequest(
                "Update DLP exfil rule", "pol-spoof", PROPOSER, "x",
                null, List.of(APPROVER), null,
                PolicyChangeKind.UPDATE, PolicyRiskTier.MEDIUM, null, null);

        assertThatThrownBy(() -> service.propose(as(NON_APPROVER), spoofed))
                .isInstanceOf(PolicyApprovalActorMismatchException.class)
                .hasMessageContaining("must equal the authenticated subject");
    }

    @Test
    void approveByApproverTransitionsToApproved() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-approve"));

        PolicyChangeApprovalDto approved = service.approve(as(APPROVER), created.id(),
                new ApproveRequest(APPROVER, "verified with policy committee", null));

        assertThat(approved.status()).isEqualTo(PolicyApprovalStatus.APPROVED);
        assertThat(approved.history()).hasSize(1);
        DecisionRecordDto first = approved.history().get(0);
        assertThat(first).isInstanceOf(DecisionRecordDto.Approve.class);
        DecisionRecordDto.Approve approve = (DecisionRecordDto.Approve) first;
        assertThat(approve.actor().id()).isEqualTo(APPROVER.id());
        assertThat(approve.actorRole()).isEqualTo(APPROVER.role());
        assertThat(approve.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(approve.newStatus()).isEqualTo(PolicyApprovalStatus.APPROVED);
        assertThat(approve.reason()).isEqualTo("verified with policy committee");
    }

    @Test
    void approveWithMismatchedActorIdYieldsActorMismatch() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-am"));

        // Codex iter-1 P1 — authenticated as Bob but body says Carol.
        assertThatThrownBy(() -> service.approve(as(APPROVER), created.id(),
                new ApproveRequest(APPROVER_TWO, "spoof", null)))
                .isInstanceOf(PolicyApprovalActorMismatchException.class)
                .hasMessageContaining("must equal the authenticated subject");
    }

    @Test
    void proposerCannotApproveOwnRequest() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-self"));

        // Codex iter-1 P1 chain — authenticated as PROPOSER, body actor
        // is also PROPOSER (passes actor_mismatch guard) but the 4-eyes
        // guard rejects the self-approval.
        assertThatThrownBy(() -> service.approve(asProposer(), created.id(),
                new ApproveRequest(PROPOSER, "self sign-off", null)))
                .isInstanceOf(PolicyApprovalProposerSelfException.class)
                .hasMessageContaining("Proposer cannot approve");

        assertThat(service.get(asProposer(), created.id()).status())
                .isEqualTo(PolicyApprovalStatus.PENDING);
    }

    @Test
    void rejectMovesToRejectedTerminalState() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-reject"));

        PolicyChangeApprovalDto rejected = service.reject(as(APPROVER), created.id(),
                new RejectRequest(APPROVER, "scope exceeds the original ticket",
                        List.of("ticket-999")));

        assertThat(rejected.status()).isEqualTo(PolicyApprovalStatus.REJECTED);
        DecisionRecordDto.Reject record =
                (DecisionRecordDto.Reject) rejected.history().get(0);
        assertThat(record.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(record.newStatus()).isEqualTo(PolicyApprovalStatus.REJECTED);
        assertThat(record.evidenceRefs()).containsExactly("ticket-999");
    }

    @Test
    void requestChangesMovesToInReviewAndStaysOpen() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-rc"));

        PolicyChangeApprovalDto inReview = service.requestChanges(as(APPROVER), created.id(),
                new RequestChangesRequest(APPROVER,
                        "please add the auditor sign-off ref", null));

        assertThat(inReview.status()).isEqualTo(PolicyApprovalStatus.IN_REVIEW);
        DecisionRecordDto.RequestChanges record =
                (DecisionRecordDto.RequestChanges) inReview.history().get(0);
        assertThat(record.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(record.newStatus()).isEqualTo(PolicyApprovalStatus.IN_REVIEW);

        // Approver-two can then approve from IN_REVIEW.
        PolicyChangeApprovalDto approved = service.approve(as(APPROVER_TWO), created.id(),
                new ApproveRequest(APPROVER_TWO, null, null));
        assertThat(approved.status()).isEqualTo(PolicyApprovalStatus.APPROVED);
        assertThat(approved.history()).hasSize(2);
        DecisionRecordDto.Approve approve =
                (DecisionRecordDto.Approve) approved.history().get(1);
        assertThat(approve.previousStatus()).isEqualTo(PolicyApprovalStatus.IN_REVIEW);
    }

    @Test
    void delegateSwapsActorWithDelegateAndStaysOpen() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-delegate"));

        PolicyChangeApprovalDto delegated = service.delegate(as(APPROVER), created.id(),
                new DelegateRequest(APPROVER, NON_APPROVER,
                        "on vacation — handing to manager", null));

        // Codex iter-1 P2 absorb — straight in-place swap. Bob (an
        // approver) is replaced by Mallory; Carol (the other approver)
        // is preserved.
        assertThat(delegated.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(delegated.currentApprovers())
                .extracting(ApprovalActorDto::id)
                .containsExactly(NON_APPROVER.id(), APPROVER_TWO.id());

        DecisionRecordDto.Delegate record =
                (DecisionRecordDto.Delegate) delegated.history().get(0);
        assertThat(record.actor().id()).isEqualTo(APPROVER.id());
        assertThat(record.delegateTo().id()).isEqualTo(NON_APPROVER.id());
        assertThat(record.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(record.newStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
    }

    @Test
    void delegateByNonApproverYieldsDelegateConflict() {
        // Codex iter-1 P2 — Mallory authenticates and tries to delegate,
        // but she is not on the current approver list.
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-delegate-conflict"));

        assertThatThrownBy(() -> service.delegate(as(NON_APPROVER), created.id(),
                new DelegateRequest(NON_APPROVER, APPROVER_TWO, null, null)))
                .isInstanceOf(PolicyApprovalDelegateConflictException.class)
                .hasMessageContaining("not on the current approver list");
    }

    @Test
    void proposerCannotApproveOwnRequestWithWhitespacePaddedActorId() {
        // Codex iter-3 absorb — `actor.id` with leading/trailing
        // whitespace would previously slip past
        // requireActorMatchesSubject (trimmed compare) but bypass
        // guardProposerSelfApprove (raw compare). The id is now
        // rejected outright as non-canonical — the 4-eyes guard
        // cannot be evaded by a stealth-encoded identity.
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-self-ws"));

        ApprovalActorDto padded = new ApprovalActorDto(
                PROPOSER.id() + " ", PROPOSER.name(), PROPOSER.role());

        assertThatThrownBy(() -> service.approve(asProposer(), created.id(),
                new ApproveRequest(padded, "stealth self-sign-off", null)))
                .isInstanceOf(PolicyApprovalActorMismatchException.class)
                .hasMessageContaining("whitespace");

        assertThat(service.get(asProposer(), created.id()).status())
                .isEqualTo(PolicyApprovalStatus.PENDING);
    }

    @Test
    void delegateToExistingApproverWithWhitespacePaddedTargetYieldsDelegateConflict() {
        // Codex iter-3 absorb — even with `delegateTo.id` cosmetically
        // padded ("carol "), the canonical-id check + trim-normalised
        // membership comparison rejects the request: padded ids fail
        // the upstream canonical-form guard with 400.
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-delegate-dup-ws"));

        ApprovalActorDto paddedCarol = new ApprovalActorDto(
                APPROVER_TWO.id() + " ", APPROVER_TWO.name(),
                APPROVER_TWO.role());

        assertResponseStatus(
                () -> service.delegate(as(APPROVER), created.id(),
                        new DelegateRequest(APPROVER, paddedCarol,
                                "padded duplicate", null)),
                HttpStatus.BAD_REQUEST,
                "whitespace");
    }

    @Test
    void delegateToExistingApproverYieldsDelegateConflict() {
        // Codex iter-2 P3 absorb — Bob (approver) delegates to Carol
        // (also approver). In-place swap would duplicate Carol on the
        // approver list, so 400 delegate_conflict instead.
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-delegate-dup"));

        assertThatThrownBy(() -> service.delegate(as(APPROVER), created.id(),
                new DelegateRequest(APPROVER, APPROVER_TWO,
                        "duplicate scenario", null)))
                .isInstanceOf(PolicyApprovalDelegateConflictException.class)
                .hasMessageContaining("already on the current approver list");
    }

    @Test
    void delegateToSelfIsRejectedWith400() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-delegate-self"));

        assertResponseStatus(
                () -> service.delegate(as(APPROVER), created.id(),
                        new DelegateRequest(APPROVER, APPROVER, null, null)),
                HttpStatus.BAD_REQUEST,
                "differ");
    }

    @Test
    void attestAppendsRecordWithNestedAttestationButKeepsStatus() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-attest"));

        Instant acceptedAt = Instant.now();
        PolicyChangeApprovalDto attested = service.attest(as(APPROVER), created.id(),
                new AttestRequest(APPROVER,
                        new DecisionAttestationDto(
                                "I have reviewed the policy and accept ownership",
                                acceptedAt),
                        null));

        assertThat(attested.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        DecisionRecordDto.Attest record =
                (DecisionRecordDto.Attest) attested.history().get(0);
        assertThat(record.attestation()).isNotNull();
        assertThat(record.attestation().statement()).contains("accept ownership");
        assertThat(record.attestation().acceptedAt()).isEqualTo(acceptedAt);
        assertThat(record.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(record.newStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
    }

    @Test
    void decisionsOnTerminalApprovedAreRejectedWith409() {
        PolicyChangeApprovalDto created = service.propose(asProposer(),
                proposeRequest("pol-terminal"));
        service.approve(as(APPROVER), created.id(),
                new ApproveRequest(APPROVER, "looks good", null));

        assertResponseStatus(
                () -> service.approve(as(APPROVER_TWO), created.id(),
                        new ApproveRequest(APPROVER_TWO, "double approve", null)),
                HttpStatus.CONFLICT,
                "not open");
    }

    @Test
    void getOnUnknownIdYields404() {
        assertResponseStatus(
                () -> service.get(asProposer(), UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "not found");
    }

    @Test
    void listFiltersByStatusAndProposer() {
        PolicyChangeApprovalDto a = service.propose(asProposer(),
                proposeRequest("pol-list-a"));
        PolicyChangeApprovalDto b = service.propose(asProposer(),
                proposeRequest("pol-list-b"));
        service.reject(as(APPROVER), b.id(),
                new RejectRequest(APPROVER, "out of scope", null));

        List<PolicyChangeApprovalDto> pending = service.list(asProposer(),
                PolicyApprovalStatus.PENDING, null, null);
        assertThat(pending).extracting(PolicyChangeApprovalDto::id).contains(a.id());
        assertThat(pending).extracting(PolicyChangeApprovalDto::id).doesNotContain(b.id());

        List<PolicyChangeApprovalDto> targeted = service.list(asProposer(),
                null, "pol-list-a", null);
        assertThat(targeted).hasSize(1);
        assertThat(targeted.get(0).id()).isEqualTo(a.id());
    }

    @Test
    void proposeWithPastDeadlineIsRejectedWith400() {
        ProposePolicyChangeRequest request = new ProposePolicyChangeRequest(
                "Update DLP exfil rule",
                "pol-past-deadline",
                PROPOSER,
                "compliance ask",
                List.of("ticket-123"),
                List.of(APPROVER, APPROVER_TWO),
                Instant.now().minusSeconds(60),
                PolicyChangeKind.UPDATE,
                PolicyRiskTier.MEDIUM,
                null,
                null);

        assertResponseStatus(
                () -> service.propose(asProposer(), request),
                HttpStatus.BAD_REQUEST,
                "future");
    }

    // --- helpers --------------------------------------------------------

    private AdminTenantContext asProposer() {
        return as(PROPOSER);
    }

    private AdminTenantContext as(ApprovalActorDto actor) {
        return new AdminTenantContext(TENANT_ID, actor.id());
    }

    private ProposePolicyChangeRequest proposeRequest(String target) {
        return new ProposePolicyChangeRequest(
                "Update DLP exfil rule",
                target,
                PROPOSER,
                "ticket-123 escalation",
                List.of("ticket-123", "https://wiki/runbook"),
                List.of(APPROVER, APPROVER_TWO),
                null,
                PolicyChangeKind.UPDATE,
                PolicyRiskTier.MEDIUM,
                Map.of("rules", List.of("allow:*")),
                Map.of("rules", List.of("deny:exfil-*")));
    }

    private void assertResponseStatus(ThrowingCallable callable,
                                      HttpStatus expected,
                                      String messageFragment) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected))
                .hasMessageContaining(messageFragment);
    }
}
