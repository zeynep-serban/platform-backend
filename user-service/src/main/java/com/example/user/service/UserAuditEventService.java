package com.example.user.service;

import com.example.user.model.UserAuditEvent;
import com.example.user.permission.UserAuditMirrorClient;
import com.example.user.repository.UserAuditEventRepository;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserAuditEventService {
    private final UserAuditEventRepository repository;
    private final UserAuditMirrorClient userAuditMirrorClient;

    public UserAuditEventService(UserAuditEventRepository repository,
                                 UserAuditMirrorClient userAuditMirrorClient) {
        this.repository = repository;
        this.userAuditMirrorClient = userAuditMirrorClient;
    }

    @Transactional
    public UserAuditEvent recordUpdateEvent(Long performedBy, Long targetUserId, String details) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_UPDATE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(details);
        return repository.save(event);
    }

    @Transactional
    public UserAuditEvent recordActivationEvent(Long performedBy, Long targetUserId, boolean active) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType(active ? "USER_ACTIVATE" : "USER_DEACTIVATE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails("User %s by %d".formatted(active ? "activated" : "deactivated", performedBy));
        return repository.save(event);
    }

    /**
     * Records a soft-delete (tombstone) event. INSERT-only: a fresh
     * {@link UserAuditEvent} with no id, persisted via {@code repository.save}
     * (Codex 019ea573, #770 Phase 2 — append-only audit, no UPDATE).
     */
    @Transactional
    public UserAuditEvent recordDeleteEvent(Long performedBy, Long targetUserId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_DELETE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails("User %d soft-deleted by %s".formatted(targetUserId, String.valueOf(performedBy)));
        return repository.save(event);
    }

    /**
     * Records a restore (un-tombstone) event. INSERT-only, mirrors
     * {@link #recordDeleteEvent} (Codex 019ea573, #770 Phase 2).
     */
    @Transactional
    public UserAuditEvent recordRestoreEvent(Long performedBy, Long targetUserId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_RESTORE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails("User %d restored by %s".formatted(targetUserId, String.valueOf(performedBy)));
        return repository.save(event);
    }

    @Transactional
    public UserAuditEvent recordExportEvent(Long performedBy, String details, boolean success) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType(success ? "USER_EXPORT" : "USER_EXPORT_FAILED");
        event.setPerformedBy(performedBy);
        event.setDetails(details);
        return repository.save(event);
    }

    @Transactional
    public UserAuditEvent recordSessionTimeoutSyncEvent(Long performedBy,
                                                        Long targetUserId,
                                                        String userEmail,
                                                        Integer previousTimeoutMinutes,
                                                        Integer nextTimeoutMinutes,
                                                        Integer expectedVersion,
                                                        Integer appliedVersion,
                                                        String source,
                                                        Integer attemptCount,
                                                        String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_SESSION_TIMEOUT_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildSessionTimeoutSyncDetails(
                performedBy,
                previousTimeoutMinutes,
                nextTimeoutMinutes,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.session-bootstrap");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("sessionTimeoutMinutes", previousTimeoutMinutes);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sessionTimeoutMinutes", nextTimeoutMinutes);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorSessionTimeoutSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordSessionTimeoutConflictEvent(Long performedBy,
                                                            Long targetUserId,
                                                            String userEmail,
                                                            Integer currentTimeoutMinutes,
                                                            Integer currentVersion,
                                                            String source,
                                                            String conflictReason,
                                                            Integer attemptCount,
                                                            String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_SESSION_TIMEOUT_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildSessionTimeoutConflictDetails(
                performedBy,
                currentTimeoutMinutes,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.session-bootstrap");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentTimeoutMinutes", currentTimeoutMinutes);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("sessionTimeoutMinutes", currentTimeoutMinutes);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sessionTimeoutMinutes", currentTimeoutMinutes);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorSessionTimeoutConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordNotificationPreferenceSyncEvent(Long performedBy,
                                                                Long targetUserId,
                                                                String userEmail,
                                                                String channel,
                                                                boolean previousEnabled,
                                                                String previousFrequency,
                                                                boolean nextEnabled,
                                                                String nextFrequency,
                                                                Integer expectedVersion,
                                                                Integer appliedVersion,
                                                                String source,
                                                                Integer attemptCount,
                                                                String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_NOTIFICATION_PREFERENCE_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildNotificationPreferenceSyncDetails(
                performedBy,
                channel,
                previousEnabled,
                previousFrequency,
                nextEnabled,
                nextFrequency,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.notification-preferences");
        metadata.put("resourceKey", buildNotificationPreferenceResourceKey(userEmail, channel));
        metadata.put("channel", channel);
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("channel", channel);
        before.put("enabled", previousEnabled);
        before.put("frequency", previousFrequency);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("channel", channel);
        after.put("enabled", nextEnabled);
        after.put("frequency", nextFrequency);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorNotificationPreferenceSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordNotificationPreferenceConflictEvent(Long performedBy,
                                                                    Long targetUserId,
                                                                    String userEmail,
                                                                    String channel,
                                                                    boolean currentEnabled,
                                                                    String currentFrequency,
                                                                    Integer currentVersion,
                                                                    String source,
                                                                    String conflictReason,
                                                                    Integer attemptCount,
                                                                    String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_NOTIFICATION_PREFERENCE_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildNotificationPreferenceConflictDetails(
                performedBy,
                channel,
                currentEnabled,
                currentFrequency,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.notification-preferences");
        metadata.put("resourceKey", buildNotificationPreferenceResourceKey(userEmail, channel));
        metadata.put("channel", channel);
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentEnabled", currentEnabled);
        metadata.put("currentFrequency", currentFrequency);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("channel", channel);
        before.put("enabled", currentEnabled);
        before.put("frequency", currentFrequency);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("channel", channel);
        after.put("enabled", currentEnabled);
        after.put("frequency", currentFrequency);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorNotificationPreferenceConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordLocaleSyncEvent(Long performedBy,
                                                Long targetUserId,
                                                String userEmail,
                                                String previousLocale,
                                                String nextLocale,
                                                Integer expectedVersion,
                                                Integer appliedVersion,
                                                String source,
                                                Integer attemptCount,
                                                String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_LOCALE_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildLocaleSyncDetails(
                performedBy,
                previousLocale,
                nextLocale,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.locale-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("locale", previousLocale);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("locale", nextLocale);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorLocaleSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordLocaleConflictEvent(Long performedBy,
                                                    Long targetUserId,
                                                    String userEmail,
                                                    String currentLocale,
                                                    Integer currentVersion,
                                                    String source,
                                                    String conflictReason,
                                                    Integer attemptCount,
                                                    String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_LOCALE_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildLocaleConflictDetails(
                performedBy,
                currentLocale,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.locale-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentLocale", currentLocale);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("locale", currentLocale);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("locale", currentLocale);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorLocaleConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordTimezoneSyncEvent(Long performedBy,
                                                  Long targetUserId,
                                                  String userEmail,
                                                  String previousTimezone,
                                                  String nextTimezone,
                                                  Integer expectedVersion,
                                                  Integer appliedVersion,
                                                  String source,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_TIMEZONE_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildTimezoneSyncDetails(
                performedBy,
                previousTimezone,
                nextTimezone,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.timezone-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("timezone", previousTimezone);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("timezone", nextTimezone);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorTimezoneSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordTimezoneConflictEvent(Long performedBy,
                                                      Long targetUserId,
                                                      String userEmail,
                                                      String currentTimezone,
                                                      Integer currentVersion,
                                                      String source,
                                                      String conflictReason,
                                                      Integer attemptCount,
                                                      String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_TIMEZONE_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildTimezoneConflictDetails(
                performedBy,
                currentTimezone,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.timezone-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentTimezone", currentTimezone);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("timezone", currentTimezone);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("timezone", currentTimezone);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorTimezoneConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordDateFormatSyncEvent(Long performedBy,
                                                    Long targetUserId,
                                                    String userEmail,
                                                    String previousDateFormat,
                                                    String nextDateFormat,
                                                    Integer expectedVersion,
                                                    Integer appliedVersion,
                                                    String source,
                                                    Integer attemptCount,
                                                    String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_DATE_FORMAT_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildDateFormatSyncDetails(
                performedBy,
                previousDateFormat,
                nextDateFormat,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.date-format-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("dateFormat", previousDateFormat);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dateFormat", nextDateFormat);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorDateFormatSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordDateFormatConflictEvent(Long performedBy,
                                                        Long targetUserId,
                                                        String userEmail,
                                                        String currentDateFormat,
                                                        Integer currentVersion,
                                                        String source,
                                                        String conflictReason,
                                                        Integer attemptCount,
                                                        String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_DATE_FORMAT_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildDateFormatConflictDetails(
                performedBy,
                currentDateFormat,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.date-format-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentDateFormat", currentDateFormat);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("dateFormat", currentDateFormat);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dateFormat", currentDateFormat);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorDateFormatConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordTimeFormatSyncEvent(Long performedBy,
                                                    Long targetUserId,
                                                    String userEmail,
                                                    String previousTimeFormat,
                                                    String nextTimeFormat,
                                                    Integer expectedVersion,
                                                    Integer appliedVersion,
                                                    String source,
                                                    Integer attemptCount,
                                                    String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_TIME_FORMAT_SYNCED");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildTimeFormatSyncDetails(
                performedBy,
                previousTimeFormat,
                nextTimeFormat,
                expectedVersion,
                appliedVersion,
                source,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.time-format-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("expectedVersion", expectedVersion);
        metadata.put("appliedVersion", appliedVersion);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("timeFormat", previousTimeFormat);
        before.put("version", expectedVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("timeFormat", nextTimeFormat);
        after.put("version", appliedVersion);

        userAuditMirrorClient.mirrorTimeFormatSync(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    @Transactional
    public UserAuditEvent recordTimeFormatConflictEvent(Long performedBy,
                                                        Long targetUserId,
                                                        String userEmail,
                                                        String currentTimeFormat,
                                                        Integer currentVersion,
                                                        String source,
                                                        String conflictReason,
                                                        Integer attemptCount,
                                                        String queueActionId) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_TIME_FORMAT_SYNC_CONFLICT");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(buildTimeFormatConflictDetails(
                performedBy,
                currentTimeFormat,
                currentVersion,
                source,
                conflictReason,
                attemptCount,
                queueActionId
        ));
        UserAuditEvent saved = repository.save(event);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.time-format-preferences");
        metadata.put("resourceKey", buildResourceKey(userEmail));
        metadata.put("source", normalizeSource(source));
        metadata.put("currentVersion", currentVersion);
        metadata.put("currentTimeFormat", currentTimeFormat);
        metadata.put("attemptCount", attemptCount);
        metadata.put("queueActionId", queueActionId);
        metadata.put("conflictReason", conflictReason);
        metadata.put("localAuditId", saved.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("timeFormat", currentTimeFormat);
        before.put("version", currentVersion);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("timeFormat", currentTimeFormat);
        after.put("version", currentVersion);
        after.put("conflictReason", conflictReason);

        userAuditMirrorClient.mirrorTimeFormatConflict(
                performedBy,
                userEmail,
                event.getDetails(),
                metadata,
                before,
                after
        );

        return saved;
    }

    private String buildSessionTimeoutSyncDetails(Long performedBy,
                                                  Integer previousTimeoutMinutes,
                                                  Integer nextTimeoutMinutes,
                                                  Integer expectedVersion,
                                                  Integer appliedVersion,
                                                  String source,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        return "Session timeout synced by %d via %s [%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                previousTimeoutMinutes,
                nextTimeoutMinutes,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildSessionTimeoutConflictDetails(Long performedBy,
                                                      Integer currentTimeoutMinutes,
                                                      Integer currentVersion,
                                                      String source,
                                                      String conflictReason,
                                                      Integer attemptCount,
                                                      String queueActionId) {
        return "Session timeout conflict by %d via %s [currentTimeout=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                currentTimeoutMinutes,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildNotificationPreferenceSyncDetails(Long performedBy,
                                                          String channel,
                                                          boolean previousEnabled,
                                                          String previousFrequency,
                                                          boolean nextEnabled,
                                                          String nextFrequency,
                                                          Integer expectedVersion,
                                                          Integer appliedVersion,
                                                          String source,
                                                          Integer attemptCount,
                                                          String queueActionId) {
        return "Notification preference synced by %d via %s [channel=%s, enabled=%s->%s, frequency=%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                channel,
                previousEnabled,
                nextEnabled,
                previousFrequency,
                nextFrequency,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildNotificationPreferenceConflictDetails(Long performedBy,
                                                              String channel,
                                                              boolean currentEnabled,
                                                              String currentFrequency,
                                                              Integer currentVersion,
                                                              String source,
                                                              String conflictReason,
                                                              Integer attemptCount,
                                                              String queueActionId) {
        return "Notification preference conflict by %d via %s [channel=%s, enabled=%s, frequency=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                channel,
                currentEnabled,
                currentFrequency,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildLocaleSyncDetails(Long performedBy,
                                          String previousLocale,
                                          String nextLocale,
                                          Integer expectedVersion,
                                          Integer appliedVersion,
                                          String source,
                                          Integer attemptCount,
                                          String queueActionId) {
        return "User locale synced by %d via %s [locale=%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                previousLocale,
                nextLocale,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildLocaleConflictDetails(Long performedBy,
                                              String currentLocale,
                                              Integer currentVersion,
                                              String source,
                                              String conflictReason,
                                              Integer attemptCount,
                                              String queueActionId) {
        return "User locale conflict by %d via %s [locale=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                currentLocale,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildTimezoneSyncDetails(Long performedBy,
                                            String previousTimezone,
                                            String nextTimezone,
                                            Integer expectedVersion,
                                            Integer appliedVersion,
                                            String source,
                                            Integer attemptCount,
                                            String queueActionId) {
        return "User timezone synced by %d via %s [timezone=%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                previousTimezone,
                nextTimezone,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildTimezoneConflictDetails(Long performedBy,
                                                String currentTimezone,
                                                Integer currentVersion,
                                                String source,
                                                String conflictReason,
                                                Integer attemptCount,
                                                String queueActionId) {
        return "User timezone conflict by %d via %s [timezone=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                currentTimezone,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildDateFormatSyncDetails(Long performedBy,
                                              String previousDateFormat,
                                              String nextDateFormat,
                                              Integer expectedVersion,
                                              Integer appliedVersion,
                                              String source,
                                              Integer attemptCount,
                                              String queueActionId) {
        return "User date format synced by %d via %s [dateFormat=%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                previousDateFormat,
                nextDateFormat,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildDateFormatConflictDetails(Long performedBy,
                                                  String currentDateFormat,
                                                  Integer currentVersion,
                                                  String source,
                                                  String conflictReason,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        return "User date format conflict by %d via %s [dateFormat=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                currentDateFormat,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildTimeFormatSyncDetails(Long performedBy,
                                              String previousTimeFormat,
                                              String nextTimeFormat,
                                              Integer expectedVersion,
                                              Integer appliedVersion,
                                              String source,
                                              Integer attemptCount,
                                              String queueActionId) {
        return "User time format synced by %d via %s [timeFormat=%s->%s, expectedVersion=%s, appliedVersion=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                previousTimeFormat,
                nextTimeFormat,
                expectedVersion,
                appliedVersion,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildTimeFormatConflictDetails(Long performedBy,
                                                  String currentTimeFormat,
                                                  Integer currentVersion,
                                                  String source,
                                                  String conflictReason,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        return "User time format conflict by %d via %s [timeFormat=%s, currentVersion=%s, reason=%s, attempt=%s, queueActionId=%s]".formatted(
                performedBy,
                normalizeSource(source),
                currentTimeFormat,
                currentVersion,
                conflictReason,
                attemptCount,
                safeQueueActionId(queueActionId)
        );
    }

    private String buildResourceKey(String userEmail) {
        if (!StringUtils.hasText(userEmail)) {
            return "profile:unknown";
        }
        return "profile:" + userEmail.trim().toLowerCase();
    }

    private String buildNotificationPreferenceResourceKey(String userEmail, String channel) {
        if (!StringUtils.hasText(userEmail)) {
            return "notification-preference:unknown:" + safeChannel(channel);
        }
        return "notification-preference:" + userEmail.trim().toLowerCase() + ":" + safeChannel(channel);
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim() : "mobile-offline-queue";
    }

    private String safeQueueActionId(String queueActionId) {
        return StringUtils.hasText(queueActionId) ? queueActionId.trim() : "n/a";
    }

    private String safeChannel(String channel) {
        return StringUtils.hasText(channel) ? channel.trim().toLowerCase() : "unknown";
    }
}
