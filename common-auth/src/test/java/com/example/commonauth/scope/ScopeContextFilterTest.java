package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ScopeContextFilter covering all branches:
 * - Dev mode (OpenFGA disabled)
 * - Production mode (OpenFGA enabled)
 * - Cache integration (hit/miss/error)
 * - Context lifecycle (cleanup, exception safety, anonymous user)
 *
 * Pattern: capture ScopeContext via AtomicReference in doAnswer,
 * then assert outside the lambda to avoid static-import scope issues.
 */
@ExtendWith(MockitoExtension.class)
class ScopeContextFilterTest {

    @Mock OpenFgaAuthzService authzService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain filterChain;

    private final AtomicReference<ScopeContext> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        captured.set(null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ScopeContextHolder.clear();
    }

    private void captureContextDuringFilter() throws Exception {
        doAnswer(inv -> {
            captured.set(ScopeContextHolder.get());
            return null;
        }).when(filterChain).doFilter(request, response);
    }

    private OpenFgaProperties disabledProps() {
        var props = new OpenFgaProperties();
        props.setEnabled(false);
        return props;
    }

    private OpenFgaProperties enabledProps() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setStoreId("store-1");
        props.setModelId("model-1");
        return props;
    }

    private void setJwtAuth(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .build();
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private void setupOpenFgaMocks(String userId, Set<Long> companies, boolean superAdmin) {
        // PR-BE-9 Phase 2 (Codex thread 019e0891 iter-2 AGREE absorb,
        // 2026-05-08): branch reads use 'viewer' relation per Faz 21.3
        // ADR-0008 explicit-scope contract (canonical
        // backend/openfga/model.fga). Pre-fix expected 'member' here, but
        // ScopeContextFilter#fetchScopeFromOpenFga now reads
        // listObjectIds(userId, "viewer", "branch") to match what
        // TupleSyncService#syncScopeTuples writes.
        when(authzService.listObjectIds(eq(userId), eq("viewer"), eq("company"))).thenReturn(companies);
        when(authzService.listObjectIds(eq(userId), eq("viewer"), eq("project"))).thenReturn(Set.of(10L));
        when(authzService.listObjectIds(eq(userId), eq("viewer"), eq("warehouse"))).thenReturn(Set.of(20L));
        when(authzService.listObjectIds(eq(userId), eq("viewer"), eq("branch"))).thenReturn(Set.of(30L));
        when(authzService.check(eq(userId), eq("admin"), eq("organization"), eq("default"))).thenReturn(superAdmin);
    }

    @Nested
    @DisplayName("Dev mode (openfga.enabled=false)")
    class DevMode {

        @Test
        @DisplayName("returns dev scope from YAML config")
        void devScopeFromConfig() throws Exception {
            var props = disabledProps();
            props.getDevScope().setCompanyIds(Set.of(1L, 2L));
            props.getDevScope().setProjectIds(Set.of(5L));
            props.getDevScope().setSuperAdmin(false);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, props)
                    .doFilterInternal(request, response, filterChain);

            assertEquals("dev-user", captured.get().userId());
            assertEquals(Set.of(1L, 2L), captured.get().allowedCompanyIds());
            assertTrue(captured.get().allowedProjectIds().contains(5L));
            assertFalse(captured.get().superAdmin());
            verifyNoInteractions(authzService);
        }

        @Test
        @DisplayName("dev superAdmin mode")
        void devSuperAdmin() throws Exception {
            var props = disabledProps();
            props.getDevScope().setSuperAdmin(true);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, props)
                    .doFilterInternal(request, response, filterChain);

            assertTrue(captured.get().superAdmin());
        }
    }

    @Nested
    @DisplayName("Production mode (openfga.enabled=true)")
    class ProductionMode {

        @Test
        @DisplayName("no authentication → empty scope")
        void noAuth_emptyScope() throws Exception {
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, enabledProps())
                    .doFilterInternal(request, response, filterChain);

            assertNull(captured.get().userId());
            assertTrue(captured.get().allowedCompanyIds().isEmpty());
            assertFalse(captured.get().superAdmin());
        }

        @Test
        @DisplayName("JWT user → parallel OpenFGA calls → ScopeContext populated")
        void jwtUser_scopeFromOpenFga() throws Exception {
            setJwtAuth("user-42");
            setupOpenFgaMocks("user-42", Set.of(1L, 2L, 3L), false);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, enabledProps())
                    .doFilterInternal(request, response, filterChain);

            assertEquals("user-42", captured.get().userId());
            assertEquals(Set.of(1L, 2L, 3L), captured.get().allowedCompanyIds());
            assertTrue(captured.get().allowedProjectIds().contains(10L));
            assertTrue(captured.get().allowedWarehouseIds().contains(20L));
            assertTrue(captured.get().allowedBranchIds().contains(30L));
            assertFalse(captured.get().superAdmin());
            verify(authzService).listObjectIds("user-42", "viewer", "company");
            verify(authzService).check("user-42", "admin", "organization", "default");
        }

        @Test
        @DisplayName("superAdmin user → superAdmin scope")
        void superAdmin_bypassScope() throws Exception {
            setJwtAuth("admin-1");
            setupOpenFgaMocks("admin-1", Set.of(), true);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, enabledProps())
                    .doFilterInternal(request, response, filterChain);

            assertEquals("admin-1", captured.get().userId());
            assertTrue(captured.get().superAdmin());
        }

        @Test
        @DisplayName("OpenFGA failure → falls back to dev scope")
        void openFgaFailure_fallback() throws Exception {
            setJwtAuth("user-err");
            when(authzService.listObjectIds(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("OpenFGA down"));
            when(authzService.check(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("OpenFGA down"));
            captureContextDuringFilter();

            var props = enabledProps();
            props.getDevScope().setCompanyIds(Set.of(99L));
            new ScopeContextFilter(authzService, props)
                    .doFilterInternal(request, response, filterChain);

            assertEquals("dev-user", captured.get().userId());
            assertTrue(captured.get().allowedCompanyIds().contains(99L));
        }
    }

    @Nested
    @DisplayName("Cache integration")
    class CacheIntegration {

        @Test
        @DisplayName("cache hit skips OpenFGA calls")
        void cacheHit_skipsOpenFga() throws Exception {
            setJwtAuth("cached-user");
            var props = enabledProps();
            var cache = new ScopeContextCache(Duration.ofSeconds(60), Duration.ZERO, 100, true);
            var vp = mock(AuthzVersionProvider.class);
            when(vp.getCurrentVersion()).thenReturn(5L);

            String key = ScopeContextCache.cacheKey("cached-user", 5L, "store-1", "model-1");
            cache.put(key, new ScopeContext("cached-user", Set.of(77L), Set.of(), Set.of(), Set.of(), false));
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, props, null, cache, vp)
                    .doFilterInternal(request, response, filterChain);

            assertEquals("cached-user", captured.get().userId());
            assertTrue(captured.get().allowedCompanyIds().contains(77L));
            verifyNoInteractions(authzService);
        }

        @Test
        @DisplayName("cache miss fetches from OpenFGA and populates cache")
        void cacheMiss_fetchesAndCaches() throws Exception {
            setJwtAuth("miss-user");
            setupOpenFgaMocks("miss-user", Set.of(5L), false);
            var props = enabledProps();
            var cache = new ScopeContextCache(Duration.ofSeconds(60), Duration.ZERO, 100, true);
            var vp = mock(AuthzVersionProvider.class);
            when(vp.getCurrentVersion()).thenReturn(1L);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, props, null, cache, vp)
                    .doFilterInternal(request, response, filterChain);

            assertTrue(captured.get().allowedCompanyIds().contains(5L));
            String key = ScopeContextCache.cacheKey("miss-user", 1L, "store-1", "model-1");
            assertNotNull(cache.get(key));
        }

        @Test
        @DisplayName("cache with OpenFGA error falls back to dev scope")
        void cacheError_fallback() throws Exception {
            setJwtAuth("err-user");
            when(authzService.listObjectIds(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("timeout"));
            when(authzService.check(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("timeout"));
            var props = enabledProps();
            props.getDevScope().setCompanyIds(Set.of(88L));
            var cache = new ScopeContextCache(Duration.ofSeconds(60), Duration.ZERO, 100, true);
            var vp = mock(AuthzVersionProvider.class);
            when(vp.getCurrentVersion()).thenReturn(1L);
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, props, null, cache, vp)
                    .doFilterInternal(request, response, filterChain);

            assertEquals("dev-user", captured.get().userId());
        }
    }

    @Nested
    @DisplayName("Context lifecycle")
    class ContextLifecycle {

        @Test
        @DisplayName("ScopeContextHolder is cleared after filter completes")
        void contextClearedAfterFilter() throws Exception {
            new ScopeContextFilter(authzService, disabledProps())
                    .doFilterInternal(request, response, filterChain);

            assertNull(ScopeContextHolder.get());
        }

        @Test
        @DisplayName("ScopeContextHolder is cleared even on exception")
        void contextClearedOnException() throws Exception {
            doThrow(new RuntimeException("boom")).when(filterChain).doFilter(request, response);
            try {
                new ScopeContextFilter(authzService, disabledProps())
                        .doFilterInternal(request, response, filterChain);
            } catch (RuntimeException ignored) {}

            assertNull(ScopeContextHolder.get());
        }

        @Test
        @DisplayName("anonymousUser principal → no userId")
        void anonymousUser_noUserId() throws Exception {
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getPrincipal()).thenReturn("anonymousUser");
            SecurityContextHolder.setContext(new SecurityContextImpl(auth));
            captureContextDuringFilter();

            new ScopeContextFilter(authzService, enabledProps())
                    .doFilterInternal(request, response, filterChain);

            assertNull(captured.get().userId());
            assertTrue(captured.get().allowedCompanyIds().isEmpty());
        }
    }
}
