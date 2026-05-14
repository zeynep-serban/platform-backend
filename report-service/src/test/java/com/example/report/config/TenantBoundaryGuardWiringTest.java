package com.example.report.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.query.TenantBoundaryGuard;
import com.example.report.registry.ReportRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Phase 2 Program 2 — Production-like wiring tests for
 * {@link TenantBoundaryGuard} + {@link ReportServiceWebMvcConfig} (Adım 5).
 *
 * <p>Codex iter-15 REVISE-1 absorb: verifies that the guard registers when
 * its dependencies are present (production) and silently no-ops when absent
 * (slice tests like {@code @WebMvcTest(ContextHealthController.class)}).
 *
 * <p>{@code @ConditionalOnBean(PermissionResolver.class)} on the guard was
 * removed (iter-15 blocker): it could silently disable the security layer in
 * production if the bean ordering raced. Instead, {@link
 * ReportServiceWebMvcConfig} accepts the guard as an optional dependency
 * ({@code @Autowired(required = false)}) — when present, registered; when
 * absent (slice tests), config is a no-op. Production fail-fast remains
 * intact because the guard's constructor still requires {@link
 * PermissionResolver} and friends.
 */
class TenantBoundaryGuardWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void guard_andConfig_registerWhenAllDependenciesPresent() {
        runner
                .withUserConfiguration(GuardWithDepsConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantBoundaryGuard.class);
                    assertThat(context).hasSingleBean(ReportServiceWebMvcConfig.class);

                    ReportServiceWebMvcConfig config = context.getBean(ReportServiceWebMvcConfig.class);
                    InterceptorRegistry registry = new InterceptorRegistry();
                    config.addInterceptors(registry);

                    List<InterceptorRegistration> regs = registrations(registry);
                    assertThat(regs).hasSize(1);
                });
    }

    @Test
    void config_noOps_whenGuardBeanAbsent() {
        runner
                .withUserConfiguration(ConfigOnlyNoGuard.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TenantBoundaryGuard.class);
                    assertThat(context).hasSingleBean(ReportServiceWebMvcConfig.class);

                    ReportServiceWebMvcConfig config = context.getBean(ReportServiceWebMvcConfig.class);
                    InterceptorRegistry registry = new InterceptorRegistry();
                    config.addInterceptors(registry);

                    List<InterceptorRegistration> regs = registrations(registry);
                    assertThat(regs).isEmpty();
                });
    }

    @SuppressWarnings("unchecked")
    private List<InterceptorRegistration> registrations(InterceptorRegistry registry) throws Exception {
        Field f = InterceptorRegistry.class.getDeclaredField("registrations");
        f.setAccessible(true);
        return (List<InterceptorRegistration>) f.get(registry);
    }

    @Configuration
    static class GuardWithDepsConfig {
        @Bean
        PermissionResolver permissionResolver() {
            return mock(PermissionResolver.class);
        }

        @Bean
        CompanyHeaderScopeNarrower narrower() {
            return new CompanyHeaderScopeNarrower();
        }

        @Bean
        ReportRegistry reportRegistry() {
            return mock(ReportRegistry.class);
        }

        @Bean
        TenantBoundaryGuard tenantBoundaryGuard(PermissionResolver resolver,
                                                CompanyHeaderScopeNarrower narrower,
                                                ReportRegistry registry) {
            return new TenantBoundaryGuard(resolver, narrower, registry);
        }

        @Bean
        ReportServiceWebMvcConfig reportServiceWebMvcConfig(TenantBoundaryGuard guard) {
            return new ReportServiceWebMvcConfig(guard);
        }
    }

    @Configuration
    static class ConfigOnlyNoGuard {
        @Bean
        ReportServiceWebMvcConfig reportServiceWebMvcConfig() {
            return new ReportServiceWebMvcConfig(null);
        }
    }
}
