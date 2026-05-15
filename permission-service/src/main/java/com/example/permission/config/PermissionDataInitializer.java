package com.example.permission.config;

import com.example.permission.event.RoleChangeEvent;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.service.RolePermissionGranuleDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(10)
public class PermissionDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionDataInitializer.class);

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ApplicationEventPublisher eventPublisher;

    // P1-B: Simplified permissions (~35) — granular reports→groups, user duplicates→3-tier
    // Consultation CNS-20260410-001 (Claude + Codex consensus: kademeli 93→35)
    private static final Map<String, PermissionDefinition> DEFAULT_PERMISSIONS = Map.ofEntries(
            // --- Modules (7) ---
            Map.entry("VIEW_USERS", new PermissionDefinition("View users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("MANAGE_USERS", new PermissionDefinition("Create or update users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("VIEW_PURCHASE", new PermissionDefinition("View purchase module", "Satın Alma")),
            Map.entry("APPROVE_PURCHASE", new PermissionDefinition("Approve purchase orders", "Satın Alma")),
            Map.entry("VIEW_WAREHOUSE", new PermissionDefinition("View warehouse module", "Depo")),
            Map.entry("MANAGE_WAREHOUSE", new PermissionDefinition("Manage warehouse inventory", "Depo")),
            Map.entry("THEME_ADMIN", new PermissionDefinition("Manage global themes and critical theme tokens", "Tema Yönetimi")),
            Map.entry("VARIANTS_READ", new PermissionDefinition("View grid variants and presets", "Variant")),
            Map.entry("VARIANTS_WRITE", new PermissionDefinition("Create or update personal variants", "Variant")),
            Map.entry("MANAGE_GLOBAL_VARIANTS", new PermissionDefinition("Manage shared and global variants", "Variant")),
            // --- Access (2 — read + write) ---
            Map.entry("access-read", new PermissionDefinition("Read access management data", "Access")),
            Map.entry("access-write", new PermissionDefinition("Create/update/delete access management records", "Access")),
            // --- Audit (1) ---
            Map.entry("audit-read", new PermissionDefinition("Read audit events", "Audit")),
            // --- Company (2) ---
            Map.entry("company-read", new PermissionDefinition("Read company master data", "Company")),
            Map.entry("company-write", new PermissionDefinition("Create or update company master data", "Company")),
            // --- System governance (4) ---
            Map.entry("role-manage", new PermissionDefinition("Manage roles", "Sistem Yönetimi")),
            Map.entry("permission-manage", new PermissionDefinition("Manage permission catalog / role-permission matrix", "Sistem Yönetimi")),
            Map.entry("permission-scope-manage", new PermissionDefinition("Manage user-permission scopes", "Sistem Yönetimi")),
            Map.entry("system-configure", new PermissionDefinition("Configure global module settings", "Sistem Yönetimi")),
            // --- User 3-tier (3 — replaces 8 duplicates) ---
            Map.entry("user-read", new PermissionDefinition("Read/list users", "Kullanıcı Yönetimi")),
            Map.entry("user-write", new PermissionDefinition("Create/update users", "Kullanıcı Yönetimi")),
            Map.entry("user-admin", new PermissionDefinition("Delete/import/export users + role assignment", "Kullanıcı Yönetimi")),
            // --- Reporting core (3) ---
            Map.entry("REPORT_VIEW", new PermissionDefinition("View reports", "Raporlama")),
            Map.entry("REPORT_EXPORT", new PermissionDefinition("Export reports (CSV/Excel)", "Raporlama")),
            Map.entry("REPORT_MANAGE", new PermissionDefinition("Manage report definitions and settings", "Raporlama")),
            // --- Report groups (4 — replaces 20 granular) ---
            Map.entry("reports.HR_REPORTS", new PermissionDefinition("HR report group (personnel, salary, attendance, leave, payroll)", "reporting")),
            Map.entry("reports.FINANCE_REPORTS", new PermissionDefinition("Finance report group (bank, cash, invoices, cheque, accounts)", "reporting")),
            Map.entry("reports.SALES_REPORTS", new PermissionDefinition("Sales report group (sales summary, stock status)", "reporting")),
            Map.entry("reports.ANALYTICS_REPORTS", new PermissionDefinition("Analytics dashboards (HR, finance analytics)", "reporting")),
            // --- Scope markers (2 — not report groups, scope modifiers) ---
            Map.entry("scope.all-companies-hr", new PermissionDefinition("Bypass company filter for HR reports", "scope")),
            Map.entry("scope.all-companies-fin", new PermissionDefinition("Bypass company filter for finance reports", "scope"))
    );

    // Codex 019dda1c iter-27: dashboard-level granule defaults seeded directly
    // (permission_id IS NULL, type/key/grant explicit). The legacy
    // DEFAULT_ROLE_PERMISSIONS code path needs entries to exist in the
    // DEFAULT_PERMISSIONS table; per-dashboard keys (HR_ANALYTICS, ...) are
    // not Permission entities, so we have to seed them as raw granule rows.
    //
    // Mirrors PermissionCatalogService.REPORTS — same upper-snake keys, same
    // category split (9 İK + 3 Finans).
    private static final List<String> DEFAULT_HR_DASHBOARD_KEYS = List.of(
            "HR_ANALYTICS", "HR_FINANSAL", "HR_EQUITY_RISK",
            "HR_BENEFITS_LITE", "HR_COMPENSATION", "HR_SALARY_ANALYTICS",
            "HR_PAYROLL_TRENDS", "HR_DEMOGRAFIK", "HR_EXECUTIVE_SUMMARY"
    );
    private static final List<String> DEFAULT_FIN_DASHBOARD_KEYS = List.of(
            "FIN_ANALYTICS", "FIN_RATIOS", "FIN_RECONCILIATION"
    );

    /**
     * R16 PR-B-2 (Codex 019e27f5 önerisi) — report_group GranuleSeed key'leri.
     *
     * <p>Bu key'ler {@code reports.<GROUP>} permission catalog entry'leriyle
     * eşleşir; {@link com.example.permission.service.TupleSyncService}
     * {@code REPORT_GROUP_KEYS} membership check yapıp OpenFGA
     * {@code report_group:<KEY>#can_view} tuple'ı yazar.
     *
     * <p>R15 user-visible repair: bu seed'ler ile ADMIN + REPORT_MANAGER
     * + REPORT_VIEWER + FINANCE_* rolleri otomatik report_group tuple'larına
     * sahip olur → /authz/me.reports map dolu döner → FE filter 24 hidden
     * report'u görünür hale getirir.
     */
    private static final List<String> DEFAULT_REPORT_GROUP_KEYS = List.of(
            "FINANCE_REPORTS", "HR_REPORTS", "SALES_REPORTS", "ANALYTICS_REPORTS"
    );

    private static final List<String> DEFAULT_FIN_REPORT_GROUP_KEYS = List.of(
            "FINANCE_REPORTS", "ANALYTICS_REPORTS"
    );

    private record GranuleSeed(
            PermissionType type,
            String key,
            GrantType grant) {}

    /**
     * PR-D2 (User Impersonation v1): {@code MODULE:IMPERSONATION_AUDIT:MANAGE}
     * granule seeded onto the ADMIN role. Granule-managed seeding is the
     * authoritative path — raw FGA tuples in
     * {@code backend/openfga/tuples-seed.json} are forbidden
     * (CNS-20260415-004). The TupleSyncService propagates the granule to a
     * {@code module:IMPERSONATION_AUDIT can_manage user:<adminUserId>} tuple
     * via {@link RoleChangeEvent} after-commit (publishImpersonationAuditSeedEvent).
     */
    private static final GranuleSeed IMPERSONATION_AUDIT_ADMIN_GRANULE = new GranuleSeed(
            PermissionType.MODULE, "IMPERSONATION_AUDIT", GrantType.MANAGE);

    private static final Map<String, List<GranuleSeed>> DEFAULT_ROLE_GRANULES = Map.ofEntries(
            Map.entry("ADMIN",           buildAdminGranules()),
            Map.entry("REPORT_MANAGER",  combine(
                    buildDashboardGranules(GrantType.MANAGE,
                            DEFAULT_HR_DASHBOARD_KEYS, DEFAULT_FIN_DASHBOARD_KEYS),
                    // R16 PR-B-2: report_group seed (TupleSyncService
                    // REPORT_GROUP_KEYS membership ile report_group type'a
                    // tuple yazar; /authz/me.reports map dolar)
                    buildReportGroupGranules(GrantType.MANAGE,
                            DEFAULT_REPORT_GROUP_KEYS))),
            // REPORT_VIEWER intentionally gets every dashboard at VIEW level —
            // a "viewer" who can't open any dashboard would be a surprising
            // role contract for users assigning it from the drawer.
            Map.entry("REPORT_VIEWER",   combine(
                    buildDashboardGranules(GrantType.VIEW,
                            DEFAULT_HR_DASHBOARD_KEYS, DEFAULT_FIN_DASHBOARD_KEYS),
                    buildReportGroupGranules(GrantType.VIEW,
                            DEFAULT_REPORT_GROUP_KEYS))),
            Map.entry("FINANCE_MANAGER", combine(
                    buildDashboardGranules(GrantType.MANAGE,
                            DEFAULT_FIN_DASHBOARD_KEYS),
                    buildReportGroupGranules(GrantType.MANAGE,
                            DEFAULT_FIN_REPORT_GROUP_KEYS))),
            Map.entry("FINANCE_VIEWER",  combine(
                    buildDashboardGranules(GrantType.VIEW,
                            DEFAULT_FIN_DASHBOARD_KEYS),
                    buildReportGroupGranules(GrantType.VIEW,
                            DEFAULT_FIN_REPORT_GROUP_KEYS)))
    );

    /**
     * ADMIN granule seed list = dashboard MANAGE (HR + Finans) + report_group
     * MANAGE (R16 PR-B-2) + dedicated IMPERSONATION_AUDIT MANAGE module
     * grant (PR-D2).
     */
    private static List<GranuleSeed> buildAdminGranules() {
        List<GranuleSeed> out = new ArrayList<>(buildDashboardGranules(
                GrantType.MANAGE, DEFAULT_HR_DASHBOARD_KEYS, DEFAULT_FIN_DASHBOARD_KEYS));
        // R16 PR-B-2: ADMIN tüm report_group'lar için MANAGE
        out.addAll(buildReportGroupGranules(GrantType.MANAGE, DEFAULT_REPORT_GROUP_KEYS));
        out.add(IMPERSONATION_AUDIT_ADMIN_GRANULE);
        return List.copyOf(out);
    }

    @SafeVarargs
    private static List<GranuleSeed> buildDashboardGranules(
            GrantType grant,
            List<String>... keyLists) {
        List<GranuleSeed> out = new ArrayList<>();
        for (List<String> keys : keyLists) {
            for (String key : keys) {
                out.add(new GranuleSeed(
                        PermissionType.REPORT, key, grant));
            }
        }
        return List.copyOf(out);
    }

    /**
     * R16 PR-B-2 (Codex 019e27f5): report_group GranuleSeed builder. Key'ler
     * {@code reports.<GROUP>} formatında üretilir; TupleSyncService
     * {@code REPORT_GROUP_KEYS} membership ile {@code report_group} type'a
     * eşleştirir.
     */
    private static List<GranuleSeed> buildReportGroupGranules(
            GrantType grant,
            List<String> groupKeys) {
        List<GranuleSeed> out = new ArrayList<>();
        for (String groupKey : groupKeys) {
            out.add(new GranuleSeed(
                    PermissionType.REPORT, "reports." + groupKey, grant));
        }
        return List.copyOf(out);
    }

    @SafeVarargs
    private static List<GranuleSeed> combine(List<GranuleSeed>... lists) {
        List<GranuleSeed> out = new ArrayList<>();
        for (List<GranuleSeed> list : lists) {
            out.addAll(list);
        }
        return List.copyOf(out);
    }

    // P1-B: Simplified role→permission mapping using groups
    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMISSIONS = Map.ofEntries(
            Map.entry("ADMIN", Set.of(
                    "VIEW_USERS", "MANAGE_USERS", "APPROVE_PURCHASE", "MANAGE_WAREHOUSE",
                    "VARIANTS_READ", "VARIANTS_WRITE", "MANAGE_GLOBAL_VARIANTS",
                    "access-read", "access-write",
                    "audit-read",
                    "company-read", "company-write",
                    "role-manage", "permission-manage", "permission-scope-manage", "system-configure",
                    "THEME_ADMIN",
                    "user-read", "user-write", "user-admin",
                    "REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE",
                    "reports.HR_REPORTS", "reports.FINANCE_REPORTS", "reports.SALES_REPORTS", "reports.ANALYTICS_REPORTS",
                    "scope.all-companies-hr", "scope.all-companies-fin"
            )),
            Map.entry("REPORT_VIEWER", Set.of("REPORT_VIEW")),
            Map.entry("REPORT_MANAGER", Set.of("REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE")),
            Map.entry("USER_MANAGE", Set.of("user-read", "user-write", "user-admin")),
            Map.entry("ROLE_MANAGE", Set.of("role-manage")),
            Map.entry("PERMISSION_MANAGE", Set.of("permission-manage", "permission-scope-manage")),
            Map.entry("SYSTEM_CONFIGURE", Set.of("system-configure")),
            Map.entry("AUDIT_READ", Set.of("audit-read")),
            Map.entry("USER_MANAGER", Set.of("VIEW_USERS", "MANAGE_USERS")),
            Map.entry("USER_VIEWER", Set.of("VIEW_USERS")),
            Map.entry("PURCHASE_MANAGER", Set.of("VIEW_USERS", "APPROVE_PURCHASE")),
            Map.entry("WAREHOUSE_OPERATOR", Set.of("VIEW_USERS", "MANAGE_WAREHOUSE")),
            // Finance roles — least-privilege preserved (Codex consensus)
            Map.entry("FINANCE_VIEWER", Set.of(
                    "REPORT_VIEW",
                    "reports.FINANCE_REPORTS", "reports.ANALYTICS_REPORTS"
            )),
            Map.entry("FINANCE_MANAGER", Set.of(
                    "REPORT_VIEW", "REPORT_EXPORT",
                    "reports.FINANCE_REPORTS", "reports.ANALYTICS_REPORTS",
                    "scope.all-companies-fin"
            ))
    );

    public PermissionDataInitializer(PermissionRepository permissionRepository,
                                     RoleRepository roleRepository,
                                     RolePermissionRepository rolePermissionRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.eventPublisher = eventPublisher;
    }

    private record PermissionDefinition(String description, String moduleName) { }

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, Permission> existingPermissions = permissionRepository.findAll()
                .stream()
                .collect(Collectors.toMap(permission -> permission.getCode().toLowerCase(), permission -> permission));

        DEFAULT_PERMISSIONS.forEach((code, definition) -> {
            String normalizedKey = code.toLowerCase();
            existingPermissions.computeIfAbsent(normalizedKey, key -> {
                Permission permission = new Permission();
                permission.setCode(code);
                permission.setDescription(definition.description());
                permission.setModuleName(definition.moduleName());
                Permission saved = permissionRepository.save(permission);
                log.info("Created default permission {}", code);
                return saved;
            });
        });

        DEFAULT_PERMISSIONS.forEach((code, definition) -> {
            Permission permission = existingPermissions.get(code.toLowerCase());
            if (permission == null) {
                return;
            }
            boolean dirty = false;
            if (permission.getDescription() == null || permission.getDescription().isBlank()) {
                permission.setDescription(definition.description());
                dirty = true;
            }
            if (definition.moduleName() != null &&
                    (permission.getModuleName() == null || permission.getModuleName().isBlank())) {
                permission.setModuleName(definition.moduleName());
                dirty = true;
            }
            if (dirty) {
                permissionRepository.save(permission);
                log.info("Updated metadata for permission {}", permission.getCode());
            }
        });

        Map<String, Role> existingRoles = roleRepository.findAll()
                .stream()
                .collect(Collectors.toMap(role -> role.getName().toUpperCase(), role -> role));

        DEFAULT_ROLE_PERMISSIONS.forEach((roleName, permissions) -> {
            String normalizedRoleName = roleName.toUpperCase();
            Role role = existingRoles.computeIfAbsent(normalizedRoleName, key -> {
                Role newRole = new Role();
                newRole.setName(normalizedRoleName);
                newRole.setDescription("%s role".formatted(normalizedRoleName));
                Role savedRole = roleRepository.save(newRole);
                log.info("Created default role {}", normalizedRoleName);
                return savedRole;
            });

            // Codex 019dd818 iter-14 (A + V16): granule-aware skip.
            //
            // Eğer role granule shortcut model'ine geçmişse (updateRoleGranules
            // endpoint'i üzerinden), legacy FK seed çalıştırmamalı. Aksi halde
            // V15/V16 cleanup migration sonrası initializer her startup'ta eski
            // FK rows'i geri ekler ve mixed state yeniden oluşur.
            //
            // Detection: permission_id IS NULL + type/key/grant tamamı set
            // olan en az 1 row varsa role granule modelinde sayılır. Bu role
            // için seed flow tamamen skip edilir (bootstrap fresh role'lerde
            // davranış değişmez — onlar hâlâ FK seed alır).
            if (usesGranuleModel(role)) {
                log.debug(
                        "Role {} uses granule shortcut model — skipping legacy FK seed",
                        normalizedRoleName);
                return;
            }

            // 2026-04-29: dual data model — legacy rows have permission_id FK,
            // new (Codex S1-S2) rows have permission_type + permission_key without FK.
            // Initializer'ın görevi legacy code-based permission'ları seedlemek;
            // null FK rows zaten yeni modeldeki granule shortcut'lar — skip safely.
            Set<String> currentPermissionCodes = role.getRolePermissions()
                    .stream()
                    .filter(rp -> rp.getPermission() != null)
                    .map(rp -> rp.getPermission().getCode().toLowerCase())
                    .collect(Collectors.toSet());

            permissions.stream()
                    .map(String::toLowerCase)
                    .filter(permissionCode -> !currentPermissionCodes.contains(permissionCode))
                    .map(existingPermissions::get)
                    .forEach(permission -> {
                        RolePermission rolePermission = new RolePermission();
                        rolePermission.setRole(role);
                        RolePermissionGranuleDefaults.apply(rolePermission, permission);
                        rolePermissionRepository.save(rolePermission);
                        log.info("Linked permission {} to role {}", permission.getCode(), role.getName());
                    });
        });

        // Codex 019dda1c iter-27: direct granule seed for the per-dashboard
        // permissions introduced in iter-26. Pre-iter-27 admin/finance/report
        // roles only carried coarse "REPORT_VIEW / REPORT_MANAGE" Permission
        // FK rows, so users assigned to those roles couldn't see any of the
        // 12 individual dashboards. We can't drive this through the legacy
        // DEFAULT_PERMISSIONS table because the per-dashboard keys are not
        // Permission entities — they are granule shortcuts owned by the
        // PermissionCatalogService. Seed them as raw role_permissions rows
        // (permission_id IS NULL) keyed on (role_id, type, key); idempotent
        // via the partial unique index uk_role_permissions_role_granule.
        // PR-D2: roles whose granule set actually changed during this seed pass
        // — used to publish RoleChangeEvent so TupleSyncService propagates the
        // new module/report granules to OpenFGA after the transaction commits.
        Set<Long> rolesNeedingTuplePropagation = new HashSet<>();

        DEFAULT_ROLE_GRANULES.forEach((roleName, granules) -> {
            String normalizedRoleName = roleName.toUpperCase();
            Role role = existingRoles.get(normalizedRoleName);
            if (role == null) {
                // Role wasn't created by the legacy seed loop above (e.g. the
                // role mapping is missing from DEFAULT_ROLE_PERMISSIONS). Skip
                // silently — granule seeding shouldn't materialize new roles
                // by itself, that's the legacy pass's job.
                return;
            }
            // Existing granule rows for this role (permission_id IS NULL).
            Set<String> existingGranuleKeys = role.getRolePermissions().stream()
                    .filter(rp -> rp.getPermission() == null)
                    .filter(rp -> rp.getPermissionType() != null && rp.getPermissionKey() != null)
                    .map(rp -> rp.getPermissionType().name() + ":" + rp.getPermissionKey())
                    .collect(Collectors.toSet());

            for (GranuleSeed seed : granules) {
                String dedupKey = seed.type().name() + ":" + seed.key();
                if (existingGranuleKeys.contains(dedupKey)) continue;
                RolePermission rolePermission = new RolePermission(
                        role, seed.type(), seed.key(), seed.grant());
                rolePermissionRepository.save(rolePermission);
                log.info("Seeded default granule {}/{}/{} for role {}",
                        seed.type(), seed.key(), seed.grant(), normalizedRoleName);
                if (role.getId() != null) {
                    rolesNeedingTuplePropagation.add(role.getId());
                }
            }
        });

        // PR-D2: publish RoleChangeEvent for every role whose granules
        // changed. The handler is @TransactionalEventListener(BEFORE_COMMIT
        // + AFTER_COMMIT) — outbox row written in this transaction (durable),
        // OpenFGA tuple propagation runs async after commit (RoleChangeEventHandler
        // → TupleSyncService.propagateRoleChange). This guarantees the
        // IMPERSONATION_AUDIT MANAGE granule reaches OpenFGA without
        // running tuple-sync inside the initializer transaction (which would
        // mix DB rollback risk with FGA writes).
        for (Long roleId : rolesNeedingTuplePropagation) {
            eventPublisher.publishEvent(new RoleChangeEvent(roleId));
            log.debug("Published RoleChangeEvent for role {} (granule seed propagation)", roleId);
        }
    }

    /**
     * Codex 019dd818 iter-14 + iter-16: role granule shortcut model'inde mi?
     *
     * <p>iter-14 (Plan A) row-shape predicate: {@code permission_id IS NULL} +
     * {@code permission_type + permission_key + grant_type} fully populated.
     * Bu pattern'i taşıyan en az 1 row varsa role granule-managed sayılır.
     *
     * <p>iter-16 (Plan C) marker predicate: row-shape signal **boş replace**
     * sonrası kayboluyordu. Kullanıcı tüm modülleri NONE seçip kaydederse
     * (PUT /granules permissions: []) role'da hiç row kalmaz, boot'ta
     * row-shape predicate false döner ve initializer legacy FK seed flow'una
     * düşer. {@link com.example.permission.model.PermissionModel#GRANULE}
     * marker'ı bu durumu kalıcı işaretler — empty replace sonrası bile boot
     * skip korunur.
     *
     * <p>Predicate "marker OR row-shape" şeklinde defansif: V17 backfill
     * marker'ı row-shape ile senkronize eder; future drift senaryosunda
     * ikinci kanıt (row-shape) hâlâ mode'u doğrular.
     */
    static boolean usesGranuleModel(Role role) {
        if (role.getPermissionModel() == com.example.permission.model.PermissionModel.GRANULE) {
            return true;
        }
        return role.getRolePermissions().stream()
                .anyMatch(rp -> rp.getPermission() == null
                        && rp.getPermissionType() != null
                        && rp.getPermissionKey() != null
                        && rp.getGrantType() != null);
    }
}
