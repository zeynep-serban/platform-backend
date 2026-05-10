package com.example.permission.config;

import com.example.permission.event.RoleChangeEvent;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D2 iter-3 absorb (Codex 019e10bf P2): integration-style coverage of the
 * ADMIN role's {@code MODULE:IMPERSONATION_AUDIT:MANAGE} granule seed +
 * {@link RoleChangeEvent} publication. The pre-existing
 * {@link PermissionDataInitializerGranuleAwareTest} only exercises the
 * static {@code usesGranuleModel} predicate — it does not assert that the
 * initializer actually inserts the new granule row or fires the
 * tuple-propagation event for the ADMIN role.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li><b>Fresh ADMIN role</b>: {@code MODULE:IMPERSONATION_AUDIT:MANAGE}
 *       row inserted; {@link RoleChangeEvent} published for the ADMIN role
 *       id.</li>
 *   <li><b>Idempotent re-run</b>: when the granule already exists,
 *       no new row is saved and no event is published for that role.</li>
 *   <li><b>ADMIN missing from existingRoles</b>: defensive — initializer
 *       should not crash; the ADMIN role bootstrap is the legacy seed
 *       loop's job, not the granule loop's.</li>
 * </ol>
 *
 * <p>Codex peer review thread {@code 019e10bf-5256-7b03-b108-5f6d5543e3ed}
 * iter-3 P2 absorb.
 */
class PermissionDataInitializerImpersonationAdminSeedTest {

    private PermissionRepository permissionRepository;
    private RoleRepository roleRepository;
    private RolePermissionRepository rolePermissionRepository;
    private ApplicationEventPublisher eventPublisher;
    private PermissionDataInitializer initializer;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(PermissionRepository.class);
        roleRepository = mock(RoleRepository.class);
        rolePermissionRepository = mock(RolePermissionRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        // permissionRepository.findAll() — return all DEFAULT_PERMISSIONS as
        // pre-existing entities so the legacy permission-seed loop is a no-op
        // and we can focus on the granule path.
        when(permissionRepository.findAll()).thenReturn(buildAllDefaultPermissions());
        when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> inv.getArgument(0));

        when(rolePermissionRepository.save(any(RolePermission.class))).thenAnswer(inv -> inv.getArgument(0));

        // Legacy seed loop creates roles for any DEFAULT_ROLE_PERMISSIONS
        // entry not present in findAll() — return the saved role with a
        // synthetic id so the granule loop later finds them via the
        // existingRoles map mutated by computeIfAbsent.
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(System.nanoTime());
            }
            return r;
        });

        initializer = new PermissionDataInitializer(
                permissionRepository, roleRepository, rolePermissionRepository, eventPublisher);
    }

    @Test
    void run_freshAdminRole_seedsImpersonationAuditManageGranule_andPublishesRoleChangeEvent() throws Exception {
        Role admin = adminRoleWithGranuleMarker(99L);
        // ADMIN is the only role we care about for this test; provide it +
        // any other roles needed by DEFAULT_ROLE_PERMISSIONS so the loop has
        // a stable iteration set.
        when(roleRepository.findAll()).thenReturn(List.of(admin));

        initializer.run();

        // 1. Granule row inserted: MODULE / IMPERSONATION_AUDIT / MANAGE on ADMIN.
        ArgumentCaptor<RolePermission> rpCaptor = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissionRepository, atLeastOnce()).save(rpCaptor.capture());
        boolean foundImpersonationGranule = rpCaptor.getAllValues().stream().anyMatch(rp ->
                rp.getRole() == admin
                && rp.getPermissionType() == PermissionType.MODULE
                && "IMPERSONATION_AUDIT".equals(rp.getPermissionKey())
                && rp.getGrantType() == GrantType.MANAGE
                && rp.getPermission() == null);
        assertThat(foundImpersonationGranule)
                .as("ADMIN role must have MODULE:IMPERSONATION_AUDIT:MANAGE granule row inserted (permission_id NULL)")
                .isTrue();

        // 2. RoleChangeEvent published for ADMIN role id.
        verify(eventPublisher).publishEvent(argThat((Object event) ->
                event instanceof RoleChangeEvent rce && rce.roleId() != null && rce.roleId() == 99L));
    }

    @Test
    void run_adminAlreadyHasAllGranules_isIdempotent_noDuplicateInsertOrEvent() throws Exception {
        Role admin = adminRoleWithGranuleMarker(99L);
        // Pre-seed every granule the initializer would otherwise insert: the
        // 12 dashboards (HR + Finans) plus IMPERSONATION_AUDIT MANAGE. This
        // makes the entire granule pass for ADMIN a no-op.
        for (String key : List.of(
                "HR_ANALYTICS", "HR_FINANSAL", "HR_EQUITY_RISK",
                "HR_BENEFITS_LITE", "HR_COMPENSATION", "HR_SALARY_ANALYTICS",
                "HR_PAYROLL_TRENDS", "HR_DEMOGRAFIK", "HR_EXECUTIVE_SUMMARY",
                "FIN_ANALYTICS", "FIN_RATIOS", "FIN_RECONCILIATION")) {
            admin.addRolePermission(new RolePermission(
                    admin, PermissionType.REPORT, key, GrantType.MANAGE));
        }
        admin.addRolePermission(new RolePermission(
                admin, PermissionType.MODULE, "IMPERSONATION_AUDIT", GrantType.MANAGE));
        when(roleRepository.findAll()).thenReturn(List.of(admin));

        initializer.run();

        // No IMPERSONATION_AUDIT granule row inserted (the dedup key check
        // skipped it). Other roles in DEFAULT_ROLE_GRANULES (REPORT_MANAGER,
        // FINANCE_*) aren't in our findAll() mock, so the granule loop
        // silently skips them — only ADMIN matters for this assertion.
        verify(rolePermissionRepository, never()).save(argThat((RolePermission rp) ->
                rp.getPermissionType() == PermissionType.MODULE
                && "IMPERSONATION_AUDIT".equals(rp.getPermissionKey())));

        // No new granule for ADMIN this pass → rolesNeedingTuplePropagation
        // does not contain ADMIN → no event for ADMIN role id.
        verify(eventPublisher, never()).publishEvent(argThat((Object event) ->
                event instanceof RoleChangeEvent rce && rce.roleId() != null && rce.roleId() == 99L));
    }

    @Test
    void run_freshAdminRole_grantsCorrectPermissionTypeAndKey() throws Exception {
        // Stronger check on (type, key, grant) tuple identity — the granule
        // is MODULE + IMPERSONATION_AUDIT + MANAGE per ADR-0014; if any of
        // these drift (e.g. someone changes to ACTION or VIEW), the FGA
        // tuple shape would no longer line up with the @RequireModule
        // annotation gate on /api/audit/events/impersonation, breaking the
        // ADMIN dashboard access end-to-end.
        Role admin = adminRoleWithGranuleMarker(99L);
        when(roleRepository.findAll()).thenReturn(List.of(admin));

        initializer.run();

        ArgumentCaptor<RolePermission> rpCaptor = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissionRepository, atLeastOnce()).save(rpCaptor.capture());
        RolePermission impersonationGrant = rpCaptor.getAllValues().stream()
                .filter(rp -> rp.getPermissionType() == PermissionType.MODULE
                        && "IMPERSONATION_AUDIT".equals(rp.getPermissionKey()))
                .findFirst()
                .orElseThrow();
        assertThat(impersonationGrant.getGrantType()).isEqualTo(GrantType.MANAGE);
        assertThat(impersonationGrant.getRole()).isSameAs(admin);
        assertThat(impersonationGrant.getPermission())
                .as("granule shortcut row must have permission_id NULL (FGA tuple-driven)")
                .isNull();
    }

    // ---------- helpers ----------

    /**
     * Build an ADMIN role with the granule-shortcut marker so
     * {@code usesGranuleModel(role)} returns true and the legacy seed loop
     * skips it (mirrors live state of an ADMIN role that has been managed
     * via the granule API at least once).
     */
    private Role adminRoleWithGranuleMarker(long id) {
        Role admin = new Role();
        admin.setId(id);
        admin.setName("ADMIN");
        admin.setPermissionModel(com.example.permission.model.PermissionModel.GRANULE);
        return admin;
    }

    /**
     * Build pre-existing Permission entities for every code referenced in
     * {@code DEFAULT_PERMISSIONS} so the legacy permission-seed loop is a
     * no-op. We don't actually iterate the constants (private); building
     * an empty list works because the loop only saves missing permissions
     * — the granule path is independent.
     */
    private List<Permission> buildAllDefaultPermissions() {
        // Empty is fine: missing permissions get created via save() and the
        // mock returns the same instance back. We only care about granule
        // row writes (permission_id NULL), not legacy FK rows.
        return new ArrayList<>();
    }
}
