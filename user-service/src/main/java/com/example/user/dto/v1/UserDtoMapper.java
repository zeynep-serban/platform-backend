package com.example.user.dto.v1;

import com.example.user.model.User;

public final class UserDtoMapper {

    private UserDtoMapper() {
    }

    public static UserSummaryDto toSummary(User user) {
        if (user == null) {
            return null;
        }
        UserSummaryDto dto = new UserSummaryDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreateDate(),
                user.getLastLogin()
        );
        dto.setSessionTimeoutMinutes(user.getSessionTimeoutMinutes());
        return dto;
    }

    public static UserDetailDto toDetail(User user) {
        if (user == null) {
            return null;
        }
        UserDetailDto dto = new UserDetailDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreateDate(),
                user.getLastLogin(),
                user.getSessionTimeoutMinutes(),
                user.getLocale()
        );
        // Codex 019e1bed REVISE-7 hotfix-3: populate kcSubject on the V1
        // detail payload so auth-service impersonation target resolution
        // (which calls GET /api/v1/users/{id}) sees a non-null subject.
        // See UserDetailDto.kcSubject Javadoc for the security rationale
        // + the user-service KC issuer drift follow-up that will let us
        // revert this leak to the internal service-token endpoint.
        dto.setKcSubject(user.getKcSubject());
        return dto;
    }
}
