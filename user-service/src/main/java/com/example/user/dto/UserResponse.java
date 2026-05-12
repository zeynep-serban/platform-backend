package com.example.user.dto;

import java.time.LocalDateTime;

/**
 * Public-facing representation of a user without sensitive fields.
 */
public class UserResponse {
    private Long id;
    private String name;
    private String email;
    private String role;
    private boolean enabled;
    private LocalDateTime createDate;
    private LocalDateTime lastLogin;
    private Integer sessionTimeoutMinutes;
    private String auditId;

    /**
     * Keycloak subject (UUID) mapped to this platform user. Codex thread
     * {@code 019e1bed} REVISE-5 (hotfix): re-exposed after the
     * service-token internal endpoint hit a pre-existing user-service KC
     * issuer config drift (`http://localhost:8081/realms/serban/...`
     * unreachable from inside the user-service pod). Going through the
     * public {@code /api/v1/users/{id}} path lets the admin JWT (already
     * authority-checked at the gateway + superAdmin-gated at
     * impersonation start) act as the trust anchor. The field is not
     * security-sensitive — the same KC subject ships in every admin's
     * own JWT, and the admin grid already lists all users with admin
     * scope. A follow-up PR will fix the user-service KC issuer drift
     * and restore the service-token internal endpoint as the cleaner
     * path.
     */
    private String kcSubject;

    public UserResponse() {
    }

    public UserResponse(Long id, String name, String email, String role, boolean enabled,
                        LocalDateTime createDate, LocalDateTime lastLogin, Integer sessionTimeoutMinutes) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
        this.enabled = enabled;
        this.createDate = createDate;
        this.lastLogin = lastLogin;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getKcSubject() {
        return kcSubject;
    }

    public void setKcSubject(String kcSubject) {
        this.kcSubject = kcSubject;
    }
}
