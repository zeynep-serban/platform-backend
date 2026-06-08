package com.example.commonauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class AuthenticatedUserLookupService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserLookupService.class);
    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final Pattern COLUMN_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final String userTable;
    private final String softDeleteColumn;

    public AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable) {
        this(jdbcTemplate, userTable, null);
    }

    /**
     * @param softDeleteColumn optional soft-delete tombstone column. When
     *        non-blank, the email→userId lookup appends {@code AND <col> IS
     *        NULL} so a soft-deleted identity never resolves a numeric
     *        userId in the OpenFGA authz context (Codex 019ea573, #770
     *        Phase 2). Pass {@code null}/blank (or use the 2-arg constructor)
     *        for tables without a soft-delete column — the filter is then
     *        omitted and behaviour is unchanged (backward compatible). The
     *        value is validated as a bare SQL identifier (no injection).
     */
    public AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable, String softDeleteColumn) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTable = normalizeTableName(userTable);
        this.softDeleteColumn = normalizeSoftDeleteColumn(softDeleteColumn);
    }

    public ResolvedAuthenticatedUser resolve(Jwt jwt) {
        if (jwt == null) {
            return new ResolvedAuthenticatedUser(null, null, null);
        }

        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));

        Long numericUserId = firstNonNull(
                extractLongClaim(jwt, "userId"),
                extractLongClaim(jwt, "uid"),
                tryParseLong(subject)
        );

        // Soft-delete (Codex 019ea573, #770 Phase 2): when a soft-delete column
        // is configured, a numeric id taken from a CLAIM (userId / uid /
        // numeric sub) is NOT yet DB-verified — a still-valid JWT issued before
        // (or after) the soft-delete would otherwise resolve a tombstoned
        // user:<id> into the authz context. Validate it against the table; if
        // it is a tombstone (or no longer present), suppress it so neither the
        // numericUserId nor the responseUserId echoes a deleted identity. The
        // email path below already filters tombstones via lookupUserIdByEmail.
        boolean suppressedDeletedNumeric = false;
        if (numericUserId == null && email != null) {
            numericUserId = lookupUserIdByEmail(email);
        } else if (numericUserId != null && softDeleteColumn != null && !isActiveById(numericUserId)) {
            numericUserId = null;
            suppressedDeletedNumeric = true;
        }

        String responseUserId = numericUserId != null
                ? Long.toString(numericUserId)
                : (suppressedDeletedNumeric
                        ? null
                        : firstNonBlank(stringClaim(jwt, "userId"), stringClaim(jwt, "uid"), subject));

        return new ResolvedAuthenticatedUser(numericUserId, responseUserId, email);
    }

    private Long lookupUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        if (!hasQueryableLocalUserTable()) {
            log.debug("Authenticated user lookup local tablo mevcut değil; SQL lookup atlanacak. table={}", userTable);
            return null;
        }
        String softDeleteFilter = softDeleteColumn == null ? "" : " and " + softDeleteColumn + " is null";
        String sql = "select id from " + userTable + " where lower(email) = ?" + softDeleteFilter + " limit 1";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, email.toLowerCase(Locale.ROOT));
        } catch (DataAccessException ex) {
            log.warn("Authenticated user lookup SQL başarısız oldu. cause={}", ex.getMessage());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        Object idValue = rows.get(0).get("id");
        return idValue instanceof Number number ? number.longValue() : null;
    }

    /**
     * Confirms the given numeric id is an <em>active</em> (non-tombstoned)
     * row, used to validate a numeric claim when a soft-delete column is
     * configured (Codex 019ea573, #770 Phase 2). Fail-open: when the column
     * is unset or the table cannot be queried it returns {@code true} (no
     * suppression) — the user-service {@code CurrentUserResolver} choke point
     * remains the authoritative gate, this is defense-in-depth for the authz
     * context.
     */
    private boolean isActiveById(long userId) {
        if (softDeleteColumn == null || !hasQueryableLocalUserTable()) {
            return true;
        }
        String sql = "select id from " + userTable + " where id = ? and " + softDeleteColumn + " is null limit 1";
        try {
            return !jdbcTemplate.queryForList(sql, userId).isEmpty();
        } catch (DataAccessException ex) {
            log.warn("Authenticated user active-by-id check failed. id={} cause={}", userId, ex.getMessage());
            return true;
        }
    }

    private boolean hasQueryableLocalUserTable() {
        try {
            String relationName = jdbcTemplate.queryForObject(
                    "select to_regclass(?)::text",
                    String.class,
                    userTable
            );
            return StringUtils.hasText(relationName);
        } catch (DataAccessException ex) {
            log.warn("Authenticated user lookup tablo kontrolü başarısız oldu. table={} cause={}",
                    userTable, ex.getMessage());
            return false;
        }
    }

    private static Long extractLongClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return tryParseLong(text);
        }
        return null;
    }

    private static String stringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private static Long tryParseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeTableName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!QUALIFIED_TABLE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid authz user table reference: " + raw);
        }
        return value;
    }

    private static String normalizeSoftDeleteColumn(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!COLUMN_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid soft-delete column reference: " + raw);
        }
        return value;
    }

    public record ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email) {
    }
}
