package com.example.report.workcube;

import com.example.report.config.SecurityConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Route-level auth contract tests for the Adım 11.3/11.4 adapter-backed
 * Workcube report endpoints exposed by {@link WorkcubeReportController}.
 *
 * <p>Endpoint surface under test:
 * <ul>
 *   <li>{@code GET /api/v1/workcube/reports/{key}/data}</li>
 *   <li>{@code GET /api/v1/workcube/reports/{key}/count}</li>
 * </ul>
 *
 * <p>Adım 11.4 removed the class-level {@code @PreAuthorize} interim gate
 * from {@link WorkcubeReportController}; full authorization is now driven
 * by {@link WorkcubeReportExecutionService#executeData} /
 * {@link WorkcubeReportExecutionService#executeCount} access evaluator
 * branches. Adım 11.5 prod cutover (flipping
 * {@code REPORT_MSSQL_ENABLED=true}) requires explicit proof that:
 * <ol>
 *   <li>An unauthenticated request never reaches the service layer (HTTP
 *       401 via Spring Security {@code .anyRequest().authenticated()} on
 *       the resource-server filter chain).</li>
 *   <li>An authenticated request that triggers a runtime allowlist
 *       violation surfaces the deterministic 403 body shape produced by
 *       {@link WorkcubeQueryExceptionHandler} (so the FE / audit pipeline
 *       can rely on a stable contract).</li>
 * </ol>
 *
 * <p>Test pattern (Codex {@code 019e2a4f} plan-time consultation
 * AGREE-after-REVISE):
 * <ul>
 *   <li>{@link WebMvcTest} slice over {@link WorkcubeReportController}
 *       only, to avoid Eureka / Flyway / datasource autoconfigure CI risk
 *       that a full {@code @SpringBootTest} would pull in.</li>
 *   <li>{@link Import} of the real {@link SecurityConfig} and the
 *       {@link WorkcubeQueryExceptionHandler} so the JWT-required filter
 *       chain and the exception → 403 body mapping are both exercised.</li>
 *   <li>{@link ActiveProfiles {@code "test"}} so {@code SecurityConfig}
 *       ({@code @Profile("!local & !dev")}) and the non-{@code conntest}
 *       filter-chain bean are both active.</li>
 *   <li>A {@link TestConfiguration} contributing a mocked
 *       {@code workcubeMssqlDataSource} bean to satisfy the
 *       {@code @ConditionalOnBean(name = "workcubeMssqlDataSource")} on
 *       {@link WorkcubeReportController}.</li>
 *   <li>{@link MockitoBean MockitoBeans} for {@link JwtDecoder} (so we can
 *       hand-craft a valid stub {@link Jwt} for the authenticated case
 *       without depending on Keycloak issuer/audience validation) plus
 *       {@link WorkcubeReportRepository} and
 *       {@link WorkcubeReportExecutionService}.</li>
 * </ul>
 *
 * <p>Scope discipline (Codex {@code 019e2a4f} pick E): no legacy
 * {@code /views/*} cases here — those keep their own method-level
 * {@code @PreAuthorize} contract until Adım 11.5 cutover and are covered
 * by {@code WorkcubeMethodSecurityTest}.
 */
@WebMvcTest(WorkcubeReportController.class)
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        WorkcubeQueryExceptionHandler.class,
        WorkcubeReportEndpointAuthRouteTest.TestMssqlBeanConfig.class
})
class WorkcubeReportEndpointAuthRouteTest {

    private static final String ROUTE_TEST_TOKEN = "route-test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private WorkcubeReportRepository repository;

    @MockitoBean
    private WorkcubeReportExecutionService executionService;

    @Test
    void newReportsData_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/workcube/reports/{key}/data", "workcube-inv"))
                .andExpect(status().isUnauthorized());

        // No service interaction should have happened — request must be
        // rejected at the security filter chain before reaching the
        // controller / service layer.
        verifyNoInteractions(executionService);
    }

    @Test
    void newReportsCount_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/workcube/reports/{key}/count", "workcube-inv"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(executionService);
    }

    @Test
    void newReportsData_workcubeSecurityViolation_returns403Body() throws Exception {
        when(jwtDecoder.decode(eq(ROUTE_TEST_TOKEN))).thenReturn(stubJwt(ROUTE_TEST_TOKEN));
        when(executionService.executeData(
                eq("workcube-inv"),
                anyInt(),
                anyInt(),
                any(),
                any(),
                any(),
                any()))
                .thenThrow(new WorkcubeQuerySecurityException(
                        "workcube-inv",
                        "Rendered SQL references table not in ReportingAllowlist.V1: dbo.ROGUE_TABLE"));

        mockMvc.perform(get("/api/v1/workcube/reports/{key}/data", "workcube-inv")
                        .header("Authorization", "Bearer " + ROUTE_TEST_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("workcube_query_security_violation"))
                .andExpect(jsonPath("$.reportKey").value("workcube-inv"))
                .andExpect(jsonPath("$.message").value(
                        "Rendered SQL references table not in ReportingAllowlist.V1: dbo.ROGUE_TABLE"))
                .andExpect(jsonPath("$.hint").exists());

        verify(executionService, times(1)).executeData(
                eq("workcube-inv"), anyInt(), anyInt(), any(), any(), any(), any());
    }

    /**
     * Builds a minimal {@link Jwt} with deterministic header / claim shape
     * for the route-level authenticated test. The token value matches the
     * Authorization header passed by the test, so the {@code @MockitoBean}
     * {@link JwtDecoder} can return this stub without depending on a real
     * Keycloak issuer / audience pair. Issued/expiry are bracketed around
     * {@code now} so any future BearerTokenAuthenticationFilter timestamp
     * validation remains satisfied.
     */
    private static Jwt stubJwt(String tokenValue) {
        Instant now = Instant.now();
        return new Jwt(
                tokenValue,
                now.minusSeconds(60),
                now.plusSeconds(300),
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of(
                        "sub", "route-test-subject",
                        "preferred_username", "route-test",
                        "aud", List.of("report-service"),
                        "iss", "https://route-test/issuer"
                ));
    }

    /**
     * Test-only configuration that:
     * <ol>
     *   <li>Provides a mocked {@code workcubeMssqlDataSource} bean to
     *       satisfy the {@code @ConditionalOnBean} guard on
     *       {@link WorkcubeReportController}.</li>
     *   <li>Registers the controller directly as a Spring bean. We
     *       cannot rely on {@code @WebMvcTest(WorkcubeReportController.class)}
     *       component-scan import alone, because the
     *       {@code @ConditionalOnBean(name = "workcubeMssqlDataSource")}
     *       guard is re-evaluated against the slice context and may not
     *       see the {@link TestConfiguration} bean in time when the
     *       controller filter runs. A direct {@code @Bean} definition
     *       bypasses the conditional entirely while leaving the routing,
     *       advice, and Spring Security filter chain unchanged.</li>
     * </ol>
     *
     * <p>The {@link WorkcubeQueryExceptionHandler} constructor dependency
     * on Jackson's {@code ObjectMapper} is satisfied by the standard
     * Spring Boot MVC auto-configuration that the {@code @WebMvcTest}
     * slice activates — no manual bean is needed here.
     */
    @TestConfiguration
    static class TestMssqlBeanConfig {

        @Bean(name = "workcubeMssqlDataSource")
        DataSource workcubeMssqlDataSource() {
            return mock(DataSource.class);
        }

        @Bean
        WorkcubeReportController workcubeReportController(
                WorkcubeReportRepository repo,
                WorkcubeReportExecutionService executionService) {
            return new WorkcubeReportController(repo, executionService);
        }
    }
}
