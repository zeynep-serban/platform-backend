package com.example.user.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@Entity
@Table(name = "users")
@FilterDef(name = "companyScope",
        parameters = @ParamDef(name = "companyIds", type = Long.class))
@Filter(name = "companyScope",
        condition = "company_id IS NULL OR company_id IN (:companyIds)")
public class User implements UserDetails {

    public static final int DEFAULT_SESSION_TIMEOUT_MINUTES = 15;
    public static final String DEFAULT_LOCALE = "tr";
    public static final String DEFAULT_TIMEZONE = "Europe/Istanbul";
    public static final String DEFAULT_DATE_FORMAT = "dd.MM.yyyy";
    public static final String DEFAULT_TIME_FORMAT = "HH:mm";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Column(name = "name", nullable = false)
    private String name;

    @Email(message = "Email must be valid")
    @NotBlank(message = "Email is required")
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @NotBlank(message = "Password is required")
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "role", nullable = false)
    private String role = "USER";

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "create_date", updatable = false)
    private LocalDateTime createDate = LocalDateTime.now();

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "session_timeout_minutes", columnDefinition = "integer default " + DEFAULT_SESSION_TIMEOUT_MINUTES + " not null")
    private Integer sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale = DEFAULT_LOCALE;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "date_format", nullable = false, length = 32)
    private String dateFormat = DEFAULT_DATE_FORMAT;

    @Column(name = "time_format", nullable = false, length = 16)
    private String timeFormat = DEFAULT_TIME_FORMAT;

    /**
     * Keycloak user UUID (subject) mapped to this platform user. Used by
     * {@code auth-service} ImpersonationController to resolve target subject
     * server-side so the admin UI never has to ask operators for the KC UUID.
     * Nullable while existing rows are backfilled; new user provisioning
     * must populate this on KC create. Codex thread {@code 019e1bed} AGREE.
     */
    @Column(name = "kc_subject", length = 64, unique = true)
    private String kcSubject;

    /**
     * Soft-delete tombstone (Codex thread {@code 019ea573}, platform-web #770
     * Phase 2 — user DELETE action). {@code null} = active; non-null = the
     * instant the row was soft-deleted. There is deliberately NO global
     * Hibernate {@code @Where}/{@code @SQLRestriction}: identity-resolution
     * security paths must read tombstones explicitly to emit a clean
     * {@code 403 USER_DELETED} and refuse resurrection. Public/query surfaces
     * exclude tombstones via {@code UserSpecifications.notDeleted()} + the
     * {@code findActive*} repository methods.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    // --- Constructorlar ---
    public User() {}

    // Gerekirse diğer constructor'larınız burada kalabilir

    // --- Getter & Setter Metotları (Mevcut olanlar) ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = normalizeRole(role); }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreateDate() { return createDate; }
    public void setCreateDate(LocalDateTime createDate) { this.createDate = createDate; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
    public Integer getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) { this.sessionTimeoutMinutes = sessionTimeoutMinutes; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = normalizeLocale(locale); }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = normalizeTimezone(timezone); }
    public String getDateFormat() { return dateFormat; }
    public void setDateFormat(String dateFormat) { this.dateFormat = normalizeDateFormat(dateFormat); }
    public String getTimeFormat() { return timeFormat; }
    public void setTimeFormat(String timeFormat) { this.timeFormat = normalizeTimeFormat(timeFormat); }
    public String getKcSubject() { return kcSubject; }
    public void setKcSubject(String kcSubject) { this.kcSubject = kcSubject; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    /** True when this row is a soft-delete tombstone (Codex 019ea573). */
    public boolean isDeleted() { return deletedAt != null; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    // --- UserDetails Arayüzünden Gelen Zorunlu Metotlar ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Kullanıcının rolünü Spring Security'nin anladığı formata çevirir.
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + this.role));
    }

    @Override
    public String getPassword() {
        // UserDetails'in istediği şifreyi döndürür.
        return this.password;
    }

    @Override
    public String getUsername() {
        // UserDetails'in istediği kullanıcı adını döndürür. Biz email kullanıyoruz.
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        // Hesabın süresinin dolup dolmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // Hesabın kilitli olup olmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // Parolanın süresinin dolup dolmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Hesabın aktif olup olmadığını veritabanındaki 'enabled' alanından alır.
        return this.enabled;
    }

    @PrePersist
    @PreUpdate
    private void ensureSessionTimeout() {
        if (this.sessionTimeoutMinutes == null || this.sessionTimeoutMinutes < 1) {
            this.sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;
        }
        this.role = normalizeRole(this.role);
        this.locale = normalizeLocale(this.locale);
        this.timezone = normalizeTimezone(this.timezone);
        this.dateFormat = normalizeDateFormat(this.dateFormat);
        this.timeFormat = normalizeTimeFormat(this.timeFormat);
    }

    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        String trimmed = role.trim().toUpperCase();
        if (trimmed.startsWith("ROLE_")) {
            trimmed = trimmed.substring(5);
        }
        return trimmed.isBlank() ? "USER" : trimmed;
    }

    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String trimmed = locale.trim().toLowerCase();
        return trimmed.isBlank() ? DEFAULT_LOCALE : trimmed;
    }

    public static String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        String trimmed = timezone.trim();
        return trimmed.isBlank() ? DEFAULT_TIMEZONE : trimmed;
    }

    public static String normalizeDateFormat(String dateFormat) {
        if (dateFormat == null || dateFormat.isBlank()) {
            return DEFAULT_DATE_FORMAT;
        }
        String trimmed = dateFormat.trim();
        return trimmed.isBlank() ? DEFAULT_DATE_FORMAT : trimmed;
    }

    public static String normalizeTimeFormat(String timeFormat) {
        if (timeFormat == null || timeFormat.isBlank()) {
            return DEFAULT_TIME_FORMAT;
        }
        String trimmed = timeFormat.trim();
        return trimmed.isBlank() ? DEFAULT_TIME_FORMAT : trimmed;
    }
}
