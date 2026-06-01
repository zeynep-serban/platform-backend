package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Report execution adapter configuration (ADR-0015, PR-D2.1b).
 *
 * <p>Opt-in field on {@link ReportDefinition} that lets a report declare
 * <em>how</em> its rows are produced. When absent, the report falls back
 * to the legacy SQL {@code QueryEngine} path that has been the default
 * since the registry was introduced.
 *
 * <p>Wire form example for the {@code users-overview} pure-grid pilot:
 *
 * <pre>{@code
 * {
 *   "key": "users-overview",
 *   "execution": {
 *     "kind": "remote-http",
 *     "service": "user-service",
 *     "path": "/api/v1/users",
 *     "responseShape": "paged-items-total"
 *   }
 * }
 * }</pre>
 *
 * <h2>Field semantics</h2>
 *
 * <ul>
 *   <li>{@code kind} — REQUIRED. {@link ExecutionKind#SQL} or {@link ExecutionKind#REMOTE_HTTP}.</li>
 *   <li>{@code service} — REQUIRED for {@code remote-http}. Allowlist'li servis
 *       adı (örn. {@code user-service}, {@code permission-service},
 *       {@code notification-orchestrator}). PR-D2.1c'de allowlist guard
 *       eklenecek.</li>
 *   <li>{@code path} — REQUIRED for {@code remote-http}. Servis-internal
 *       path (örn. {@code /api/v1/users}, {@code /api/v1/roles}). Bu da
 *       allowlist'li (service+path tuple).</li>
 *   <li>{@code responseShape} — REQUIRED for {@code remote-http}. Response
 *       normalizer kontratı. PR-D2.1c'de tanımlı shape'ler:
 *       <ul>
 *         <li>{@code paged-items-total} — backend {@code {items: [...], total: N}}
 *             şeklinde döner; grid data + total ile eşleşir.</li>
 *         <li>{@code items-array} — backend düz array döner; grid data ile
 *             eşleşir, total = items.length.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Validation</h2>
 *
 * <p>Constructor-level pre-checks:
 *
 * <ul>
 *   <li>{@code kind} null → IllegalArgumentException.</li>
 *   <li>{@code kind == REMOTE_HTTP} ve {@code service}/{@code path}/{@code responseShape}
 *       boş → IllegalArgumentException.</li>
 *   <li>{@code kind == SQL} ve {@code service}/{@code path}/{@code responseShape}
 *       dolu → IllegalArgumentException (yanlış kullanım, SQL path'i sourceQuery
 *       kullanır).</li>
 * </ul>
 *
 * <p>NOT validated at constructor (PR-D2.1c iş): allowlist match
 * ({@code service}+{@code path} tuple), responseShape known-set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionConfig(
        ExecutionKind kind,
        String service,
        String path,
        String responseShape
) {
    public ExecutionConfig {
        if (kind == null) {
            throw new IllegalArgumentException("ExecutionConfig.kind must not be null");
        }
        if (kind == ExecutionKind.REMOTE_HTTP) {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.service must not be blank for kind=remote-http");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.path must not be blank for kind=remote-http");
            }
            if (responseShape == null || responseShape.isBlank()) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.responseShape must not be blank for kind=remote-http");
            }
        } else if (kind == ExecutionKind.SQL) {
            // SQL kind keeps the legacy path: sourceQuery + sourceSchema +
            // QueryEngine handle everything. service/path/responseShape are
            // remote-http concepts and must stay null to prevent ambiguity.
            if (service != null) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.service must be null for kind=sql (legacy MSSQL path)");
            }
            if (path != null) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.path must be null for kind=sql (legacy MSSQL path)");
            }
            if (responseShape != null) {
                throw new IllegalArgumentException(
                        "ExecutionConfig.responseShape must be null for kind=sql (legacy MSSQL path)");
            }
        }
    }
}
