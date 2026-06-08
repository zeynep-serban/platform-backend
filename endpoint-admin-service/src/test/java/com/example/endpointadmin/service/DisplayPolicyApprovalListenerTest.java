package com.example.endpointadmin.service;

import com.example.endpointadmin.event.CommandApprovalDecidedEvent;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;
import com.example.endpointadmin.model.WallpaperStyle;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * #508 slice-2b — unit coverage for {@link DisplayPolicyApprovalListener}, the
 * Codex 019ea911 RED-fix: the current desired-state row is promoted ONLY on
 * APPROVE; a REJECT leaves it untouched so a rejected proposal never becomes
 * current truth.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisplayPolicyApprovalListenerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID PRIOR_CMD = UUID.randomUUID();
    private static final UUID REVISION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-09T10:00:00Z");
    private static final String APPROVER = "bob@example.com";
    private static final String PROPOSER = "alice@example.com";

    @Mock private EndpointDisplayPolicyRevisionRepository revisionRepository;
    @Mock private EndpointDisplayPolicyRepository policyRepository;
    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointAuditService auditService;

    private DisplayPolicyApprovalListener listener;

    @BeforeEach
    void setUp() {
        listener = new DisplayPolicyApprovalListener(
                revisionRepository, policyRepository, commandRepository, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.empty());
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // No prior approved display-policy command by default (no supersede).
        when(commandRepository.findActiveDisplayPolicyCommandIdsForDevice(DEVICE_ID, COMMAND_ID))
                .thenReturn(List.of());
    }

    @Test
    void approveEnforce_promotesCurrentFromRevision() {
        when(revisionRepository.findByTenantIdAndCommandId(TENANT, COMMAND_ID))
                .thenReturn(List.of(enforceRevision()));

        listener.onCommandApprovalDecided(approve());

        ArgumentCaptor<EndpointDisplayPolicy> cap = ArgumentCaptor.forClass(EndpointDisplayPolicy.class);
        verify(policyRepository, times(1)).save(cap.capture());
        EndpointDisplayPolicy saved = cap.getValue();
        assertThat(saved.getOperation()).isEqualTo(DisplayPolicyOperation.ENFORCE);
        assertThat(saved.getClearedAt()).isNull();
        assertThat(saved.getCurrentRevisionId()).isEqualTo(REVISION_ID);
        assertThat(saved.getWallpaperStyle()).isEqualTo(WallpaperStyle.FILL);
        assertThat(saved.getLastUpdatedBySubject()).isEqualTo(APPROVER);
        assertThat(saved.getCreatedBySubject()).isEqualTo(PROPOSER);
    }

    @Test
    void approveClear_flipsCurrentToCleared() {
        EndpointDisplayPolicyRevision clear = new EndpointDisplayPolicyRevision();
        clear.setId(REVISION_ID);
        clear.setTenantId(TENANT);
        clear.setDeviceId(DEVICE_ID);
        clear.setOperation(DisplayPolicyOperation.CLEAR);
        clear.setPolicyHashSha256("c".repeat(64));
        clear.setReason("undo");
        clear.setCreatedBySubject(PROPOSER);
        clear.setCommandId(COMMAND_ID);
        when(revisionRepository.findByTenantIdAndCommandId(TENANT, COMMAND_ID)).thenReturn(List.of(clear));

        // pre-existing active ENFORCE row that should flip to CLEAR in place
        EndpointDisplayPolicy existing = new EndpointDisplayPolicy();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        existing.setDeviceId(DEVICE_ID);
        existing.setOperation(DisplayPolicyOperation.ENFORCE);
        existing.setScreensaverEnabled(true);
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE_ID)).thenReturn(Optional.of(existing));

        listener.onCommandApprovalDecided(approve());

        ArgumentCaptor<EndpointDisplayPolicy> cap = ArgumentCaptor.forClass(EndpointDisplayPolicy.class);
        verify(policyRepository).save(cap.capture());
        EndpointDisplayPolicy saved = cap.getValue();
        assertThat(saved.getOperation()).isEqualTo(DisplayPolicyOperation.CLEAR);
        assertThat(saved.getClearedAt()).isEqualTo(NOW);
        assertThat(saved.getClearedBySubject()).isEqualTo(APPROVER);
        assertThat(saved.getScreensaverEnabled()).isNull(); // snapshot cleared
    }

    @Test
    void reject_leavesCurrentUntouched() {
        CommandApprovalDecidedEvent reject = new CommandApprovalDecidedEvent(
                COMMAND_ID, CommandType.SET_DISPLAY_POLICY, ApprovalDecision.REJECT,
                TENANT, DEVICE_ID, APPROVER);

        listener.onCommandApprovalDecided(reject);

        verify(policyRepository, never()).save(any());
        verify(revisionRepository, never()).findByTenantIdAndCommandId(any(), any());
    }

    @Test
    void otherCommandType_isIgnored() {
        CommandApprovalDecidedEvent other = new CommandApprovalDecidedEvent(
                COMMAND_ID, CommandType.UNINSTALL_SOFTWARE, ApprovalDecision.APPROVE,
                TENANT, DEVICE_ID, APPROVER);

        listener.onCommandApprovalDecided(other);

        verify(policyRepository, never()).save(any());
    }

    @Test
    void approveWithNoBackingRevision_throwsAndRollsBack() {
        when(revisionRepository.findByTenantIdAndCommandId(TENANT, COMMAND_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> listener.onCommandApprovalDecided(approve()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected exactly 1 revision");
        verify(policyRepository, never()).save(any());
    }

    @Test
    void approve_supersedesPriorApprovedQueuedCommand() {
        when(revisionRepository.findByTenantIdAndCommandId(TENANT, COMMAND_ID))
                .thenReturn(List.of(enforceRevision()));
        // A prior APPROVED + QUEUED display-policy command exists for the device.
        when(commandRepository.findActiveDisplayPolicyCommandIdsForDevice(DEVICE_ID, COMMAND_ID))
                .thenReturn(List.of(PRIOR_CMD));
        EndpointCommand prior = priorCommand(CommandStatus.QUEUED);
        when(commandRepository.findByIdAndDeviceIdForUpdate(PRIOR_CMD, DEVICE_ID))
                .thenReturn(Optional.of(prior));

        listener.onCommandApprovalDecided(approve());

        // the stale approved-queued command is cancelled, and current is promoted
        ArgumentCaptor<EndpointCommand> cap = ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository, times(1)).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(CommandStatus.CANCELLED);
        verify(policyRepository, times(1)).save(any());
    }

    @Test
    void approve_priorApprovedInFlight_throws409AndDoesNotPromote() {
        when(revisionRepository.findByTenantIdAndCommandId(TENANT, COMMAND_ID))
                .thenReturn(List.of(enforceRevision()));
        when(commandRepository.findActiveDisplayPolicyCommandIdsForDevice(DEVICE_ID, COMMAND_ID))
                .thenReturn(List.of(PRIOR_CMD));
        when(commandRepository.findByIdAndDeviceIdForUpdate(PRIOR_CMD, DEVICE_ID))
                .thenReturn(Optional.of(priorCommand(CommandStatus.DELIVERED))); // in-flight

        assertThatThrownBy(() -> listener.onCommandApprovalDecided(approve()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("being applied");
        verify(commandRepository, never()).save(any()); // in-flight NOT cancelled
        verify(policyRepository, never()).save(any());   // current NOT promoted → approval rolls back
    }

    private static EndpointCommand priorCommand(CommandStatus status) {
        EndpointCommand c = new EndpointCommand();
        c.setCommandType(CommandType.SET_DISPLAY_POLICY);
        c.setApprovalStatus(ApprovalStatus.APPROVED);
        c.setStatus(status);
        c.setIdempotencyKey("display-policy:" + PRIOR_CMD);
        try {
            java.lang.reflect.Field f = EndpointCommand.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, PRIOR_CMD);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private static CommandApprovalDecidedEvent approve() {
        return new CommandApprovalDecidedEvent(
                COMMAND_ID, CommandType.SET_DISPLAY_POLICY, ApprovalDecision.APPROVE,
                TENANT, DEVICE_ID, APPROVER);
    }

    private static EndpointDisplayPolicyRevision enforceRevision() {
        EndpointDisplayPolicyRevision r = new EndpointDisplayPolicyRevision();
        r.setId(REVISION_ID);
        r.setTenantId(TENANT);
        r.setDeviceId(DEVICE_ID);
        r.setOperation(DisplayPolicyOperation.ENFORCE);
        r.setScreensaverEnabled(true);
        r.setScreensaverTimeoutSeconds(600);
        r.setScreensaverSecure(true);
        r.setScreensaverScrPath("c:\\windows\\system32\\scrnsave.scr");
        r.setWallpaperEnabled(true);
        r.setWallpaperStyle(WallpaperStyle.FILL);
        r.setWallpaperUserCannotChange(true);
        r.setPolicyHashSha256("a".repeat(64));
        r.setReason("kiosk lockdown");
        r.setCreatedBySubject(PROPOSER);
        r.setCommandId(COMMAND_ID);
        return r;
    }
}
