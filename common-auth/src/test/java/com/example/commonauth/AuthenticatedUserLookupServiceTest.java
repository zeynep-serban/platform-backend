package com.example.commonauth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthenticatedUserLookupServiceTest {

    @Test
    void resolve_prefersNumericUidClaim() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), null),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "uid", 42L,
                "email", "admin@example.com"
        ), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_fallsBackToEmailLookupWhenSubjectIsNotNumeric() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(Map.of("id", 7L)), "user_service.users"),
                "user_service.users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertEquals(7L, resolved.numericUserId());
        assertEquals("7", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_returnsSubjectWhenLookupCannotResolveNumericUserId() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(new StubJdbcTemplate(List.of(), null), "users");
        Jwt jwt = buildJwt(Map.of(
                "preferred_username", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_skipsSqlLookupWhenConfiguredTableDoesNotExist() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), null),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_returnsNullWhenTableProbeFails() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new FailingProbeJdbcTemplate(),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_softDeleteColumn_suppressesTombstonedNumericClaim() {
        // Finding 1 (Codex thread 019ea6f6 REVISE): with a soft-delete column
        // configured, a numeric uid/userId/sub claim pointing at a tombstone
        // must NOT resolve — neither numericUserId nor responseUserId may echo
        // the deleted id. Active-by-id query returns empty = tombstone.
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), "user_service.users"),
                "user_service.users",
                "deleted_at"
        );
        Jwt jwt = buildJwt(Map.of("uid", 42L, "email", "ghost@example.com"), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertNull(resolved.responseUserId());
        assertEquals("ghost@example.com", resolved.email());
    }

    @Test
    void resolve_softDeleteColumn_keepsActiveNumericClaim() {
        // An active numeric id is preserved when the soft-delete column is set.
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(Map.of("id", 42L)), "user_service.users"),
                "user_service.users",
                "deleted_at"
        );
        Jwt jwt = buildJwt(Map.of("uid", 42L, "email", "live@example.com"), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
    }

    private static Jwt buildJwt(Map<String, Object> claims, String subject) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(subject);
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> rows;
        private final String relationName;

        private StubJdbcTemplate(List<Map<String, Object>> rows, String relationName) {
            this.rows = rows;
            this.relationName = relationName;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(relationName);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return rows;
        }
    }

    private static final class FailingProbeJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            throw new DataAccessResourceFailureException("probe failed");
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            throw new AssertionError("queryForList should not be called when table probe fails");
        }
    }
}
