package com.example.permission.dto.v1;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthzMeResponseDto {
    private String userId;
    private Set<String> permissions;        // legacy — backward compat
    private List<String> allowedModules;    // frontend compatibility
    private List<AuthzScopeSummaryDto> scopes;
    private boolean superAdmin;
    private List<ScopeSummaryDto> allowedScopes;

    // STORY-0318 additions
    private List<String> roles;
    private Map<String, String> modules;     // key → "VIEW" | "MANAGE"
    private Map<String, String> actions;     // key → "ALLOW" | "DENY"
    private Map<String, String> reports;     // key → "ALLOW" | "DENY"

    // P0 cache invalidation (CNS-20260410-001)
    private Long authzVersion;

    /**
     * Numeric canonical subscriber id (Faz 23.5 hardening — Codex thread
     * {@code 019e0316} iter-3 AGREE absorb).
     *
     * <p>Set <i>only</i> when {@code userId} resolves to a numeric value
     * (the platform's DB user id). UUID/sub fallback responses leave
     * this {@code null} so the alias does not silently leak the drift it
     * was created to remove.
     *
     * <p>The frontend should prefer this field over {@code userId} once
     * the canonical {@code subscriberId} JWT claim ships; until then the
     * value mirrors the numeric {@code userId} as a forward-compatible
     * alias.
     */
    private Long subscriberId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }

    public List<String> getAllowedModules() { return allowedModules; }
    public void setAllowedModules(List<String> allowedModules) { this.allowedModules = allowedModules; }

    public List<AuthzScopeSummaryDto> getScopes() { return scopes; }
    public void setScopes(List<AuthzScopeSummaryDto> scopes) { this.scopes = scopes; }

    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }

    public List<ScopeSummaryDto> getAllowedScopes() { return allowedScopes; }
    public void setAllowedScopes(List<ScopeSummaryDto> allowedScopes) { this.allowedScopes = allowedScopes; }

    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }

    public Map<String, String> getModules() { return modules; }
    public void setModules(Map<String, String> modules) { this.modules = modules; }

    public Map<String, String> getActions() { return actions; }
    public void setActions(Map<String, String> actions) { this.actions = actions; }

    public Map<String, String> getReports() { return reports; }
    public void setReports(Map<String, String> reports) { this.reports = reports; }

    public Long getAuthzVersion() { return authzVersion; }
    public void setAuthzVersion(Long authzVersion) { this.authzVersion = authzVersion; }

    public Long getSubscriberId() { return subscriberId; }
    public void setSubscriberId(Long subscriberId) { this.subscriberId = subscriberId; }
}
