package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave-12 PR-5 (Codex iter-2 absorb) — JSON contract round-trip with
 * the platform-web {@code @mfe/design-system} approval types.
 *
 * <p>Asserts the wire shape directly so the front-end consuming
 * {@code ApprovalRequest} + {@code DecisionRecord} can drop its
 * {@code approvalsApiRepository} adapter in without a renaming
 * shim:
 *
 * <ul>
 *   <li>{@code DecisionRecord} discriminator key is {@code "action"},
 *       not {@code "kind"};</li>
 *   <li>Discriminator values are lowercase snake_case
 *       ({@code "request_changes"}, not {@code "REQUEST_CHANGES"});</li>
 *   <li>Status / changeKind / riskTier enums round-trip as lowercase
 *       strings ({@code "in_review"}, {@code "update"},
 *       {@code "medium"});</li>
 *   <li>{@code attest} carries a nested {@code attestation} object —
 *       NOT flat {@code statement} + {@code acceptedAt} fields;</li>
 *   <li>{@code delegate} carries {@code delegateTo} as a top-level
 *       {@code ApprovalActor};</li>
 *   <li>{@code DecisionRecordBase} fields ({@code id},
 *       {@code actorRole}, {@code reason}, {@code evidenceRefs},
 *       {@code previousStatus}, {@code newStatus}, {@code timestamp})
 *       are present on every variant.</li>
 * </ul>
 */
class PolicyChangeApprovalContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void statusEnumSerializesAsLowercaseSnakeCase() throws Exception {
        assertThat(MAPPER.writeValueAsString(PolicyApprovalStatus.PENDING))
                .isEqualTo("\"pending\"");
        assertThat(MAPPER.writeValueAsString(PolicyApprovalStatus.IN_REVIEW))
                .isEqualTo("\"in_review\"");
        assertThat(MAPPER.writeValueAsString(PolicyApprovalStatus.WITHDRAWN))
                .isEqualTo("\"withdrawn\"");
        // Round-trip from lowercase wire back to enum.
        assertThat(MAPPER.readValue("\"in_review\"", PolicyApprovalStatus.class))
                .isEqualTo(PolicyApprovalStatus.IN_REVIEW);
    }

    @Test
    void changeKindAndRiskTierSerializeAsLowercase() throws Exception {
        assertThat(MAPPER.writeValueAsString(PolicyChangeKind.UPDATE))
                .isEqualTo("\"update\"");
        assertThat(MAPPER.writeValueAsString(PolicyRiskTier.MEDIUM))
                .isEqualTo("\"medium\"");
    }

    @Test
    void approveDecisionUsesActionDiscriminator() throws Exception {
        DecisionRecordDto.Approve approve = new DecisionRecordDto.Approve(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new ApprovalActorDto("alice", "Alice", "policy_author"),
                "policy_author",
                "looks good",
                List.of("ticket-1"),
                PolicyApprovalStatus.PENDING,
                PolicyApprovalStatus.APPROVED,
                Instant.parse("2026-05-29T10:00:00Z"));

        String json = MAPPER.writeValueAsString((DecisionRecordDto) approve);

        assertThat(json).contains("\"action\":\"approve\"");
        assertThat(json).contains("\"previousStatus\":\"pending\"");
        assertThat(json).contains("\"newStatus\":\"approved\"");
        assertThat(json).contains("\"actorRole\":\"policy_author\"");
        assertThat(json).contains("\"evidenceRefs\":[\"ticket-1\"]");
        assertThat(json).contains("\"timestamp\":\"2026-05-29T10:00:00Z\"");
        // No "kind" discriminator leakage from the previous shape.
        assertThat(json).doesNotContain("\"kind\":");
    }

    @Test
    void requestChangesDiscriminatorWiresAsRequestChangesSnakeCase() throws Exception {
        DecisionRecordDto.RequestChanges rc = new DecisionRecordDto.RequestChanges(
                UUID.randomUUID(),
                new ApprovalActorDto("bob", "Bob", "security_reviewer"),
                "security_reviewer",
                "add ticket ref",
                null,
                PolicyApprovalStatus.PENDING,
                PolicyApprovalStatus.IN_REVIEW,
                Instant.parse("2026-05-29T10:01:00Z"));

        String json = MAPPER.writeValueAsString((DecisionRecordDto) rc);

        assertThat(json).contains("\"action\":\"request_changes\"");
        assertThat(json).contains("\"newStatus\":\"in_review\"");
    }

    @Test
    void delegateCarriesDelegateToActor() throws Exception {
        DecisionRecordDto.Delegate delegate = new DecisionRecordDto.Delegate(
                UUID.randomUUID(),
                new ApprovalActorDto("bob", "Bob", "security_reviewer"),
                "security_reviewer",
                "on vacation",
                null,
                PolicyApprovalStatus.PENDING,
                PolicyApprovalStatus.PENDING,
                Instant.parse("2026-05-29T10:02:00Z"),
                new ApprovalActorDto("dave", "Dave", "compliance_lead"));

        String json = MAPPER.writeValueAsString((DecisionRecordDto) delegate);

        assertThat(json).contains("\"action\":\"delegate\"");
        assertThat(json).contains("\"delegateTo\":{\"id\":\"dave\",\"name\":\"Dave\",\"role\":\"compliance_lead\"}");
    }

    @Test
    void attestCarriesNestedAttestationObject() throws Exception {
        DecisionRecordDto.Attest attest = new DecisionRecordDto.Attest(
                UUID.randomUUID(),
                new ApprovalActorDto("bob", "Bob", "security_reviewer"),
                "security_reviewer",
                null,
                null,
                PolicyApprovalStatus.PENDING,
                PolicyApprovalStatus.PENDING,
                Instant.parse("2026-05-29T10:03:00Z"),
                new DecisionAttestationDto(
                        "I have reviewed the policy",
                        Instant.parse("2026-05-29T10:02:55Z")));

        String json = MAPPER.writeValueAsString((DecisionRecordDto) attest);

        assertThat(json).contains("\"action\":\"attest\"");
        assertThat(json).contains("\"attestation\":");
        assertThat(json).contains("\"statement\":\"I have reviewed the policy\"");
        assertThat(json).contains("\"acceptedAt\":\"2026-05-29T10:02:55Z\"");
        // Flat statement/acceptedAt should NOT appear at the record root
        // (the contract has them strictly inside `attestation`).
        assertThat(json).doesNotContain(",\"statement\":");
    }

    @Test
    void decisionRecordRoundTripsBackToConcreteVariant() throws Exception {
        String json = "{"
                + "\"action\":\"approve\","
                + "\"id\":\"22222222-2222-2222-2222-222222222222\","
                + "\"actor\":{\"id\":\"bob\",\"name\":\"Bob\",\"role\":\"security_reviewer\"},"
                + "\"actorRole\":\"security_reviewer\","
                + "\"reason\":\"verified\","
                + "\"previousStatus\":\"pending\","
                + "\"newStatus\":\"approved\","
                + "\"timestamp\":\"2026-05-29T10:00:00Z\""
                + "}";

        DecisionRecordDto record = MAPPER.readValue(json, DecisionRecordDto.class);

        assertThat(record).isInstanceOf(DecisionRecordDto.Approve.class);
        assertThat(record.actor().id()).isEqualTo("bob");
        assertThat(record.previousStatus()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(record.newStatus()).isEqualTo(PolicyApprovalStatus.APPROVED);
    }
}
