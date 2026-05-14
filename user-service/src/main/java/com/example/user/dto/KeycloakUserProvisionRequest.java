package com.example.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Keycloak'tan gelen kullanıcı bilgilerini user-service veritabanına yansıtmak
 * için kullanılan DTO.
 */
public class KeycloakUserProvisionRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 2, max = 256)
    private String name;

    @Size(min = 2, max = 64)
    private String role;

    private Boolean enabled;

    private Integer sessionTimeoutMinutes;

    /**
     * Keycloak subject UUID (the "sub" claim of the user's KC tokens).
     * Codex 019e2022 follow-up: BUG #1 prevention — if this field is
     * null on a freshly-provisioned user, the auth-service impersonation
     * broker rejects every impersonation attempt with
     * TARGET_SUBJECT_UNRESOLVABLE (Step 1f). The caller should always
     * supply this value at provision time. Optional for backward
     * compatibility with older callers that handled backfill via the
     * separate {@code RB-kc-subject-backfill.md} runbook.
     */
    @Size(max = 64)
    private String kcSubject;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getKcSubject() {
        return kcSubject;
    }

    public void setKcSubject(String kcSubject) {
        this.kcSubject = kcSubject;
    }
}
