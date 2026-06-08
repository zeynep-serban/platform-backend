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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #508 slice-2b — unit coverage for {@link EndpointDisplayPolicyService}.
 *
 * <p>The keystone assertion (Codex 019ea911 RED-fix) is
 * {@link #enforce_happy_writesRevisionAndPendingCommand_butNeverCurrent()}: the
 * dispatch path writes ONLY a revision + a PENDING command and NEVER touches the
 * current desired-state row (that promotion is the approval listener's job).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndpointDisplayPolicyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID APPROVED_CMD = UUID.randomUUID();
    private static final String SUBJECT = "alice@example.com";
    private static final Instant NOW = Instant.parse("2026-06-09T10:00:00Z");

    @Mock private EndpointDisplayPolicyRepository policyRepository;
    @Mock private EndpointDisplayPolicyRevisionRepository revisionRepository;
    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointDeviceRepository deviceRepository;
    @Mock private EndpointHeartbeatRepository heartbeatRepository;
    @Mock private EndpointAuditService auditService;

    private EndpointDisplayPolicyService service;

    private final AdminTenantContext context = new AdminTenantContext(TENANT, SUBJECT);

    @BeforeEach
    void setUp() {
        service = newService(true);

        EndpointDevice device = org.mockito.Mockito.mock(EndpointDevice.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getStatus()).thenReturn(com.example.endpointadmin.model.DeviceStatus.ONLINE);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT, DEVICE_ID))
                .thenReturn(Optional.of(device));
        when(deviceRepository.findVisibleToOrgAndId(TENANT, DEVICE_ID))
                .thenReturn(Optional.of(device));

        EndpointHeartbeat hb = org.mockito.Mockito.mock(EndpointHeartbeat.class);
        when(hb.getReceivedAt()).thenReturn(NOW.minusSeconds(30));
        when(hb.getPayload()).thenReturn(Map.of("capabilities", List.of("SET_DISPLAY_POLICY")));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(hb));

        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of());
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(commandRepository.saveAndFlush(any())).thenAnswer(inv -> {
            EndpointCommand c = inv.getArgument(0);
            setCommandId(c, COMMAND_ID);
            return c;
        });
        when(commandRepository.findByTenantIdAndId(TENANT, COMMAND_ID))
                .thenAnswer(inv -> Optional.of(pendingCommand()));
    }

    private EndpointDisplayPolicyService newService(boolean enabled) {
        return new EndpointDisplayPolicyService(
                policyRepository, revisionRepository, commandRepository, deviceRepository,
                heartbeatRepository, auditService, Clock.fixed(NOW, ZoneOffset.UTC),
                enabled, Duration.ofMinutes(5), "SET_DISPLAY_POLICY");
    }

    // ── ENFORCE ──────────────────────────────────────────────────────────────

    @Test
    void enforce_happy_writesRevisionAndPendingCommand_butNeverCurrent() {
        AdminDisplayPolicyResponse res = service.enforce(context, DEVICE_ID, enforceRequest());

        // RED-fix: the current desired-state row is NEVER written at dispatch time.
        verify(policyRepository, never()).save(any());
        // Exactly one revision + one PENDING SET_DISPLAY_POLICY command persisted.
        verify(revisionRepository, times(1)).saveAndFlush(any());
        org.mockito.ArgumentCaptor<EndpointCommand> cmd =
                org.mockito.ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository, times(1)).saveAndFlush(cmd.capture());
        assertThat(cmd.getValue().getCommandType()).isEqualTo(CommandType.SET_DISPLAY_POLICY);
        assertThat(cmd.getValue().getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(cmd.getValue().getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(cmd.getValue().getIssuedBySubject()).isEqualTo(SUBJECT);

        // Response carries the pending proposal; current is null (never approved).
        assertThat(res.openProposal()).isNotNull();
        assertThat(res.openProposal().operation()).isEqualTo("ENFORCE");
        assertThat(res.operation()).isNull();
    }

    @Test
    void enforce_featureFlagOff_throws503() {
        EndpointDisplayPolicyService disabled = newService(false);
        assertThatThrownBy(() -> disabled.enforce(context, DEVICE_ID, enforceRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void enforce_clearOperationToPut_throws400() {
        SetDisplayPolicyRequest req = new SetDisplayPolicyRequest(
                DisplayPolicyOperation.CLEAR, "reason", null, null);
        assertThatThrownBy(() -> service.enforce(context, DEVICE_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void enforce_invalidScrPath_throws400() {
        SetDisplayPolicyRequest req = new SetDisplayPolicyRequest(
                DisplayPolicyOperation.ENFORCE, "kiosk",
                new SetDisplayPolicyRequest.Screensaver(true, 600, true,
                        "c:\\windows\\system32\\evil.scr"),
                null);
        assertThatThrownBy(() -> service.enforce(context, DEVICE_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void enforce_identicalOpenProposal_replaysWithoutNewCommand() {
        EndpointDisplayPolicyRevision open = enforceRevisionMatching(enforceRequest());
        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of(open));

        AdminDisplayPolicyResponse res = service.enforce(context, DEVICE_ID, enforceRequest());

        verify(commandRepository, never()).saveAndFlush(any());
        verify(revisionRepository, never()).saveAndFlush(any());
        assertThat(res.openProposal()).isNotNull();
        assertThat(res.openProposal().revisionId()).isEqualTo(open.getId());
    }

    @Test
    void enforce_differentOpenProposal_throws409() {
        EndpointDisplayPolicyRevision open = enforceRevisionMatching(enforceRequest());
        open.setPolicyHashSha256("b".repeat(64)); // different hash
        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.enforce(context, DEVICE_ID, enforceRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void enforce_currentAlreadyIdentical_isIdempotentNoOp() {
        EndpointDisplayPolicyRevision sameValues = enforceRevisionMatching(enforceRequest());
        EndpointDisplayPolicy current = new EndpointDisplayPolicy();
        current.setId(UUID.randomUUID());
        current.setTenantId(TENANT);
        current.setDeviceId(DEVICE_ID);
        current.setOperation(DisplayPolicyOperation.ENFORCE);
        current.setClearedAt(null);
        current.setPolicyHashSha256(sameValues.getPolicyHashSha256());
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(current));

        AdminDisplayPolicyResponse res = service.enforce(context, DEVICE_ID, enforceRequest());

        verify(commandRepository, never()).saveAndFlush(any());
        assertThat(res.operation()).isEqualTo("ENFORCE");
        assertThat(res.openProposal()).isNull();
    }

    @Test
    void enforce_staleHeartbeat_throws424() {
        EndpointHeartbeat stale = org.mockito.Mockito.mock(EndpointHeartbeat.class);
        when(stale.getReceivedAt()).thenReturn(NOW.minusSeconds(3600));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(stale));
        assertThatThrownBy(() -> service.enforce(context, DEVICE_ID, enforceRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("424");
    }

    @Test
    void enforce_capabilityMissing_throws422() {
        EndpointHeartbeat hb = org.mockito.Mockito.mock(EndpointHeartbeat.class);
        when(hb.getReceivedAt()).thenReturn(NOW.minusSeconds(10));
        when(hb.getPayload()).thenReturn(Map.of("capabilities", List.of("COLLECT_INVENTORY")));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(hb));
        assertThatThrownBy(() -> service.enforce(context, DEVICE_ID, enforceRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }

    // ── CLEAR ────────────────────────────────────────────────────────────────

    @Test
    void clear_noCurrentNoOpen_throws404() {
        assertThatThrownBy(() -> service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("undo")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void clear_activeEnforce_createsClearProposal() {
        EndpointDisplayPolicy current = new EndpointDisplayPolicy();
        current.setId(UUID.randomUUID());
        current.setTenantId(TENANT);
        current.setDeviceId(DEVICE_ID);
        current.setOperation(DisplayPolicyOperation.ENFORCE);
        current.setPolicyHashSha256("a".repeat(64));
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(current));

        AdminDisplayPolicyResponse res = service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("undo"));

        org.mockito.ArgumentCaptor<EndpointDisplayPolicyRevision> rev =
                org.mockito.ArgumentCaptor.forClass(EndpointDisplayPolicyRevision.class);
        verify(revisionRepository, times(1)).saveAndFlush(rev.capture());
        assertThat(rev.getValue().getOperation()).isEqualTo(DisplayPolicyOperation.CLEAR);
        verify(commandRepository, times(1)).saveAndFlush(any());
        verify(policyRepository, never()).save(any()); // current promoted only on approve
        assertThat(res.openProposal().operation()).isEqualTo("CLEAR");
    }

    @Test
    void clear_alreadyCleared_isNoOp() {
        EndpointDisplayPolicy current = new EndpointDisplayPolicy();
        current.setId(UUID.randomUUID());
        current.setTenantId(TENANT);
        current.setDeviceId(DEVICE_ID);
        current.setOperation(DisplayPolicyOperation.CLEAR);
        current.setClearedAt(NOW);
        current.setPolicyHashSha256("a".repeat(64));
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(current));

        AdminDisplayPolicyResponse res = service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("undo"));

        verify(commandRepository, never()).saveAndFlush(any());
        assertThat(res.operation()).isEqualTo("CLEAR");
    }

    @Test
    void clear_openProposalApprovedEnforce_allowedAtProposeNoSupersedeYet() {
        // After enforce→approve (current=ENFORCE, command APPROVED+QUEUED), a CLEAR
        // is ALLOWED at propose time and DOES NOT cancel the approved command here
        // — supersede is deferred to the CLEAR's own approval (Codex 019ea92f), so
        // a not-yet-approved/rejectable CLEAR never suppresses approved delivery.
        EndpointDisplayPolicyRevision openApproved = approvedEnforceOpenProposal();
        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of(openApproved));
        EndpointDisplayPolicy current = new EndpointDisplayPolicy();
        current.setId(UUID.randomUUID());
        current.setTenantId(TENANT);
        current.setDeviceId(DEVICE_ID);
        current.setOperation(DisplayPolicyOperation.ENFORCE);
        current.setPolicyHashSha256("c".repeat(64));
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(current));

        AdminDisplayPolicyResponse res =
                service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("undo"));

        verify(revisionRepository, times(1)).saveAndFlush(any());
        verify(commandRepository, times(1)).saveAndFlush(any()); // new CLEAR command
        verify(commandRepository, never()).save(any());           // NOTHING cancelled at propose time
        assertThat(res.openProposal().operation()).isEqualTo("CLEAR");
    }

    @Test
    void clear_openProposalPendingEnforce_throws409() {
        // A still-PENDING ENFORCE (awaiting maker-checker) DOES block a CLEAR.
        EndpointDisplayPolicyRevision openPending = enforceRevisionMatching(enforceRequest());
        openPending.setPolicyHashSha256("d".repeat(64));
        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of(openPending));
        // openPending.commandId == COMMAND_ID → setUp stub returns a PENDING command.

        assertThatThrownBy(() -> service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("undo")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void enforce_openProposalApprovedDifferent_allowedAtProposeNoSupersedeYet() {
        EndpointDisplayPolicyRevision openApproved = approvedEnforceOpenProposal();
        when(revisionRepository.findOpenProposals(TENANT, DEVICE_ID)).thenReturn(List.of(openApproved));
        // new ENFORCE has a different hash than the approved open proposal ("c"*64).

        AdminDisplayPolicyResponse res = service.enforce(context, DEVICE_ID, enforceRequest());

        verify(commandRepository, times(1)).saveAndFlush(any());
        verify(commandRepository, never()).save(any()); // no propose-time cancel
        assertThat(res.openProposal().operation()).isEqualTo("ENFORCE");
    }

    @Test
    void clear_blankReason_throws400() {
        assertThatThrownBy(() -> service.clear(context, DEVICE_ID, new ClearDisplayPolicyRequest("  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    void get_nothing_throws404() {
        assertThatThrownBy(() -> service.get(context, DEVICE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void get_currentPresent_returnsMapped() {
        EndpointDisplayPolicy current = new EndpointDisplayPolicy();
        current.setId(UUID.randomUUID());
        current.setTenantId(TENANT);
        current.setDeviceId(DEVICE_ID);
        current.setOperation(DisplayPolicyOperation.ENFORCE);
        current.setWallpaperStyle(WallpaperStyle.FILL);
        current.setPolicyHashSha256("a".repeat(64));
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(current));

        AdminDisplayPolicyResponse res = service.get(context, DEVICE_ID);
        assertThat(res.operation()).isEqualTo("ENFORCE");
        assertThat(res.wallpaper().style()).isEqualTo("FILL");
        assertThat(res.openProposal()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SetDisplayPolicyRequest enforceRequest() {
        return new SetDisplayPolicyRequest(
                DisplayPolicyOperation.ENFORCE,
                "kiosk lockdown",
                new SetDisplayPolicyRequest.Screensaver(true, 600, true,
                        "c:\\windows\\system32\\scrnsave.scr"),
                new SetDisplayPolicyRequest.Wallpaper(true, "fill", true,
                        "wallpapers/corp.png", null, "image/png"));
    }

    /** A revision whose hash matches what the service computes for {@code enforceRequest()}. */
    private static EndpointDisplayPolicyRevision enforceRevisionMatching(SetDisplayPolicyRequest req) {
        EndpointDisplayPolicyRevision r = new EndpointDisplayPolicyRevision();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT);
        r.setDeviceId(DEVICE_ID);
        r.setOperation(DisplayPolicyOperation.ENFORCE);
        r.setScreensaverEnabled(req.screensaver().enabled());
        r.setScreensaverTimeoutSeconds(req.screensaver().timeoutSeconds());
        r.setScreensaverSecure(req.screensaver().secureOnResume());
        r.setScreensaverScrPath(req.screensaver().scrPath());
        r.setWallpaperEnabled(req.wallpaper().enabled());
        r.setWallpaperStyle(WallpaperStyle.valueOf(
                req.wallpaper().style().toUpperCase(java.util.Locale.ROOT)));
        r.setWallpaperUserCannotChange(req.wallpaper().userCannotChange());
        r.setWallpaperAssetRef(req.wallpaper().assetRef());
        r.setWallpaperContentType(req.wallpaper().contentType());
        r.setReason(req.reason());
        r.setCommandId(COMMAND_ID);
        r.setPolicyHashSha256(EndpointDisplayPolicyService.computeHash(r));
        return r;
    }

    private static EndpointCommand pendingCommand() {
        EndpointCommand c = new EndpointCommand();
        c.setCommandType(CommandType.SET_DISPLAY_POLICY);
        c.setApprovalStatus(ApprovalStatus.PENDING);
        c.setStatus(CommandStatus.QUEUED);
        setCommandId(c, COMMAND_ID);
        return c;
    }

    private static EndpointCommand approvedCommand() {
        EndpointCommand c = new EndpointCommand();
        c.setCommandType(CommandType.SET_DISPLAY_POLICY);
        c.setApprovalStatus(ApprovalStatus.APPROVED);
        c.setStatus(CommandStatus.QUEUED);
        setCommandId(c, APPROVED_CMD);
        return c;
    }

    /** An ENFORCE open proposal whose backing command is APPROVED (enacted). */
    private EndpointDisplayPolicyRevision approvedEnforceOpenProposal() {
        EndpointDisplayPolicyRevision r = new EndpointDisplayPolicyRevision();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT);
        r.setDeviceId(DEVICE_ID);
        r.setOperation(DisplayPolicyOperation.ENFORCE);
        r.setScreensaverEnabled(true);
        r.setPolicyHashSha256("c".repeat(64));
        r.setCommandId(APPROVED_CMD);
        when(commandRepository.findByTenantIdAndId(TENANT, APPROVED_CMD))
                .thenReturn(Optional.of(approvedCommand()));
        return r;
    }

    private static void setCommandId(EndpointCommand c, UUID id) {
        try {
            Field f = EndpointCommand.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
