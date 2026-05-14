package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.authz.ScopeSummaryDto;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Phase 2 Program 2 — TenantBoundaryGuard unit tests (Adım 5).
 *
 * <p>Codex iter-12 DoD 9 spec test + iter-14 PARTIAL revision 2 additional
 * tests (non-tenant bypass + permission resolver failure).
 */
class TenantBoundaryGuardTest {

    private PermissionResolver permissionResolver;
    private CompanyHeaderScopeNarrower narrower;
    private ReportRegistry reportRegistry;
    private TenantBoundaryGuard guard;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        permissionResolver = mock(PermissionResolver.class);
        narrower = new CompanyHeaderScopeNarrower();
        reportRegistry = mock(ReportRegistry.class);
        guard = new TenantBoundaryGuard(permissionResolver, narrower, reportRegistry);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Helpers ---------------------------------------------------------

    private void seedReportKey(String key) {
        Map<String, String> vars = new HashMap<>();
        vars.put("key", key);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
    }

    private void seedReport(String key, String schemaMode) {
        ReportDefinition def = mock(ReportDefinition.class);
        when(def.schemaMode()).thenReturn(schemaMode);
        when(reportRegistry.get(key)).thenReturn(java.util.Optional.of(def));
    }

    private void seedJwt() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private AuthzMeResponse authz(boolean superAdmin, List<ScopeSummaryDto> scopes) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("u1");
        authz.setSuperAdmin(superAdmin);
        authz.setAllowedScopes(scopes);
        return authz;
    }

    private ScopeSummaryDto company(String id) {
        return new ScopeSummaryDto("COMPANY", id);
    }

    // --- Spec tests (9, Codex iter-12) -----------------------------------

    @Test
    void singleCompanyScope_noHeader_passesAndSetsNarrowedAttribute() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"))));

        boolean result = guard.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        Object attr = request.getAttribute(TenantBoundaryGuard.NARROWED_AUTHZ_ATTRIBUTE);
        assertThat(attr).isInstanceOf(AuthzMeResponse.class);
        AuthzMeResponse narrowed = (AuthzMeResponse) attr;
        assertThat(narrowed.getScopeRefIds("COMPANY")).containsExactly("1");
    }

    @Test
    void multiCompanyScope_noHeader_throws400TenantSelectionRequired() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"), company("5"))));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("X-Company-Id")
                .hasMessageContaining("satis-ozet");
    }

    @Test
    void multiCompanyScope_validHeader_narrowsAndPasses() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        request.addHeader("X-Company-Id", "5");
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"), company("5"))));

        boolean result = guard.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        AuthzMeResponse narrowed = (AuthzMeResponse)
                request.getAttribute(TenantBoundaryGuard.NARROWED_AUTHZ_ATTRIBUTE);
        assertThat(narrowed.getScopeRefIds("COMPANY")).containsExactly("5");
    }

    @Test
    void superAdmin_noHeader_throws400TenantSelectionRequired() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(true, List.of()));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("super-admin");
    }

    @Test
    void superAdmin_validHeader_resolves() {
        seedReportKey("stok-durum");
        seedReport("stok-durum", "current");
        seedJwt();
        request.addHeader("X-Company-Id", "42");
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(true, List.of()));

        boolean result = guard.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        AuthzMeResponse narrowed = (AuthzMeResponse)
                request.getAttribute(TenantBoundaryGuard.NARROWED_AUTHZ_ATTRIBUTE);
        assertThat(narrowed.getScopeRefIds("COMPANY")).containsExactly("42");
        assertThat(narrowed.isSuperAdmin()).isTrue();
    }

    @Test
    void nonNumericHeader_throws400ResponseStatus() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        request.addHeader("X-Company-Id", "abc");
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"))));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(t -> ((ResponseStatusException) t).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void outOfScopeCompany_scopedUser_throws403ResponseStatus() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        request.addHeader("X-Company-Id", "99");
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"), company("5"))));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(t -> ((ResponseStatusException) t).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void scopedUser_emptyCompanyScope_throwsTenantSelectionRequired() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of()));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("COMPANY scope");
    }

    @Test
    void missingJwt_bypassesGuardReturnsTrue() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        // no JWT seeded — Spring Security upstream would 401

        boolean result = guard.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(request.getAttribute(TenantBoundaryGuard.NARROWED_AUTHZ_ATTRIBUTE)).isNull();
    }

    // --- iter-14 additional tests (PARTIAL revision) ---------------------

    @Test
    void nonTenantReport_static_bypassesGuardWithoutEnforcement() {
        seedReportKey("master-data-list");
        seedReport("master-data-list", "static");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authz(false, List.of(company("1"), company("5"))));

        boolean result = guard.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(request.getAttribute(TenantBoundaryGuard.NARROWED_AUTHZ_ATTRIBUTE)).isNull();
    }

    @Test
    void permissionResolverThrows_guardDoesNotSwallow() {
        seedReportKey("satis-ozet");
        seedReport("satis-ozet", "yearly");
        seedJwt();
        when(permissionResolver.getAuthzMe(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("openfga unavailable"));

        assertThatThrownBy(() -> guard.preHandle(request, response, new Object()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("openfga unavailable");
    }
}
