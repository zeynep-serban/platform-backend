package com.example.user.controller;

import com.example.user.dto.v1.NotificationPreferenceDto;
import com.example.user.dto.v1.NotificationPreferenceMutationDto;
import com.example.user.dto.v1.NotificationPreferenceSnapshotDto;
import com.example.user.dto.v1.NotificationPreferencesResponseDto;
import com.example.user.dto.v1.UpdateNotificationPreferenceRequestDto;
import com.example.user.dto.v1.UpdateNotificationPreferencesRequestDto;
import com.example.user.model.User;
import com.example.user.model.UserNotificationPreference;
import com.example.user.service.CurrentUserResolver;
import com.example.user.service.NotificationPreferencesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferencesControllerV1 {

    private final NotificationPreferencesService notificationPreferencesService;
    private final CurrentUserResolver currentUserResolver;

    public NotificationPreferencesControllerV1(NotificationPreferencesService notificationPreferencesService,
                                               CurrentUserResolver currentUserResolver) {
        this.notificationPreferencesService = notificationPreferencesService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ResponseEntity<NotificationPreferencesResponseDto> getPreferences() {
        User currentUser = requireCurrentUser();
        List<UserNotificationPreference> prefs = notificationPreferencesService.getOrInitForUser(currentUser.getId());
        return ResponseEntity.ok(new NotificationPreferencesResponseDto(toDtoList(prefs)));
    }

    @GetMapping("/{channel}")
    public ResponseEntity<NotificationPreferenceSnapshotDto> getPreferenceSnapshot(@PathVariable String channel) {
        User currentUser = requireCurrentUser();
        UserNotificationPreference preference =
                notificationPreferencesService.getOrInitChannelForUser(currentUser.getId(), channel);
        return ResponseEntity.ok(new NotificationPreferenceSnapshotDto(
                buildNotificationResourceKey(currentUser, preference.getChannel()),
                preference.getChannel(),
                preference.isEnabled(),
                preference.getFrequency(),
                safeVersion(preference)
        ));
    }

    @PatchMapping
    public ResponseEntity<NotificationPreferencesResponseDto> updatePreferences(@RequestBody UpdateNotificationPreferencesRequestDto request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_body");
        }

        User currentUser = requireCurrentUser();
        List<UserNotificationPreference> updated = notificationPreferencesService.updateForUser(currentUser.getId(), request.getPreferences());
        return ResponseEntity.ok(new NotificationPreferencesResponseDto(toDtoList(updated)));
    }

    @PutMapping("/{channel}")
    public ResponseEntity<NotificationPreferenceMutationDto> updatePreference(@PathVariable String channel,
                                                                              @RequestBody UpdateNotificationPreferenceRequestDto request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_body");
        }

        User currentUser = requireCurrentUser();
        var result = notificationPreferencesService.syncOwnPreference(
                currentUser.getId(),
                currentUser.getEmail(),
                channel,
                request.getEnabled(),
                request.getFrequency(),
                request.getExpectedVersion(),
                request.getSource(),
                request.getAttemptCount(),
                request.getQueueActionId()
        );

        String resourceKey = buildNotificationResourceKey(currentUser, result.channel());
        NotificationPreferenceMutationDto response = result.isConflict()
                ? NotificationPreferenceMutationDto.conflict(
                        prefixUserAuditId(result.auditId()),
                        resourceKey,
                        result.channel(),
                        result.enabled(),
                        result.frequency(),
                        safeVersion(result.version()),
                        result.source(),
                        result.message(),
                        result.errorCode(),
                        result.conflictReason()
                )
                : NotificationPreferenceMutationDto.ok(
                        prefixUserAuditId(result.auditId()),
                        resourceKey,
                        result.channel(),
                        result.enabled(),
                        result.frequency(),
                        safeVersion(result.version()),
                        result.source()
                );

        return ResponseEntity.status(result.isConflict() ? HttpStatus.CONFLICT : HttpStatus.OK).body(response);
    }

    private List<NotificationPreferenceDto> toDtoList(List<UserNotificationPreference> prefs) {
        if (prefs == null) return List.of();
        return prefs.stream()
                .map(p -> new NotificationPreferenceDto(
                        p.getChannel(),
                        p.isEnabled(),
                        p.getFrequency(),
                        p.getUpdatedAt(),
                        safeVersion(p)
                ))
                .toList();
    }

    private String buildNotificationResourceKey(User user, String channel) {
        return "notification-preference:" + user.getEmail().trim().toLowerCase() + ":" + channel.trim().toLowerCase();
    }

    private String prefixUserAuditId(String auditId) {
        if (!StringUtils.hasText(auditId)) {
            return auditId;
        }
        return auditId.startsWith("user-") ? auditId : "user-" + auditId;
    }

    private int safeVersion(UserNotificationPreference preference) {
        if (preference == null || preference.getVersion() == null || preference.getVersion() < 0) {
            return 0;
        }
        return preference.getVersion();
    }

    private int safeVersion(Integer version) {
        if (version == null || version < 0) {
            return 0;
        }
        return version;
    }

    /**
     * Resolves the current request's backend {@link User} profile.
     *
     * <p>Delegates to the shared {@link CurrentUserResolver}; see
     * {@code UserControllerV1#requireCurrentUser()} for the rationale
     * (single source of truth + Keycloak user lazy-provision bridge
     * safety net).
     */
    private User requireCurrentUser() {
        return currentUserResolver.resolveCurrentUser();
    }
}
