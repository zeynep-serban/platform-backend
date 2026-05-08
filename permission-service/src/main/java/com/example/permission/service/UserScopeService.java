package com.example.permission.service;

import com.example.commonauth.scope.OpenFgaScopeReader;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.model.Scope;
import com.example.permission.model.UserPermissionScope;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.ScopeRepository;
import com.example.permission.repository.UserPermissionScopeRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserScopeService {

    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("COMPANY", "PROJECT");

    private final UserPermissionScopeRepository userPermissionScopeRepository;
    private final ScopeRepository scopeRepository;
    private final PermissionRepository permissionRepository;
    @Nullable private final OpenFgaScopeReader openFgaScopeReader;

    public UserScopeService(UserPermissionScopeRepository userPermissionScopeRepository,
                            ScopeRepository scopeRepository,
                            PermissionRepository permissionRepository,
                            @Nullable OpenFgaScopeReader openFgaScopeReader) {
        this.userPermissionScopeRepository = userPermissionScopeRepository;
        this.scopeRepository = scopeRepository;
        this.permissionRepository = permissionRepository;
        this.openFgaScopeReader = openFgaScopeReader;
    }

    /**
     * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 4):
     * read-path migration from DB {@code user_permission_scopes} table
     * to OpenFGA tuples via {@link OpenFgaScopeReader}. The legacy DB
     * table was a Phase-1 ADR-AUTHZ-SCOPE-01 artifact; once Faz 21.3
     * ADR-0008 explicit-scope contract became canonical, OpenFGA
     * tuples (written by {@code TupleSyncService.syncScopeTuples}) are
     * the source of truth — but {@code listUserScopes} kept reading
     * the DB, producing the persistence-store split that surfaced as
     * the "şirket yetkileri kayıt olmuyor" production bug
     * (testai.acik.com /admin/users UserDetailDrawer).
     *
     * <p>When OpenFGA is enabled (production), reader returns the
     * authoritative scope view via {@code listObjectIds × 4} (parallel,
     * cache-backed). When disabled (dev/permitAll local profile), the
     * DB fallback preserves the legacy behavior so dev fixtures keep
     * working without OpenFGA running.
     */
    @Transactional(readOnly = true)
    public List<ScopeSummaryDto> listUserScopes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        if (openFgaScopeReader != null) {
            Map<String, Set<Long>> summary = openFgaScopeReader.readScopeSummarySafe(String.valueOf(userId));
            List<ScopeSummaryDto> out = new ArrayList<>();
            summary.forEach((scopeType, refIds) ->
                    refIds.forEach(refId -> out.add(new ScopeSummaryDto(scopeType, refId))));
            return out;
        }
        return userPermissionScopeRepository.findByUserIdWithPermissionAndScope(userId).stream()
                .filter(ups -> ups.getScope() != null && ups.getScope().getScopeType() != null && ups.getScope().getRefId() != null)
                .map(ups -> new ScopeSummaryDto(ups.getScope().getScopeType(), ups.getScope().getRefId()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void addScope(Long userId, String scopeType, Long scopeRefId, String permissionCode) {
        validateScope(scopeType);
        var permission = permissionRepository.findByCodeIgnoreCase(permissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + permissionCode));

        Scope scope = scopeRepository.findByScopeTypeIgnoreCaseAndRefId(scopeType, scopeRefId)
                .orElseGet(() -> {
                    Scope s = new Scope();
                    s.setScopeType(scopeType.toUpperCase());
                    s.setRefId(scopeRefId);
                    return scopeRepository.save(s);
                });

        boolean exists = userPermissionScopeRepository.existsByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(
                userId, permissionCode, scope.getId());
        if (exists) {
            return; // idempotent
        }

        UserPermissionScope ups = new UserPermissionScope();
        ups.setUserId(userId);
        ups.setPermission(permission);
        ups.setScope(scope);
        userPermissionScopeRepository.save(ups);
    }

    @Transactional
    public void removeScope(Long userId, String scopeType, Long scopeRefId, String permissionCode) {
        validateScope(scopeType);
        permissionRepository.findByCodeIgnoreCase(permissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + permissionCode));

        scopeRepository.findByScopeTypeIgnoreCaseAndRefId(scopeType, scopeRefId).ifPresent(scope ->
                userPermissionScopeRepository.deleteByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(userId, permissionCode, scope.getId())
        );
    }

    private void validateScope(String scopeType) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType.toUpperCase())) {
            throw new IllegalArgumentException("Desteklenmeyen scopeType: " + scopeType);
        }
    }
}
