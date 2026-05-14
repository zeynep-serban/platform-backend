package com.example.user.service;

import com.example.user.dto.KeycloakUserProvisionRequest;
import com.example.user.dto.RegisterRequest;
import com.example.user.dto.UpdateUserRequest;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.authz.AuthorizationContextService;
import com.example.commonauth.AuthorizationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Expression;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService implements UserDetailsService { // UserDetailsService arayüzünü uygular

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String SESSION_TIMEOUT_SYNC_CONFLICT_CODE = "USER_SESSION_TIMEOUT_SYNC_CONFLICT";
    private static final String LOCALE_SYNC_CONFLICT_CODE = "USER_LOCALE_SYNC_CONFLICT";
    private static final String TIMEZONE_SYNC_CONFLICT_CODE = "USER_TIMEZONE_SYNC_CONFLICT";
    private static final String DATE_FORMAT_SYNC_CONFLICT_CODE = "USER_DATE_FORMAT_SYNC_CONFLICT";
    private static final String TIME_FORMAT_SYNC_CONFLICT_CODE = "USER_TIME_FORMAT_SYNC_CONFLICT";
    private static final String STALE_EXPECTED_VERSION_REASON = "STALE_EXPECTED_VERSION";
    private static final Set<String> ALLOWED_LOCALES = Set.of("tr", "en", "de", "es");
    private static final Set<String> ALLOWED_DATE_FORMATS = Set.of(
            "dd.MM.yyyy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
    );
    private static final Set<String> ALLOWED_TIME_FORMATS = Set.of(
            "HH:mm",
            "hh:mm a",
            "HH.mm"
    );

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserAuditEventService userAuditEventService;
    private final AuthorizationContextService authorizationContextService;
    private final int maxSessionTimeoutMinutes;

    public record SessionTimeoutSyncResult(String status,
                                           Integer sessionTimeoutMinutes,
                                           Integer version,
                                           String auditId,
                                           String source,
                                           String message,
                                           String errorCode,
                                           String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public record LocaleSyncResult(String status,
                                   String locale,
                                   Integer version,
                                   String auditId,
                                   String source,
                                   String message,
                                   String errorCode,
                                   String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public record TimezoneSyncResult(String status,
                                     String timezone,
                                     Integer version,
                                     String auditId,
                                     String source,
                                     String message,
                                     String errorCode,
                                     String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public record DateFormatSyncResult(String status,
                                       String dateFormat,
                                       Integer version,
                                       String auditId,
                                       String source,
                                       String message,
                                       String errorCode,
                                       String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public record TimeFormatSyncResult(String status,
                                       String timeFormat,
                                       Integer version,
                                       String auditId,
                                       String source,
                                       String message,
                                       String errorCode,
                                       String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       UserAuditEventService userAuditEventService,
                       AuthorizationContextService authorizationContextService,
                       @Value("${user.session-timeout.max-minutes:1440}") int configuredMaxSessionTimeoutMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userAuditEventService = userAuditEventService;
        this.authorizationContextService = authorizationContextService;
        this.maxSessionTimeoutMinutes = Math.max(User.DEFAULT_SESSION_TIMEOUT_MINUTES, configuredMaxSessionTimeoutMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void normalizeExistingSessionTimeouts() {
        int updated = userRepository.normalizeSessionTimeouts(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
        if (updated > 0) {
            log.info("Oturum süreleri varsayılan değere güncellendi. Etkilenen kayıt sayısı={}", updated);
        }
        int rolesUpdated = normalizeExistingRoles();
        if (rolesUpdated > 0) {
            log.info("Rol değerleri normalize edildi. Etkilenen kayıt sayısı={}", rolesUpdated);
        }
    }

    public User updateUser(Long userId, UpdateUserRequest updateRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        if (updateRequest.getName() != null && !updateRequest.getName().isBlank()) {
            user.setName(updateRequest.getName());
        }

        if (updateRequest.getRole() != null && !updateRequest.getRole().isBlank()) {
            user.setRole(updateRequest.getRole());
        }

        if (updateRequest.getEnabled() != null) {
            user.setEnabled(updateRequest.getEnabled());
        }

        if (updateRequest.getSessionTimeoutMinutes() != null) {
            int timeout = Math.max(1, Math.min(updateRequest.getSessionTimeoutMinutes(), maxSessionTimeoutMinutes));
            user.setSessionTimeoutMinutes(timeout);
        }

        if (StringUtils.hasText(updateRequest.getLocale())) {
            user.setLocale(resolveRequestedLocale(updateRequest.getLocale()));
        }

        return userRepository.save(user);
    }

    /**
     * Yeni bir kullanıcıyı kaydeder.
     * @param registerRequest Kayıt için gerekli bilgileri içeren DTO.
     * @return Kaydedilen User nesnesi.
     */
    public User registerUser(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalStateException("Bu email adresi zaten kullanılıyor.");
        }

        User newUser = new User();
        newUser.setName(registerRequest.getName());
        newUser.setEmail(registerRequest.getEmail());
        // Parolayı şifreleyerek kaydediyoruz.
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        newUser.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
        newUser.setLocale(User.DEFAULT_LOCALE);
        newUser.setTimezone(User.DEFAULT_TIMEZONE);
        newUser.setDateFormat(User.DEFAULT_DATE_FORMAT);
        newUser.setTimeFormat(User.DEFAULT_TIME_FORMAT);
        // Varsayılan olarak rolü "USER" ve hesabı "enabled" (aktif) olacak.

        return userRepository.save(newUser);
    }

    /**
     * Herkese açık kayıt uç noktası için kullanıcı oluşturur.
     * Hesap başlangıçta pasif (enabled=false) bırakılır, doğrulama sonrası admin tarafından aktifleştirilir.
     */
    public User registerUserPublic(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalStateException("Bu email adresi zaten kullanılıyor.");
        }

        User newUser = new User();
        newUser.setName(registerRequest.getName());
        newUser.setEmail(registerRequest.getEmail());
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        newUser.setEnabled(false);
        newUser.setRole("USER");
        newUser.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
        newUser.setLocale(User.DEFAULT_LOCALE);
        newUser.setTimezone(User.DEFAULT_TIMEZONE);
        newUser.setDateFormat(User.DEFAULT_DATE_FORMAT);
        newUser.setTimeFormat(User.DEFAULT_TIME_FORMAT);

        return userRepository.save(newUser);
    }

    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        if (!user.isEnabled()) {
            user.setEnabled(true);
            userRepository.save(user);
        }
    }

    public void updatePasswordInternal(Long userId, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    @Transactional
    public void updateLastLogin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        log.info("Son giriş tarihi güncellendi userId={}", userId);
    }

    @Transactional
    public User provisionFromKeycloak(KeycloakUserProvisionRequest request) {
        String email = request.getEmail().trim();
        Optional<User> existing = userRepository.findByEmail(email);
        User target = existing.orElseGet(() -> {
            User fresh = new User();
            fresh.setEmail(email);
            fresh.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            fresh.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
            fresh.setLocale(User.DEFAULT_LOCALE);
            fresh.setTimezone(User.DEFAULT_TIMEZONE);
            fresh.setDateFormat(User.DEFAULT_DATE_FORMAT);
            fresh.setTimeFormat(User.DEFAULT_TIME_FORMAT);
            fresh.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
            return fresh;
        });

        target.setName(request.getName());

        if (StringUtils.hasText(request.getRole())) {
            target.setRole(request.getRole());
        }

        if (request.getEnabled() != null) {
            target.setEnabled(request.getEnabled());
        }

        Integer requestedTimeout = request.getSessionTimeoutMinutes();
        if (requestedTimeout != null) {
            int safeTimeout = Math.max(1, Math.min(requestedTimeout, maxSessionTimeoutMinutes));
            target.setSessionTimeoutMinutes(safeTimeout);
        }

        target.setLocale(resolveRequestedLocale(target.getLocale()));
        target.setTimezone(resolveRequestedTimezone(target.getTimezone()));
        target.setDateFormat(resolveRequestedDateFormat(target.getDateFormat()));
        target.setTimeFormat(resolveRequestedTimeFormat(target.getTimeFormat()));

        // Codex 019e2022 follow-up — BUG #1 prevention: persist the
        // Keycloak subject UUID when the caller supplies one. Without
        // this the auth-service impersonation broker rejects every
        // impersonation attempt for the user with TARGET_SUBJECT_UNRESOLVABLE.
        // Only overwrite when the request actually carries a value; this
        // preserves any backfilled subject on the existing row.
        if (StringUtils.hasText(request.getKcSubject())) {
            target.setKcSubject(request.getKcSubject().trim());
        }

        return userRepository.save(target);
    }

    /**
     * Email adresine göre kullanıcıyı bulur.
     * @param email Aranacak email adresi.
     * @return Opsiyonel olarak User nesnesi.
     */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Transactional
    public User findRequiredByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Keycloak kullanıcısı bulundu ancak yerel profil yok: {}", email);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    public User findRequiredById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + id));
    }

    public String updateActivation(Long userId, boolean active, Long performedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
        if (user.isEnabled() != active) {
            user.setEnabled(active);
            userRepository.save(user);
        }
        if (performedBy != null) {
            var event = userAuditEventService.recordActivationEvent(performedBy, userId, active);
            if (event != null && event.getId() != null) {
                return event.getId().toString();
            }
        }
        return null;
    }

    @Transactional
    public SessionTimeoutSyncResult syncOwnSessionTimeout(Long userId,
                                                          Integer requestedTimeoutMinutes,
                                                          Integer expectedVersion,
                                                          String source,
                                                          Integer attemptCount,
                                                          String queueActionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));

        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }

        Integer currentVersion = safeVersion(user);
        if (!currentVersion.equals(expectedVersion)) {
            return buildConflictResult(user, source, "User profile version changed before replay.", attemptCount, queueActionId);
        }

        int safeTimeout = Math.max(1, Math.min(requestedTimeoutMinutes == null ? User.DEFAULT_SESSION_TIMEOUT_MINUTES : requestedTimeoutMinutes, maxSessionTimeoutMinutes));
        Integer previousTimeout = user.getSessionTimeoutMinutes();
        user.setSessionTimeoutMinutes(safeTimeout);

        try {
            User saved = userRepository.saveAndFlush(user);
            String auditId = null;
            if (!java.util.Objects.equals(previousTimeout, saved.getSessionTimeoutMinutes())) {
                var auditEvent = userAuditEventService.recordSessionTimeoutSyncEvent(
                        userId,
                        userId,
                        saved.getEmail(),
                        previousTimeout,
                        saved.getSessionTimeoutMinutes(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (auditEvent != null && auditEvent.getId() != null) {
                    auditId = auditEvent.getId().toString();
                }
            }
            return new SessionTimeoutSyncResult(
                    "ok",
                    saved.getSessionTimeoutMinutes(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "Session timeout synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            User latest = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
            return buildConflictResult(latest, source, "User profile version conflict detected during replay.", attemptCount, queueActionId);
        }
    }

    @Transactional
    public LocaleSyncResult syncOwnLocale(Long userId,
                                          String requestedLocale,
                                          Integer expectedVersion,
                                          String source,
                                          Integer attemptCount,
                                          String queueActionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));

        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }

        Integer currentVersion = safeVersion(user);
        if (!currentVersion.equals(expectedVersion)) {
            return buildLocaleConflictResult(user, source, "User locale version changed before replay.", attemptCount, queueActionId);
        }

        String previousLocale = user.getLocale();
        user.setLocale(resolveRequestedLocale(requestedLocale));

        try {
            User saved = userRepository.saveAndFlush(user);
            String auditId = null;
            if (!java.util.Objects.equals(previousLocale, saved.getLocale())) {
                var auditEvent = userAuditEventService.recordLocaleSyncEvent(
                        userId,
                        userId,
                        saved.getEmail(),
                        previousLocale,
                        saved.getLocale(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (auditEvent != null && auditEvent.getId() != null) {
                    auditId = auditEvent.getId().toString();
                }
            }

            return new LocaleSyncResult(
                    "ok",
                    saved.getLocale(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "User locale synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            User latest = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
            return buildLocaleConflictResult(latest, source, "User locale version conflict detected during replay.", attemptCount, queueActionId);
        }
    }

    @Transactional
    public TimezoneSyncResult syncOwnTimezone(Long userId,
                                              String requestedTimezone,
                                              Integer expectedVersion,
                                              String source,
                                              Integer attemptCount,
                                              String queueActionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));

        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }

        Integer currentVersion = safeVersion(user);
        if (!currentVersion.equals(expectedVersion)) {
            return buildTimezoneConflictResult(user, source, "User timezone version changed before replay.", attemptCount, queueActionId);
        }

        String previousTimezone = user.getTimezone();
        user.setTimezone(resolveRequestedTimezone(requestedTimezone));

        try {
            User saved = userRepository.saveAndFlush(user);
            String auditId = null;
            if (!java.util.Objects.equals(previousTimezone, saved.getTimezone())) {
                var auditEvent = userAuditEventService.recordTimezoneSyncEvent(
                        userId,
                        userId,
                        saved.getEmail(),
                        previousTimezone,
                        saved.getTimezone(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (auditEvent != null && auditEvent.getId() != null) {
                    auditId = auditEvent.getId().toString();
                }
            }

            return new TimezoneSyncResult(
                    "ok",
                    saved.getTimezone(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "User timezone synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            User latest = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
            return buildTimezoneConflictResult(latest, source, "User timezone version conflict detected during replay.", attemptCount, queueActionId);
        }
    }

    @Transactional
    public DateFormatSyncResult syncOwnDateFormat(Long userId,
                                                  String requestedDateFormat,
                                                  Integer expectedVersion,
                                                  String source,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));

        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }

        Integer currentVersion = safeVersion(user);
        if (!currentVersion.equals(expectedVersion)) {
            return buildDateFormatConflictResult(user, source, "User date format version changed before replay.", attemptCount, queueActionId);
        }

        String previousDateFormat = user.getDateFormat();
        user.setDateFormat(resolveRequestedDateFormat(requestedDateFormat));

        try {
            User saved = userRepository.saveAndFlush(user);
            String auditId = null;
            if (!java.util.Objects.equals(previousDateFormat, saved.getDateFormat())) {
                var auditEvent = userAuditEventService.recordDateFormatSyncEvent(
                        userId,
                        userId,
                        saved.getEmail(),
                        previousDateFormat,
                        saved.getDateFormat(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (auditEvent != null && auditEvent.getId() != null) {
                    auditId = auditEvent.getId().toString();
                }
            }

            return new DateFormatSyncResult(
                    "ok",
                    saved.getDateFormat(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "User date format synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            User latest = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
            return buildDateFormatConflictResult(latest, source, "User date format version conflict detected during replay.", attemptCount, queueActionId);
        }
    }

    @Transactional
    public TimeFormatSyncResult syncOwnTimeFormat(Long userId,
                                                  String requestedTimeFormat,
                                                  Integer expectedVersion,
                                                  String source,
                                                  Integer attemptCount,
                                                  String queueActionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));

        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }

        Integer currentVersion = safeVersion(user);
        if (!currentVersion.equals(expectedVersion)) {
            return buildTimeFormatConflictResult(user, source, "User time format version changed before replay.", attemptCount, queueActionId);
        }

        String previousTimeFormat = user.getTimeFormat();
        user.setTimeFormat(resolveRequestedTimeFormat(requestedTimeFormat));

        try {
            User saved = userRepository.saveAndFlush(user);
            String auditId = null;
            if (!java.util.Objects.equals(previousTimeFormat, saved.getTimeFormat())) {
                var auditEvent = userAuditEventService.recordTimeFormatSyncEvent(
                        userId,
                        userId,
                        saved.getEmail(),
                        previousTimeFormat,
                        saved.getTimeFormat(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (auditEvent != null && auditEvent.getId() != null) {
                    auditId = auditEvent.getId().toString();
                }
            }

            return new TimeFormatSyncResult(
                    "ok",
                    saved.getTimeFormat(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "User time format synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            User latest = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
            return buildTimeFormatConflictResult(latest, source, "User time format version conflict detected during replay.", attemptCount, queueActionId);
        }
    }

    private int normalizeExistingRoles() {
        List<User> users = userRepository.findAll();
        List<User> toUpdate = new ArrayList<>();
        for (User user : users) {
            String normalized = User.normalizeRole(user.getRole());
            if (!normalized.equals(user.getRole())) {
                user.setRole(normalized);
                toUpdate.add(user);
            }
        }
        if (toUpdate.isEmpty()) {
            return 0;
        }
        userRepository.saveAll(toUpdate);
        return toUpdate.size();
    }

    private SessionTimeoutSyncResult buildConflictResult(User user,
                                                         String source,
                                                         String message,
                                                         Integer attemptCount,
                                                         String queueActionId) {
        String auditId = null;
        var auditEvent = userAuditEventService.recordSessionTimeoutConflictEvent(
                user.getId(),
                user.getId(),
                user.getEmail(),
                user.getSessionTimeoutMinutes(),
                safeVersion(user),
                normalizeSource(source),
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (auditEvent != null && auditEvent.getId() != null) {
            auditId = auditEvent.getId().toString();
        }

        return new SessionTimeoutSyncResult(
                "conflict",
                user.getSessionTimeoutMinutes(),
                safeVersion(user),
                auditId,
                normalizeSource(source),
                message,
                SESSION_TIMEOUT_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private LocaleSyncResult buildLocaleConflictResult(User user,
                                                       String source,
                                                       String message,
                                                       Integer attemptCount,
                                                       String queueActionId) {
        String auditId = null;
        var auditEvent = userAuditEventService.recordLocaleConflictEvent(
                user.getId(),
                user.getId(),
                user.getEmail(),
                user.getLocale(),
                safeVersion(user),
                normalizeSource(source),
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (auditEvent != null && auditEvent.getId() != null) {
            auditId = auditEvent.getId().toString();
        }

        return new LocaleSyncResult(
                "conflict",
                user.getLocale(),
                safeVersion(user),
                auditId,
                normalizeSource(source),
                message,
                LOCALE_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private TimezoneSyncResult buildTimezoneConflictResult(User user,
                                                           String source,
                                                           String message,
                                                           Integer attemptCount,
                                                           String queueActionId) {
        String auditId = null;
        var auditEvent = userAuditEventService.recordTimezoneConflictEvent(
                user.getId(),
                user.getId(),
                user.getEmail(),
                user.getTimezone(),
                safeVersion(user),
                normalizeSource(source),
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (auditEvent != null && auditEvent.getId() != null) {
            auditId = auditEvent.getId().toString();
        }

        return new TimezoneSyncResult(
                "conflict",
                user.getTimezone(),
                safeVersion(user),
                auditId,
                normalizeSource(source),
                message,
                TIMEZONE_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private DateFormatSyncResult buildDateFormatConflictResult(User user,
                                                               String source,
                                                               String message,
                                                               Integer attemptCount,
                                                               String queueActionId) {
        String auditId = null;
        var auditEvent = userAuditEventService.recordDateFormatConflictEvent(
                user.getId(),
                user.getId(),
                user.getEmail(),
                user.getDateFormat(),
                safeVersion(user),
                normalizeSource(source),
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (auditEvent != null && auditEvent.getId() != null) {
            auditId = auditEvent.getId().toString();
        }

        return new DateFormatSyncResult(
                "conflict",
                user.getDateFormat(),
                safeVersion(user),
                auditId,
                normalizeSource(source),
                message,
                DATE_FORMAT_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private TimeFormatSyncResult buildTimeFormatConflictResult(User user,
                                                               String source,
                                                               String message,
                                                               Integer attemptCount,
                                                               String queueActionId) {
        String auditId = null;
        var auditEvent = userAuditEventService.recordTimeFormatConflictEvent(
                user.getId(),
                user.getId(),
                user.getEmail(),
                user.getTimeFormat(),
                safeVersion(user),
                normalizeSource(source),
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (auditEvent != null && auditEvent.getId() != null) {
            auditId = auditEvent.getId().toString();
        }

        return new TimeFormatSyncResult(
                "conflict",
                user.getTimeFormat(),
                safeVersion(user),
                auditId,
                normalizeSource(source),
                message,
                TIME_FORMAT_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private int safeVersion(User user) {
        if (user == null || user.getVersion() == null || user.getVersion() < 0) {
            return 0;
        }
        return user.getVersion();
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim() : "mobile-offline-queue";
    }

    private String resolveRequestedLocale(String requestedLocale) {
        String normalized = User.normalizeLocale(requestedLocale);
        String localeKey = normalized.replace('-', '_').toLowerCase(Locale.ROOT);
        if (!ALLOWED_LOCALES.contains(localeKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_locale");
        }
        return localeKey;
    }

    private String resolveRequestedTimezone(String requestedTimezone) {
        String normalized = User.normalizeTimezone(requestedTimezone);
        try {
            return ZoneId.of(normalized).getId();
        } catch (DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_timezone");
        }
    }

    private String resolveRequestedDateFormat(String requestedDateFormat) {
        String normalized = User.normalizeDateFormat(requestedDateFormat);
        if (!ALLOWED_DATE_FORMATS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_date_format");
        }
        return normalized;
    }

    private String resolveRequestedTimeFormat(String requestedTimeFormat) {
        String normalized = User.normalizeTimeFormat(requestedTimeFormat);
        if (!ALLOWED_TIME_FORMATS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_time_format");
        }
        return normalized;
    }

    public Page<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Pageable pageable) {
        return searchUsers(search, status, role, null, pageable);
    }

    public Page<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Specification<User> extraSpec,
                                  Pageable pageable) {
        Specification<User> spec = buildSpecification(search, status, role, extraSpec);

        AuthorizationContext authz = resolveCurrentAuthz();
        if (!authz.isAdmin()) {
            Set<Long> allowedCompanies = authz.getAllowedCompanyIds();
            Specification<User> companySpec = (root, query, cb) -> {
                if (allowedCompanies.isEmpty()) {
                    // company scope yoksa, yalnızca company_id IS NULL olan global kullanıcılar görünsün
                    return cb.isNull(root.get("companyId"));
                }
                // company scope varsa: allowedCompanyIds OR company_id IS NULL
                return cb.or(root.get("companyId").in(allowedCompanies), cb.isNull(root.get("companyId")));
            };
            spec = spec == null ? companySpec : spec.and(companySpec);
        }

        if (spec == null) {
            return userRepository.findAll(pageable);
        }

        return userRepository.findAll(spec, pageable);
    }

    public List<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Specification<User> extraSpec,
                                  Sort sort) {
        Specification<User> spec = buildSpecification(search, status, role, extraSpec);
        Sort safeSort = (sort == null || sort.isUnsorted())
                ? Sort.by(Sort.Direction.ASC, "id")
                : sort;

        AuthorizationContext authz = resolveCurrentAuthz();
        if (!authz.isAdmin()) {
            Set<Long> allowedCompanies = authz.getAllowedCompanyIds();
            Specification<User> companySpec = (root, query, cb) -> {
                if (allowedCompanies.isEmpty()) {
                    return cb.isNull(root.get("companyId"));
                }
                return cb.or(root.get("companyId").in(allowedCompanies), cb.isNull(root.get("companyId")));
            };
            spec = spec == null ? companySpec : spec.and(companySpec);
        }

        if (spec == null) {
            return userRepository.findAll(safeSort);
        }

        return userRepository.findAll(spec, safeSort);
    }

    // ── SSRM: Group, Aggregation, Pivot ───────────────────────────

    private static final Set<String> ALLOWED_GROUP_FIELDS = Set.of(
            "role", "enabled", "name", "email");
    private static final Set<String> ALLOWED_AGG_FUNCS = Set.of(
            "sum", "avg", "count", "min", "max");
    private static final Set<String> NUMERIC_FIELDS = Set.of(
            "sessionTimeoutMinutes", "id");

    /**
     * JPQL GROUP BY — returns [{groupValue, count, ...aggValues}] for SSRM grouping.
     */
    public List<Object[]> getDistinctGroupValues(String search, String status, String role,
                                                  Specification<User> extraSpec, String groupField,
                                                  Sort sort, String valueCols) {
        if (!ALLOWED_GROUP_FIELDS.contains(groupField)) {
            groupField = "role";
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<User> root = cq.from(User.class);

        // Build WHERE from spec
        Specification<User> fullSpec = buildSpecification(search, status, role, extraSpec);
        Predicate predicate = fullSpec != null
                ? fullSpec.toPredicate(root, cq, cb)
                : cb.conjunction();
        cq.where(predicate);

        // SELECT groupField, COUNT(*)
        Expression<?> groupExpr = root.get(groupField);
        java.util.List<jakarta.persistence.criteria.Selection<?>> selections = new java.util.ArrayList<>();
        selections.add(groupExpr);
        selections.add(cb.count(root));

        // Add aggregate expressions from valueCols (e.g. "sessionTimeoutMinutes:sum")
        java.util.List<String> aggLabels = new java.util.ArrayList<>();
        if (valueCols != null && !valueCols.isBlank()) {
            for (String vc : valueCols.split(",")) {
                String[] parts = vc.split(":");
                if (parts.length != 2) continue;
                String field = parts[0].trim();
                String func = parts[1].trim().toLowerCase();
                if (!NUMERIC_FIELDS.contains(field) || !ALLOWED_AGG_FUNCS.contains(func)) continue;
                Expression<Number> numExpr = root.get(field);
                switch (func) {
                    case "sum":   selections.add(cb.sum(numExpr)); break;
                    case "avg":   selections.add(cb.avg(numExpr)); break;
                    case "count": selections.add(cb.count(numExpr)); break;
                    case "min":   selections.add(cb.min(numExpr)); break;
                    case "max":   selections.add(cb.max(numExpr)); break;
                }
                aggLabels.add(field + "_" + func);
            }
        }

        cq.multiselect(selections.toArray(new jakarta.persistence.criteria.Selection[0]));
        cq.groupBy(groupExpr);
        cq.orderBy(cb.asc(groupExpr));

        return entityManager.createQuery(cq).getResultList();
    }

    /** Backward-compatible overload without valueCols. */
    public List<Object[]> getDistinctGroupValues(String search, String status, String role,
                                                  Specification<User> extraSpec, String groupField, Sort sort) {
        return getDistinctGroupValues(search, status, role, extraSpec, groupField, sort, null);
    }

    /**
     * Compute aggregate values for a filtered set (SSRM aggregation without grouping).
     * Returns: {"sessionTimeoutMinutes": 450, ...}
     */
    public java.util.Map<String, Object> computeAggregation(Specification<User> spec, String valueCols) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (valueCols == null || valueCols.isBlank()) return result;

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        for (String vc : valueCols.split(",")) {
            String[] parts = vc.split(":");
            if (parts.length != 2) continue;
            String field = parts[0].trim();
            String func = parts[1].trim().toLowerCase();
            if (!NUMERIC_FIELDS.contains(field) || !ALLOWED_AGG_FUNCS.contains(func)) continue;

            CriteriaQuery<Number> cq = cb.createQuery(Number.class);
            Root<User> root = cq.from(User.class);

            if (spec != null) {
                cq.where(spec.toPredicate(root, cq, cb));
            }

            Expression<Number> numExpr = root.get(field);
            switch (func) {
                case "sum":   cq.select(cb.sum(numExpr)); break;
                case "avg":   cq.select(cb.avg(numExpr)); break;
                case "count": cq.select(cb.count(numExpr)); break;
                case "min":   cq.select(cb.min(numExpr)); break;
                case "max":   cq.select(cb.max(numExpr)); break;
            }

            Number value = entityManager.createQuery(cq).getSingleResult();
            result.put(field, value);
        }
        return result;
    }

    /**
     * Compute pivot data: group by groupField, pivot by pivotField, aggregate valueCols.
     * Returns items as Maps + secondaryColumns list.
     */
    public java.util.Map<String, Object> computePivot(Specification<User> spec,
                                                        String groupField, String pivotField, String valueCols) {
        // 1. Get distinct pivot values
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> pvq = cb.createQuery(String.class);
        Root<User> pvRoot = pvq.from(User.class);
        pvq.select(pvRoot.get(pivotField)).distinct(true);
        if (spec != null) pvq.where(spec.toPredicate(pvRoot, pvq, cb));
        List<String> pivotValues = entityManager.createQuery(pvq).getResultList();

        // 2. For each group, compute aggregate per pivot value
        CriteriaQuery<Object[]> gq = cb.createQuery(Object[].class);
        Root<User> gRoot = gq.from(User.class);
        gq.select(cb.array(gRoot.get(groupField))).distinct(true);
        if (spec != null) gq.where(spec.toPredicate(gRoot, gq, cb));
        gq.groupBy(gRoot.get(groupField));
        List<Object[]> groupValues = entityManager.createQuery(gq).getResultList();

        // Parse valueCols
        String valueField = "sessionTimeoutMinutes";
        String aggFunc = "sum";
        if (valueCols != null && valueCols.contains(":")) {
            String[] parts = valueCols.split(",")[0].split(":");
            valueField = parts[0].trim();
            aggFunc = parts.length > 1 ? parts[1].trim().toLowerCase() : "sum";
        }

        List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        List<String> secondaryColumns = new java.util.ArrayList<>();

        for (String pv : pivotValues) {
            secondaryColumns.add(pv + "_" + valueField);
        }

        for (Object[] gv : groupValues) {
            String groupVal = String.valueOf(gv[0]);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put(groupField, groupVal);

            for (String pv : pivotValues) {
                // Query: SELECT aggFunc(valueField) FROM User WHERE groupField=groupVal AND pivotField=pv
                CriteriaQuery<Number> aq = cb.createQuery(Number.class);
                Root<User> aRoot = aq.from(User.class);
                Predicate where = cb.and(
                        cb.equal(aRoot.get(groupField), groupVal),
                        cb.equal(aRoot.get(pivotField), pv)
                );
                if (spec != null) where = cb.and(where, spec.toPredicate(aRoot, aq, cb));
                aq.where(where);

                Expression<Number> numExpr = aRoot.get(valueField);
                switch (aggFunc) {
                    case "sum":   aq.select(cb.sum(numExpr)); break;
                    case "avg":   aq.select(cb.avg(numExpr)); break;
                    case "count": aq.select(cb.count(numExpr)); break;
                    case "min":   aq.select(cb.min(numExpr)); break;
                    case "max":   aq.select(cb.max(numExpr)); break;
                    default:      aq.select(cb.sum(numExpr)); break;
                }

                Number val = entityManager.createQuery(aq).getSingleResult();
                row.put(pv + "_" + valueField, val != null ? val : 0);
            }
            items.add(row);
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("items", items);
        result.put("secondaryColumns", secondaryColumns);
        return result;
    }

    private Specification<User> buildSpecification(String search,
                                                   String status,
                                                   String role,
                                                   Specification<User> extraSpec) {
        Specification<User> spec = null;

        if (StringUtils.hasText(search)) {
            String like = "%" + search.trim().toLowerCase() + "%";
            Specification<User> searchSpec = (root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("name")), like)
            );
            spec = spec == null ? searchSpec : spec.and(searchSpec);
        }

        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status)) {
            if ("ACTIVE".equalsIgnoreCase(status)) {
                Specification<User> activeSpec = (root, query, cb) -> cb.isTrue(root.get("enabled"));
                spec = spec == null ? activeSpec : spec.and(activeSpec);
            } else if ("INACTIVE".equalsIgnoreCase(status)) {
                Specification<User> inactiveSpec = (root, query, cb) -> cb.isFalse(root.get("enabled"));
                spec = spec == null ? inactiveSpec : spec.and(inactiveSpec);
            }
        }

        if (StringUtils.hasText(role) && !"ALL".equalsIgnoreCase(role)) {
            String normalizedRole = role.trim().toUpperCase();
            Specification<User> roleSpec = (root, query, cb) -> cb.equal(cb.upper(root.get("role")), normalizedRole);
            spec = spec == null ? roleSpec : spec.and(roleSpec);
        }

        if (extraSpec != null) {
            spec = (spec == null) ? extraSpec : spec.and(extraSpec);
        }
        return spec;
    }

    private AuthorizationContext resolveCurrentAuthz() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return AuthorizationContext.of(null, null, Set.of(), Set.of());
        }
        List<GrantedAuthority> authorities = authentication.getAuthorities() == null
                ? List.of()
                : new ArrayList<>(authentication.getAuthorities());
        return authorizationContextService.buildContext(
                authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt ? jwt : null,
                authorities
        );
    }

    /**
     * UserDetailsService arayüzünden gelen ve Spring Security tarafından kullanılan metot.
     * Email (username) ile kullanıcıyı veritabanından yükler.
     * @param email Kullanıcı adı olarak kullanılan email.
     * @return UserDetails arayüzünü uygulayan bir User nesnesi.
     * @throws UsernameNotFoundException Kullanıcı bulunamazsa fırlatılır.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Kullanıcıyı kendi veritabanından bul ve döndür.
        // User modelimiz zaten UserDetails'i uyguladığı için doğrudan döndürebiliriz.
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Kullanıcı bulunamadı: " + email));
    }
}
