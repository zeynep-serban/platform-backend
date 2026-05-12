package com.example.user.dto.v1;

import java.time.LocalDateTime;

public class UserDetailDto extends UserSummaryDto {

    private Integer sessionTimeoutMinutes;
    private String locale;

    /**
     * Keycloak subject (UUID) mapped to this platform user. Codex thread
     * {@code 019e1bed} REVISE-7 (hotfix-3): re-exposed on the V1 detail
     * endpoint after live testai smoke proved the V1 controller path
     * ({@code /api/v1/users/{id}}, used by auth-service for backend-
     * authoritative impersonation target subject resolution) returns
     * {@link UserDetailDto} — NOT {@link com.example.user.dto.UserResponse}
     * — and the previous REVISE-5 hotfix only re-exposed the field on
     * the legacy {@code /api/users/{id}} controller's DTO.
     *
     * <p>Same non-sensitivity rationale as {@code UserResponse.kcSubject}:
     * the admin JWT is already gateway-authority-checked + superAdmin-gated
     * at impersonation start; the field is the user's KC subject UUID,
     * which is the same identifier every admin's own JWT carries.
     *
     * <p>Follow-up: fix user-service KC issuer drift
     * ({@code http://localhost:8081/realms/serban/...} unreachable from
     * inside the user-service pod, should be {@code http://keycloak:8080})
     * so the service-token internal endpoint
     * {@code /api/users/internal/{id}/impersonation-target} works and
     * the kcSubject leak can be reverted from both public DTOs.
     */
    private String kcSubject;

    public UserDetailDto() {
        super();
    }

    public UserDetailDto(Long id,
                         String name,
                         String email,
                         String role,
                         boolean enabled,
                         LocalDateTime createDate,
                         LocalDateTime lastLogin,
                         Integer sessionTimeoutMinutes,
                         String locale) {
        super(id, name, email, role, enabled, createDate, lastLogin);
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.locale = locale;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getKcSubject() {
        return kcSubject;
    }

    public void setKcSubject(String kcSubject) {
        this.kcSubject = kcSubject;
    }
}
