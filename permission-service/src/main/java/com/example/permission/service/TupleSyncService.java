package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextCache;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized OpenFGA tuple synchronization for the Zanzibar authorization model.
 * Handles feature-level (module/action/report/page/field) and data-level (scope) tuples.
 * Implements deny-wins semantics when combining permissions from multiple roles.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true", matchIfMissing = false)
public class TupleSyncService {

    private static final Logger log = LoggerFactory.getLogger(TupleSyncService.class);

    private final OpenFgaAuthzService authzService;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final AuthzVersionService authzVersionService;
    private final ScopeContextCache scopeContextCache;

    @org.springframework.beans.factory.annotation.Autowired
    public TupleSyncService(OpenFgaAuthzService authzService,
                            RolePermissionRepository rolePermissionRepository,
                            UserRoleAssignmentRepository assignmentRepository,
                            AuthzVersionService authzVersionService,
                            @org.springframework.lang.Nullable ScopeContextCache scopeContextCache) {
        this.authzService = authzService;
        this.rolePermissionRepository = rolePermissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.authzVersionService = authzVersionService;
        this.scopeContextCache = scopeContextCache;
    }

    /**
     * Write feature tuples for a user based on their combined role permissions.
     * Applies deny-wins semantics: if any role DENYs a permission, the user is blocked.
     */
    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions) {
        syncFeatureTuplesForUser(userId, allPermissions, false);
    }

    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions, boolean skipVersionIncrement) {
        Map<String, ResolvedGrant> effective = resolveEffectiveGrants(allPermissions);
        boolean anyWriteFailed = false;

        for (var entry : effective.entrySet()) {
            String compositeKey = entry.getKey();
            ResolvedGrant grant = entry.getValue();
            String[] parts = compositeKey.split(":", 2);
            PermissionType type = PermissionType.valueOf(parts[0]);
            String key = parts[1];

            TupleMapping mapping = toTupleMapping(type, grant.grantType());
            if (mapping == null) continue;

            try {
                authzService.writeTuple(userId, mapping.relation(), mapping.objectType(), key);

                // CNS-20260415-004 Codex bulgu #3: Eskiden DENY icin ikinci "blocked"
                // tuple yazilirdi. Ancak toTupleMapping(DENY) zaten "blocked" relation
                // donduruyor (MODULE → "blocked"/"module"; ACTION → "blocked"/"action";
                // REPORT → "blocked"/"report"). Yukaridaki writeTuple cagrisi zaten
                // blocked tuple'ini yaziyor → duplicate write. Kaldirildi.
            } catch (Exception e) {
                anyWriteFailed = true;
                log.warn("OpenFGA tuple write failed for user:{} {}:{} — {}", userId, mapping.relation(), key, e.getMessage());
            }
        }
        // P0 fail-closed: only bump version if ALL OpenFGA writes succeeded
        if (!skipVersionIncrement && !anyWriteFailed) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        } else if (anyWriteFailed) {
            log.warn("Skipping version bump for user:{} — OpenFGA writes had failures", userId);
        }
    }

    /**
     * Delete all feature tuples for a user, then re-sync from current role assignments.
     */
    @Transactional(readOnly = true)
    public void refreshFeatureTuples(String userId) {
        refreshFeatureTuples(userId, false);
    }

    @Transactional(readOnly = true)
    public void refreshFeatureTuples(String userId, boolean skipVersionIncrement) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();

        if (roleIds.isEmpty()) {
            deleteAllFeatureTuples(userId);
            return;
        }

        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        deleteAllFeatureTuples(userId);
        syncFeatureTuplesForUser(userId, allPermissions, skipVersionIncrement);
    }

    /**
     * When a role's permissions change, refresh tuples for ALL users assigned to that role.
     */
    // 2026-04-18 OI-03 Bug 4 (Codex 019da431): propagateRoleChange must be
    // writable because authzVersionService.incrementVersion() performs an UPDATE
    // on authz_sync_version. Previous `readOnly=true` caused Spring/Hibernate to
    // reject the UPDATE with "cannot execute UPDATE in a read-only transaction"
    // and the fastpath consistently failed — outbox poller would retry 30 s
    // later, inflating authzVersion propagation latency and log noise.
    @Transactional
    public void propagateRoleChange(Long roleId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findByRoleIdAndActiveTrue(roleId);
        Set<Long> userIds = assignments.stream()
                .map(UserRoleAssignment::getUserId)
                .collect(Collectors.toSet());

        log.info("Propagating role {} change to {} users", roleId, userIds.size());

        for (Long numericUserId : userIds) {
            try {
                refreshFeatureTuples(String.valueOf(numericUserId), true);
            } catch (Exception e) {
                log.error("Failed to refresh tuples for user:{} after role:{} change", numericUserId, roleId, e);
            }
        }
        authzVersionService.incrementVersion();
        if (scopeContextCache != null) scopeContextCache.evictAll();
    }

    /**
     * Write data scope tuples for a user.
     */
    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds) {
        syncScopeTuples(userId, companyIds, projectIds, warehouseIds, branchIds, false);
    }

    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds, boolean skipVersionIncrement) {
        boolean anyFailed = false;
        // Codex thread 019e0891 iter-2 AGREE absorb (2026-05-08 PR-BE-9 Phase 1):
        // Faz 21.3 ADR-0008 "explicit-scope contract" canonical model uses
        // `viewer: [user]` for ALL scope object types (company/project/
        // warehouse/branch). Previous code wrote `operator` for warehouse and
        // `member` for branch — relations defined ONLY in legacy multi-tier
        // model.fga (root); `backend/openfga/model.fga` (canonical per ADR-0008
        // 2026-04-26) drops them entirely. On a cluster loading the canonical
        // model, those writes return success at SDK level but produce a tuple
        // that no `viewer` lookup can ever resolve — silent persistence loss
        // surfaced as the "şirket yetkileri kayıt olmuyor" production bug.
        // Aligning all four object types on `viewer` makes WRITE relation
        // match what ScopeContextFilter (line ~125-132) already reads.
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "company", companyIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "project", projectIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "warehouse", warehouseIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "branch", branchIds);
        if (!skipVersionIncrement && !anyFailed) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        } else if (anyFailed) {
            log.warn("Skipping version bump for user:{} — scope tuple writes had failures", userId);
        }
    }

    /**
     * Resolve effective grants from multiple role permissions using deny-wins semantics.
     * Key format: "PERMISSION_TYPE:permission_key"
     */
    public Map<String, ResolvedGrant> resolveEffectiveGrants(List<RolePermission> permissions) {
        Map<String, ResolvedGrant> effective = new LinkedHashMap<>();

        for (RolePermission rp : permissions) {
            if (rp.getPermissionType() == null || rp.getPermissionKey() == null || rp.getGrantType() == null) {
                continue;
            }

            String compositeKey = rp.getPermissionType().name() + ":" + rp.getPermissionKey();
            ResolvedGrant existing = effective.get(compositeKey);

            if (existing == null) {
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            } else if (rp.getGrantType() == GrantType.DENY) {
                // DENY always wins
                effective.put(compositeKey, new ResolvedGrant(GrantType.DENY, rp.getRole().getName()));
            } else if (existing.grantType() != GrantType.DENY && isHigherGrant(rp.getGrantType(), existing.grantType())) {
                // Higher grant wins (MANAGE > VIEW, ALLOW > VIEW)
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            }
        }

        return effective;
    }

    // --- Private helpers ---

    /** @return true if write succeeded, false on failure */
    private boolean writeScopeTuplesSafe(String userId, String relation, String objectType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        var tuples = ids.stream()
                .map(id -> OpenFgaAuthzService.writeTupleKey(userId, relation, objectType, String.valueOf(id)))
                .toList();
        try {
            authzService.writeTuples(tuples);
            log.debug("OpenFGA batch scope write: {} tuples for user:{} {}:{}", tuples.size(), userId, relation, objectType);
            return true;
        } catch (Exception e) {
            log.warn("OpenFGA batch scope write failed for user:{} {}:{} ({} tuples) — {}",
                    userId, relation, objectType, tuples.size(), e.getMessage());
            return false;
        }
    }

    /**
     * Delete known feature tuples for a user before re-sync.
     * Uses batch deleteTuples for efficiency.
     */
    private void deleteAllFeatureTuples(String userId) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream().map(a -> a.getRole().getId()).distinct().toList();
        if (roleIds.isEmpty()) return;

        List<RolePermission> allPerms = rolePermissionRepository.findByRoleIdIn(roleIds);

        // 2026-04-18 OI-03 Bug 5 (Codex 019da431): stale-relation cleanup.
        // Previous code deleted only the CURRENT grant's mapped relation (e.g.
        // can_view for VIEW grant). On transitions like VIEW→DENY or MANAGE→VIEW
        // the old relation tuple (can_view / can_manage) would remain in
        // OpenFGA and surface as a phantom allow. Fix: for every
        // (permissionType, permissionKey) pair present in the user's active
        // roles, delete ALL possible relations for that type so the subsequent
        // write phase re-applies exactly the current grant's mapping. Uses the
        // already-defined but previously-unused `getRelationsForType` helper.
        //
        // Dedupe key: canonical string "userId|relation|objectType:objectId".
        // SDK model classes (ClientTupleKey / ClientTupleKeyWithoutCondition)
        // do NOT override equals/hashCode, so LinkedHashSet<ClientTuple*> would
        // silently leak duplicates (iter-2 REVISE).
        java.util.LinkedHashMap<String, ClientTupleKeyWithoutCondition> dedupe = new java.util.LinkedHashMap<>();
        for (RolePermission rp : allPerms) {
            if (rp.getPermissionType() == null || rp.getPermissionKey() == null) continue;
            String objectType = objectTypeForPermissionType(rp.getPermissionType());
            if (objectType == null) continue;
            for (String relation : getRelationsForType(rp.getPermissionType())) {
                String canonical = userId + "|" + relation + "|" + objectType + ":" + rp.getPermissionKey();
                dedupe.putIfAbsent(canonical, OpenFgaAuthzService.deleteTupleKey(
                        userId, relation, objectType, rp.getPermissionKey()));
            }
        }

        if (dedupe.isEmpty()) return;
        var deleteTuples = new ArrayList<>(dedupe.values());
        try {
            // 2026-04-18 OI-03 Bug 3 (Codex 019da431): OpenFgaAuthzService.deleteTuples
            // is now idempotent — "tuple does not exist" errors are swallowed and
            // per-tuple fallback retries surviving entries.
            authzService.deleteTuples(deleteTuples);
            log.debug("OpenFGA batch feature delete: {} tuples for user:{}", deleteTuples.size(), userId);
        } catch (Exception e) {
            log.debug("OpenFGA batch feature delete (partial no-op) for user:{} ({} tuples) — {}",
                    userId, deleteTuples.size(), e.getMessage());
        }
    }

    private String objectTypeForPermissionType(PermissionType type) {
        return switch (type) {
            case MODULE -> "module";
            case ACTION -> "action";
            case REPORT -> "report";
        };
    }

    private List<String> getRelationsForType(PermissionType type) {
        return switch (type) {
            case MODULE -> List.of("can_manage", "can_edit", "can_view", "blocked");
            case ACTION -> List.of("allowed", "blocked");
            case REPORT -> List.of("can_edit", "can_view", "blocked");
            // PAGE, FIELD removed in V10 migration (TB-21)
        };
    }

    private TupleMapping toTupleMapping(PermissionType type, GrantType grant) {
        return switch (type) {
            case MODULE -> switch (grant) {
                case MANAGE -> new TupleMapping("can_manage", "module");
                case VIEW -> new TupleMapping("can_view", "module");
                case DENY -> new TupleMapping("blocked", "module");
                case ALLOW -> new TupleMapping("can_view", "module");
            };
            case ACTION -> switch (grant) {
                case ALLOW -> new TupleMapping("allowed", "action");
                case DENY -> new TupleMapping("blocked", "action");
                default -> null;
            };
            case REPORT -> switch (grant) {
                case MANAGE -> new TupleMapping("can_edit", "report");
                case ALLOW, VIEW -> new TupleMapping("can_view", "report");
                case DENY -> new TupleMapping("blocked", "report");
            };
            // PAGE, FIELD removed in V10 migration (TB-21)
        };
    }

    private boolean isHigherGrant(GrantType candidate, GrantType existing) {
        return grantOrdinal(candidate) > grantOrdinal(existing);
    }

    private int grantOrdinal(GrantType grant) {
        return switch (grant) {
            case DENY -> -1;
            case VIEW -> 1;
            case ALLOW -> 2;
            case MANAGE -> 3;
        };
    }

    public record ResolvedGrant(GrantType grantType, String sourceRole) {}
    private record TupleMapping(String relation, String objectType) {}
}
