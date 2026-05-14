package com.example.report.config;

import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.query.TenantBoundaryGuard;
import com.example.report.registry.ReportRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2 Program 2 — Manual {@code @Bean} registration for
 * {@link TenantBoundaryGuard} (Adım 5).
 *
 * <p>The guard intentionally has <b>no</b> {@code @Component} annotation
 * (Codex iter-15 absorb): a {@code HandlerInterceptor} bean discovered via
 * component-scan is auto-pulled into {@code @WebMvcTest} slices, which then
 * try to wire its {@link PermissionResolver} dependency and fail. Registering
 * the guard from a dedicated {@code @Configuration} instead means slice tests
 * skip it (slices do not scan {@code @Configuration} classes), while the
 * production / full-application context picks it up via {@code @ComponentScan}
 * and the constructor injection fails fast if {@code PermissionResolver},
 * {@link CompanyHeaderScopeNarrower}, or {@link ReportRegistry} is missing.
 *
 * <p>Kept in its own {@code @Configuration} (not on
 * {@link ReportServiceWebMvcConfig}) to avoid a {@code BeanCurrentlyInCreation}
 * cycle: the WebMvc config constructor accepts the guard as an optional
 * dependency, and Spring cannot resolve a {@code @Bean} method defined on the
 * same class while that class is still being constructed.
 */
@Configuration
public class TenantBoundaryGuardBeanConfig {

    @Bean
    public TenantBoundaryGuard tenantBoundaryGuard(PermissionResolver permissionResolver,
                                                   CompanyHeaderScopeNarrower narrower,
                                                   ReportRegistry reportRegistry) {
        return new TenantBoundaryGuard(permissionResolver, narrower, reportRegistry);
    }
}
