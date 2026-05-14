package com.example.report.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.report.query.TenantBoundaryGuard;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * Phase 2 Program 2 — Path matrix proof for {@link ReportServiceWebMvcConfig}
 * (Adım 5).
 *
 * <p>Codex iter-14 PARTIAL revision "WebMvc path matrix proof": prove that
 * the guard fires for {@code data/query/export} and that catalog / metadata
 * / schema-context paths are not in the interceptor's path pattern list.
 */
class ReportServiceWebMvcConfigTest {

    @Test
    void addInterceptors_registersGuardOnExecutionEndpointsOnly() throws Exception {
        TenantBoundaryGuard guard = mock(TenantBoundaryGuard.class);
        ReportServiceWebMvcConfig config = new ReportServiceWebMvcConfig(guard);

        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);

        List<InterceptorRegistration> regs = registrations(registry);
        assertThat(regs).hasSize(1);

        List<String> patterns = includePatterns(regs.get(0));
        assertThat(patterns).containsExactlyInAnyOrder(
                "/api/v1/reports/*/data",
                "/api/v1/reports/*/query",
                "/api/v1/reports/*/export"
        );
    }

    @Test
    void mappedInterceptor_matches_executionPaths() {
        String[] patterns = {
                "/api/v1/reports/*/data",
                "/api/v1/reports/*/query",
                "/api/v1/reports/*/export"
        };
        MappedInterceptor mapped = new MappedInterceptor(patterns, mock(TenantBoundaryGuard.class));

        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/data"))).isTrue();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/query"))).isTrue();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/export"))).isTrue();
    }

    @Test
    void mappedInterceptor_doesNotMatch_catalogAndMetadataAndSchemaContextAndWorkcube() {
        String[] patterns = {
                "/api/v1/reports/*/data",
                "/api/v1/reports/*/query",
                "/api/v1/reports/*/export"
        };
        MappedInterceptor mapped = new MappedInterceptor(patterns, mock(TenantBoundaryGuard.class));

        assertThat(mapped.matches(req("/api/v1/reports"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/metadata"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/history"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/categories"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/companies"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/schema-context"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/alerts"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/reports/satis-ozet/schedules"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/workcube/views"))).isFalse();
        assertThat(mapped.matches(req("/api/v1/workcube/views/foo"))).isFalse();
    }

    private MockHttpServletRequest req(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(r);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<InterceptorRegistration> registrations(InterceptorRegistry registry) throws Exception {
        Field f = InterceptorRegistry.class.getDeclaredField("registrations");
        f.setAccessible(true);
        return (List<InterceptorRegistration>) f.get(registry);
    }

    @SuppressWarnings("unchecked")
    private List<String> includePatterns(InterceptorRegistration reg) throws Exception {
        Field f = InterceptorRegistration.class.getDeclaredField("includePatterns");
        f.setAccessible(true);
        return (List<String>) f.get(reg);
    }
}
