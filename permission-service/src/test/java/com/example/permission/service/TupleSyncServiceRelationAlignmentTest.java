package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-9 Phase 1+2):
 * regression guard for the warehouse {@code operator} → {@code viewer} and
 * branch {@code member} → {@code viewer} relation alignment fix.
 *
 * <p>Background: the canonical OpenFGA model declared in
 * {@code platform-backend/backend/openfga/model.fga} (Faz 21.3 ADR-0008
 * "explicit-scope contract") only defines {@code viewer: [user]} for all
 * scope object types — {@code operator} on warehouse and {@code member} on
 * branch are NOT defined. The legacy multi-tier model in
 * {@code platform-backend/openfga/model.fga} (root) had the alias chain
 * {@code viewer = [user] or operator}, which silently masked the drift on
 * any cluster still loading that model. Once the canonical
 * Faz 21.3 model is loaded, the {@code operator}/{@code member} writes
 * succeed at the SDK level (no relation enforcement on write) but the
 * resulting tuples are unreachable from any {@code viewer} lookup —
 * persistence loss surfaced as the "şirket yetkileri kayıt olmuyor"
 * production bug on testai.acik.com /admin/users.
 *
 * <p>This test pins the contract: every scope tuple emitted by
 * {@link TupleSyncService#syncScopeTuples(String, List, List, List, List,
 * boolean)} carries the {@code viewer} relation, regardless of object
 * type. Reverting either fix to the legacy {@code operator}/{@code member}
 * relation will fail this assertion.
 */
@ExtendWith(MockitoExtension.class)
class TupleSyncServiceRelationAlignmentTest {

    @Mock OpenFgaAuthzService authzService;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock UserRoleAssignmentRepository assignmentRepository;
    @Mock AuthzVersionService authzVersionService;

    TupleSyncService service;

    @BeforeEach
    void setUp() {
        service = new TupleSyncService(
                authzService,
                rolePermissionRepository,
                assignmentRepository,
                authzVersionService,
                null);
    }

    @Test
    @DisplayName("syncScopeTuples writes 'viewer' for ALL scope types (Faz 21.3 ADR-0008)")
    @SuppressWarnings("unchecked")
    void allScopesUseViewerRelation() {
        String userId = "1204";
        List<Long> companyIds = List.of(38L, 39L);
        List<Long> projectIds = List.of(100L);
        List<Long> warehouseIds = List.of(5L);
        List<Long> branchIds = List.of(7L);

        service.syncScopeTuples(userId, companyIds, projectIds, warehouseIds, branchIds, true);

        ArgumentCaptor<List<ClientTupleKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService, times(4)).writeTuples(captor.capture());

        List<List<ClientTupleKey>> batches = captor.getAllValues();
        Set<String> relationsObserved = new HashSet<>();
        Set<String> objectTypesObserved = new HashSet<>();

        for (List<ClientTupleKey> batch : batches) {
            for (ClientTupleKey tuple : batch) {
                relationsObserved.add(tuple.getRelation());
                String objectRef = tuple.getObject();
                String objectType = objectRef.split(":", 2)[0];
                objectTypesObserved.add(objectType);
            }
        }

        assertThat(relationsObserved)
                .as("Every scope tuple must use 'viewer' relation per ADR-0008")
                .containsExactly("viewer");
        assertThat(objectTypesObserved)
                .as("All four scope object types must be covered")
                .containsExactlyInAnyOrder("company", "project", "warehouse", "branch");
    }

    @Test
    @DisplayName("syncScopeTuples warehouse uses 'viewer' (regression: was 'operator')")
    @SuppressWarnings("unchecked")
    void warehouseUsesViewerNotOperator() {
        service.syncScopeTuples("1204", List.of(), List.of(), List.of(5L), List.of(), true);

        ArgumentCaptor<List<ClientTupleKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService).writeTuples(captor.capture());

        ClientTupleKey tuple = captor.getValue().get(0);
        assertThat(tuple.getRelation()).isEqualTo("viewer");
        assertThat(tuple.getObject()).startsWith("warehouse:");
    }

    @Test
    @DisplayName("syncScopeTuples branch uses 'viewer' (regression: was 'member')")
    @SuppressWarnings("unchecked")
    void branchUsesViewerNotMember() {
        service.syncScopeTuples("1204", List.of(), List.of(), List.of(), List.of(7L), true);

        ArgumentCaptor<List<ClientTupleKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService).writeTuples(captor.capture());

        ClientTupleKey tuple = captor.getValue().get(0);
        assertThat(tuple.getRelation()).isEqualTo("viewer");
        assertThat(tuple.getObject()).startsWith("branch:");
    }

    @Test
    @DisplayName("syncScopeTuples skips empty scope lists (no writeTuples call)")
    void emptyListsSkipped() {
        service.syncScopeTuples("1204", List.of(), List.of(), List.of(), List.of(), true);
        verify(authzService, times(0)).writeTuples(any());
    }
}
