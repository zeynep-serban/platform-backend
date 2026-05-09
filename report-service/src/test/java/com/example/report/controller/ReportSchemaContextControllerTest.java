package com.example.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthResult;
import com.example.report.schema.SchemaTruthService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 Program 8e — ReportSchemaContextController unit tests
 * (Codex iter-1 absorb 2 BLOCKING + 2 REVISE).
 *
 * <p>Tests:
 * <ol>
 *   <li>Unknown report → 404</li>
 *   <li>Unauthorized user → 403 (Codex §1 absorb)</li>
 *   <li>Tier 1 schema_service → header + visible columns only (Codex §2)</li>
 *   <li>Tier 2 fallback → committed_snapshot header</li>
 *   <li>Tier 1+2 miss → registry_type tier (Codex §2 Tier 3 partial)</li>
 *   <li>Restricted column hidden + raw DB column not leaked (Codex §1 + §2)</li>
 *   <li>Snapshot DB type overrides registry type (precision contract)</li>
 * </ol>
 */
class ReportSchemaContextControllerTest {

    private ReportRegistry mockRegistry;
    private SchemaTruthService mockFacade;
    private PermissionResolver mockPermissionClient;
    private ReportAccessEvaluator mockAccessEvaluator;
    private ColumnFilter mockColumnFilter;
    private ReportAuditClient mockAuditClient;
    private Jwt mockJwt;
    private ReportSchemaContextController controller;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(ReportRegistry.class);
        mockFacade = mock(SchemaTruthService.class);
        mockPermissionClient = mock(PermissionResolver.class);
        mockAccessEvaluator = mock(ReportAccessEvaluator.class);
        mockColumnFilter = mock(ColumnFilter.class);
        mockAuditClient = mock(ReportAuditClient.class);
        mockJwt = mock(Jwt.class);
        // Codex 019e0d06 iter-2 absorb: schema-context endpoint X-Company-Id +
        // resolver-aware schema lookup; mocks default to legacy literal path.
        com.example.report.authz.CompanyHeaderScopeNarrower mockNarrower =
                mock(com.example.report.authz.CompanyHeaderScopeNarrower.class);
        when(mockNarrower.narrow(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        com.example.report.query.YearlySchemaResolver mockYearly =
                mock(com.example.report.query.YearlySchemaResolver.class);
        com.example.report.query.CurrentTenantSchemaResolver mockCurrent =
                mock(com.example.report.query.CurrentTenantSchemaResolver.class);

        controller = new ReportSchemaContextController(
                mockRegistry, mockFacade, mockPermissionClient,
                mockAccessEvaluator, mockColumnFilter, mockAuditClient,
                mockNarrower, mockYearly, mockCurrent);
    }

    @Test
    void getSchemaContext_unknownReport_returns404() {
        when(mockRegistry.get("ghost")).thenReturn(Optional.empty());

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("ghost", null, mockJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSchemaContext_unauthorizedUser_throws403() {
        // Codex iter-1 §1 absorb: report-level access check
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));
        AuthzMeResponse authz = new AuthzMeResponse();
        when(mockPermissionClient.getAuthzMe(any())).thenReturn(authz);
        when(mockAccessEvaluator.evaluate(any(), any()))
                .thenReturn(ReportAccessEvaluator.AccessResult.DENIED_NO_REPORT_VIEW);

        assertThatThrownBy(() -> controller.getSchemaContext("fin-muhasebe-detay", null, mockJwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FORBIDDEN");
    }

    @Test
    void getSchemaContext_tier1Success_returnsSchemaServiceTier_visibleColumnsOnly() {
        // Codex iter-1 §2 absorb: visible report-column shape, NOT raw DB
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));
        AuthzMeResponse authz = new AuthzMeResponse();
        when(mockPermissionClient.getAuthzMe(any())).thenReturn(authz);
        when(mockAccessEvaluator.evaluate(any(), any()))
                .thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        // Visible columns: only AMOUNT (registry has 2: AMOUNT + ACCOUNT_CODE)
        when(mockColumnFilter.getVisibleColumnDefinitions(any(), any()))
                .thenReturn(List.of(def.columns().get(0))); // only AMOUNT

        // Snapshot has 3 columns (extra ACC_COMPANY_ID NOT in report)
        SchemaSnapshot.ColumnInfo amount = new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.ColumnInfo accountCode = new SchemaSnapshot.ColumnInfo("ACCOUNT_CODE", "NVARCHAR(50)", false);
        SchemaSnapshot.ColumnInfo accCompanyId = new SchemaSnapshot.ColumnInfo("ACC_COMPANY_ID", "INT", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35",
                List.of(amount, accountCode, accCompanyId));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshotWithTier(any(), any()))
                .thenReturn(new SchemaTruthResult(Optional.of(snapshot), SchemaTruthResult.TIER_SCHEMA_SERVICE));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay", null, mockJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("schema_service");

        Map<String, String> types = response.getBody().columnTypes();
        assertThat(types).containsEntry("AMOUNT", "DECIMAL(18,2)"); // DB precision
        assertThat(types).doesNotContainKey("ACCOUNT_CODE"); // visibility filter
        assertThat(types).doesNotContainKey("ACC_COMPANY_ID"); // raw DB column hidden
    }

    @Test
    void getSchemaContext_tier1FailSoft_tier2Success_returnsCommittedSnapshotTier() {
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));
        AuthzMeResponse authz = new AuthzMeResponse();
        when(mockPermissionClient.getAuthzMe(any())).thenReturn(authz);
        when(mockAccessEvaluator.evaluate(any(), any()))
                .thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        when(mockColumnFilter.getVisibleColumnDefinitions(any(), any()))
                .thenReturn(def.columns());

        SchemaSnapshot.ColumnInfo amount = new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(amount));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshotWithTier(any(), any()))
                .thenReturn(new SchemaTruthResult(Optional.of(snapshot), SchemaTruthResult.TIER_COMMITTED_SNAPSHOT));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay", null, mockJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("committed_snapshot");
    }

    @Test
    void getSchemaContext_tier1And2Miss_fallsToTier3RegistryType() {
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));
        AuthzMeResponse authz = new AuthzMeResponse();
        when(mockPermissionClient.getAuthzMe(any())).thenReturn(authz);
        when(mockAccessEvaluator.evaluate(any(), any()))
                .thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        when(mockColumnFilter.getVisibleColumnDefinitions(any(), any()))
                .thenReturn(def.columns());

        // Both tier 1+2 miss → facade returns registry_type tier with empty snapshot
        when(mockFacade.fetchSnapshotWithTier(any(), any()))
                .thenReturn(new SchemaTruthResult(Optional.empty(), SchemaTruthResult.TIER_REGISTRY_TYPE));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay", null, mockJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("registry_type");
        // Tier 3: registry types fallback (number for AMOUNT, text for ACCOUNT_CODE)
        Map<String, String> types = response.getBody().columnTypes();
        assertThat(types).containsEntry("AMOUNT", "number");
        assertThat(types).containsEntry("ACCOUNT_CODE", "text");
    }

    @Test
    void getSchemaContext_visibleAliasNotInSnapshot_fallsToRegistryType() {
        // sourceQuery alias kolonları snapshot table.columns()'ta yok; registry type kullan
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));
        AuthzMeResponse authz = new AuthzMeResponse();
        when(mockPermissionClient.getAuthzMe(any())).thenReturn(authz);
        when(mockAccessEvaluator.evaluate(any(), any()))
                .thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        when(mockColumnFilter.getVisibleColumnDefinitions(any(), any()))
                .thenReturn(def.columns());

        // Snapshot has different column (ACC_COMPANY_ID instead of AMOUNT alias)
        SchemaSnapshot.ColumnInfo accCompanyId = new SchemaSnapshot.ColumnInfo("ACC_COMPANY_ID", "INT", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(accCompanyId));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshotWithTier(any(), any()))
                .thenReturn(new SchemaTruthResult(Optional.of(snapshot), SchemaTruthResult.TIER_SCHEMA_SERVICE));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay", null, mockJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // AMOUNT alias not in snapshot → registry type "number" used
        assertThat(response.getBody().columnTypes()).containsEntry("AMOUNT", "number");
        assertThat(response.getBody().columnTypes()).doesNotContainKey("ACC_COMPANY_ID");
    }

    private ReportDefinition buildDef(String key, String table, String schema) {
        return new ReportDefinition(
                key, "1", "Test", "test", "test",
                table, schema,
                "yearly", null, null,
                List.of(
                        new ColumnDefinition("AMOUNT", "Tutar", "number", 100, false),
                        new ColumnDefinition("ACCOUNT_CODE", "Hesap", "text", 100, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));
    }
}
