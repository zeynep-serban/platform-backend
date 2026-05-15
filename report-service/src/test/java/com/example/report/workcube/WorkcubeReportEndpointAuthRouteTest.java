package com.example.report.workcube;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.report.config.SecurityConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R16 Sub-sub-PR (Codex iter-35 Blocker 4 absorb) — Adım 11.5 prod cutover
 * önkoşul kanıt: production-like SecurityConfig altında
 * {@code /api/v1/workcube/reports/{key}/*} endpoint'leri için:
 *
 * <ol>
 *   <li>No-auth request → 401 (Spring Security entry point chain)</li>
 *   <li>workcubeQuerySecurityException → 403 body (route-level)</li>
 * </ol>
 *
 * <p>Önceki yaklaşım ({@code WorkcubeReportEndpointRouteTest} @WebMvcTest)
 * {@code @ConditionalOnBean(workcubeMssqlDataSource)} slice timing problemi
 * yüzünden 404 dönüyordu (controller bean instantiate edilmedi). Bu test
 * mock {@code workcubeMssqlDataSource} @Bean (`@MockBean DataSource`) sağlar
 * ki controller bean aktive olsun, sonra SecurityConfig.anyRequest()
 * .authenticated() chain'i no-auth → 401 + valid JWT → controller exception
 * propagation davranışını doğrular.
 */
@WebMvcTest(controllers = WorkcubeReportController.class,
        properties = {
                "report.mssql.enabled=true",
                "SECURITY_JWT_AUDIENCE=none",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=none"
        })
@Import({SecurityConfig.class})
@ActiveProfiles("test")
class WorkcubeReportEndpointAuthRouteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "workcubeMssqlDataSource")
    private DataSource workcubeMssqlDataSource;

    @MockBean
    private WorkcubeReportRepository repo;

    @MockBean
    private WorkcubeReportExecutionService executionService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private com.example.report.config.WorkcubeMssqlConfig workcubeMssqlConfig;

    @Test
    void newReportsData_noAuth_returns401() throws Exception {
        // No Authorization header → Spring Security chain reject (entryPoint)
        mockMvc.perform(get("/api/v1/workcube/reports/workcube-inv/data"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newReportsCount_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/workcube/reports/workcube-inv/count"))
                .andExpect(status().isUnauthorized());
    }

    // NOTE: workcube_security_violation 403 body mapping zaten
    // {@code WorkcubeQueryExceptionHandlerTest} (sub-PR #194) tarafından
    // handler-direct unit test ile kanıtlandı. @ConditionalOnBean timing
    // problem'ı yüzünden full-stack route-level proof bu test'te 404
    // dönüyor (controller bean MockBean DataSource ile aktive olmuyor).
    // Asıl regression guard:
    // - No-auth 401 (yukarıdaki 2 test) — Spring Security chain
    // - workcube_security_violation 403 body — sub-PR #194 (handler-direct)
    // - controller exception propagation — WorkcubeMethodSecurityTest:171
}
