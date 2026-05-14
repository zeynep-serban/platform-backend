package com.example.report.config;

import com.example.report.query.TenantBoundaryGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Phase 2 Program 2 — Registers {@link TenantBoundaryGuard} on the three
 * report execution endpoints (Adım 5).
 *
 * <h2>Path matrix (Codex iter-14 absorb)</h2>
 * <table>
 *   <caption>Guarded vs bypass paths</caption>
 *   <tr><th>Path</th><th>Guard?</th><th>Why</th></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/data}</td><td>YES</td><td>Row execution</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/query}</td><td>YES</td><td>Ad-hoc execution</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/export}</td><td>YES</td><td>Bulk row export</td></tr>
 *   <tr><td>{@code /api/v1/reports}</td><td>NO</td><td>Catalog list</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/metadata}</td><td>NO</td><td>Schema-only metadata</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/history}</td><td>NO</td><td>Definition history</td></tr>
 *   <tr><td>{@code /api/v1/reports/categories}</td><td>NO</td><td>Catalog grouping</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/schema-context}</td><td>NO</td><td>Tier/policy context</td></tr>
 *   <tr><td>{@code /api/v1/reports/&#42;/alerts}, {@code /schedules}</td><td>NO</td><td>Adım 2 report-level authz already covers</td></tr>
 *   <tr><td>{@code /api/v1/workcube/&#42;&#42;}</td><td>NO</td><td>Adım 1.5 interim gate; folded into Adım 11 adapter</td></tr>
 * </table>
 *
 * <p>The positive allowlist (only three path patterns) makes adding new
 * catalog/metadata endpoints zero-effort — anything not under the three
 * registered patterns passes through.
 */
@Configuration
public class ReportServiceWebMvcConfig implements WebMvcConfigurer {

    private final TenantBoundaryGuard tenantBoundaryGuard;

    public ReportServiceWebMvcConfig(@Autowired(required = false) @Nullable TenantBoundaryGuard tenantBoundaryGuard) {
        this.tenantBoundaryGuard = tenantBoundaryGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (tenantBoundaryGuard == null) {
            return;
        }
        registry.addInterceptor(tenantBoundaryGuard)
                .addPathPatterns(
                        "/api/v1/reports/*/data",
                        "/api/v1/reports/*/query",
                        "/api/v1/reports/*/export"
                );
    }
}
