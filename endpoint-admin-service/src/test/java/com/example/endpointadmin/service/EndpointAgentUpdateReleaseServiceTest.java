package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRequest;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseResponse;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRevokeRequest;
import com.example.endpointadmin.exception.AgentUpdateReleaseMakerCheckerViolationException;
import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-031 — signed agent update release catalog tests.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointAgentUpdateReleaseService.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class
})
class EndpointAgentUpdateReleaseServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUBJECT_ALICE = "alice@example.com";
    private static final String SUBJECT_BOB = "bob@example.com";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA512 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private EndpointAgentUpdateReleaseService releaseService;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createReleasePersistsDraftMetadataAndEmitsAudit() {
        AdminAgentUpdateReleaseResponse response = releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE),
                request("endpoint-agent-0.2.0"));

        assertThat(response.status()).isEqualTo(AgentUpdateReleaseStatus.DRAFT);
        assertThat(response.enabled()).isFalse();
        assertThat(response.channel()).isEqualTo(AgentUpdateChannel.PILOT);
        assertThat(response.targetVersion()).isEqualTo("0.2.0-dev");
        assertThat(response.sha256()).isEqualTo(SHA256);
        assertThat(response.sha512()).isEqualTo(SHA512);
        assertThat(response.signerThumbprint())
                .isEqualTo("A".repeat(40));
        assertThat(response.signingTier())
                .isEqualTo(AgentUpdateSigningTier.LAB_ONLY_EVIDENCE);

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointAgentUpdateReleaseService.EVENT_CREATED);
    }

    @Test
    void createReleaseNormalizesColonSeparatedSha256Thumbprint() {
        String colonSeparatedSha256Thumbprint = String.join(":",
                "AA", "AA", "AA", "AA", "AA", "AA", "AA", "AA",
                "AA", "AA", "AA", "AA", "AA", "AA", "AA", "AA",
                "AA", "AA", "AA", "AA", "AA", "AA", "AA", "AA",
                "AA", "AA", "AA", "AA", "AA", "AA", "AA", "AA");
        AdminAgentUpdateReleaseRequest request =
                new AdminAgentUpdateReleaseRequest(
                        "sha256-thumbprint",
                        AgentUpdateChannel.PILOT,
                        "0.2.0",
                        "https://downloads.example.com/endpoint-agent.exe",
                        null,
                        SHA256,
                        null,
                        colonSeparatedSha256Thumbprint,
                        AgentUpdateSigningTier.TRUSTED_SIGNED,
                        25_000_000,
                        null);

        AdminAgentUpdateReleaseResponse response = releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE),
                request);

        assertThat(response.signerThumbprint()).isEqualTo("A".repeat(64));
    }

    @Test
    void createReleaseRejectsOverlongThumbprintBeforePersistence() {
        AdminAgentUpdateReleaseRequest bad = new AdminAgentUpdateReleaseRequest(
                "bad-thumbprint",
                AgentUpdateChannel.PILOT,
                "0.2.0",
                "https://downloads.example.com/endpoint-agent.exe",
                null,
                SHA256,
                null,
                "A".repeat(65),
                AgentUpdateSigningTier.TRUSTED_SIGNED,
                25_000_000,
                null);

        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("signerThumbprint must be a 40 or 64");
    }

    @Test
    void createReleaseRejectsOverlongFieldsBeforePersistence() {
        AdminAgentUpdateReleaseRequest badReleaseId =
                new AdminAgentUpdateReleaseRequest(
                        "r".repeat(129),
                        AgentUpdateChannel.PILOT,
                        "0.2.0",
                        "https://downloads.example.com/endpoint-agent.exe",
                        null,
                        SHA256,
                        null,
                        "A".repeat(40),
                        AgentUpdateSigningTier.TRUSTED_SIGNED,
                        25_000_000,
                        null);
        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), badReleaseId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("releaseId must be at most 128");

        AdminAgentUpdateReleaseRequest badBinaryUrl =
                new AdminAgentUpdateReleaseRequest(
                        "bad-binary-url",
                        AgentUpdateChannel.PILOT,
                        "0.2.0",
                        "https://downloads.example.com/"
                                + "a".repeat(2049),
                        null,
                        SHA256,
                        null,
                        "A".repeat(40),
                        AgentUpdateSigningTier.TRUSTED_SIGNED,
                        25_000_000,
                        null);
        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), badBinaryUrl))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("binaryUrl must be at most 2048");

        AdminAgentUpdateReleaseRequest badReleaseNotes =
                new AdminAgentUpdateReleaseRequest(
                        "bad-release-notes",
                        AgentUpdateChannel.PILOT,
                        "0.2.0",
                        "https://downloads.example.com/endpoint-agent.exe",
                        null,
                        SHA256,
                        null,
                        "A".repeat(40),
                        AgentUpdateSigningTier.TRUSTED_SIGNED,
                        25_000_000,
                        "n".repeat(2049));
        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), badReleaseNotes))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("releaseNotes must be at most 2048");
    }

    @Test
    void createReleaseRejectsSignedUrlLikeQueryParameters() {
        AdminAgentUpdateReleaseRequest bad = new AdminAgentUpdateReleaseRequest(
                "bad-url",
                AgentUpdateChannel.PILOT,
                "0.2.0",
                "https://downloads.example.com/endpoint-agent.exe?token=secret",
                null,
                SHA256,
                null,
                "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA",
                AgentUpdateSigningTier.TRUSTED_SIGNED,
                25_000_000,
                null);

        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must not contain userinfo, query or fragment");
    }

    @Test
    void createReleaseRejectsUnparseableVersionAndInvalidHash() {
        AdminAgentUpdateReleaseRequest badVersion = new AdminAgentUpdateReleaseRequest(
                "bad-version",
                AgentUpdateChannel.PILOT,
                "latest",
                "https://downloads.example.com/endpoint-agent.exe",
                null,
                SHA256,
                null,
                "A".repeat(40),
                AgentUpdateSigningTier.TRUSTED_SIGNED,
                25_000_000,
                null);

        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), badVersion))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("targetVersion must be parseable");

        AdminAgentUpdateReleaseRequest badHash = new AdminAgentUpdateReleaseRequest(
                "bad-hash",
                AgentUpdateChannel.PILOT,
                "0.2.0",
                "https://downloads.example.com/endpoint-agent.exe",
                null,
                "z".repeat(64),
                null,
                "A".repeat(40),
                AgentUpdateSigningTier.TRUSTED_SIGNED,
                25_000_000,
                null);

        assertThatThrownBy(() -> releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE), badHash))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void approveReleaseRequiresDifferentApproverAndEmitsRejectAudit() {
        releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE),
                request("self-approve"));

        assertThatThrownBy(() -> releaseService.approveRelease(
                context(TENANT_A, SUBJECT_ALICE),
                "self-approve"))
                .isInstanceOf(AgentUpdateReleaseMakerCheckerViolationException.class)
                .extracting(ex -> ((AgentUpdateReleaseMakerCheckerViolationException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        flushAndClear();
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointAgentUpdateReleaseService
                        .EVENT_APPROVAL_REJECTED_MAKER_CHECKER);
        AdminAgentUpdateReleaseResponse current = releaseService.getRelease(
                context(TENANT_A, SUBJECT_BOB),
                "self-approve");
        assertThat(current.status()).isEqualTo(AgentUpdateReleaseStatus.DRAFT);
        assertThat(current.enabled()).isFalse();
    }

    @Test
    void approveReleaseHappyPathEnablesReleaseAndListFiltersIt() {
        releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE),
                request("approved"));

        AdminAgentUpdateReleaseResponse approved =
                releaseService.approveRelease(
                        context(TENANT_A, SUBJECT_BOB),
                        "approved");

        assertThat(approved.status())
                .isEqualTo(AgentUpdateReleaseStatus.APPROVED);
        assertThat(approved.enabled()).isTrue();
        assertThat(approved.approvedBySubject()).isEqualTo(SUBJECT_BOB);

        var page = releaseService.listReleases(
                context(TENANT_A, SUBJECT_BOB),
                AgentUpdateChannel.PILOT,
                AgentUpdateReleaseStatus.APPROVED,
                true,
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).releaseId()).isEqualTo("approved");
    }

    @Test
    void revokeReleaseDisablesReleaseAndTenantIsolationHolds() {
        releaseService.createRelease(
                context(TENANT_A, SUBJECT_ALICE),
                request("revoked"));
        releaseService.approveRelease(
                context(TENANT_A, SUBJECT_BOB),
                "revoked");

        AdminAgentUpdateReleaseResponse revoked =
                releaseService.revokeRelease(
                        context(TENANT_A, SUBJECT_BOB),
                        "revoked",
                        new AdminAgentUpdateReleaseRevokeRequest(
                                "superseded by 0.2.1"));

        assertThat(revoked.status()).isEqualTo(AgentUpdateReleaseStatus.REVOKED);
        assertThat(revoked.enabled()).isFalse();
        assertThat(revoked.revocationReason()).isEqualTo("superseded by 0.2.1");

        assertThatThrownBy(() -> releaseService.getRelease(
                context(TENANT_B, SUBJECT_BOB),
                "revoked"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex)
                        .getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    private AdminAgentUpdateReleaseRequest request(String releaseId) {
        return new AdminAgentUpdateReleaseRequest(
                releaseId,
                AgentUpdateChannel.PILOT,
                "0.2.0-dev",
                "https://downloads.example.com/endpoint-agent.exe",
                "https://downloads.example.com/endpoint-agent.json",
                SHA256,
                SHA512,
                "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA",
                AgentUpdateSigningTier.LAB_ONLY_EVIDENCE,
                25_000_000,
                "lab-only evidence release");
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> auditEventTypes(UUID tenantId) {
        return auditRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(tenantId)
                .stream()
                .map(EndpointAuditEvent::getEventType)
                .toList();
    }

    private AdminTenantContext context(UUID tenantId, String subject) {
        return new AdminTenantContext(tenantId, subject);
    }
}
