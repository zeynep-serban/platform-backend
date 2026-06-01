package com.example.report.execution;

import java.util.List;
import java.util.Map;

/**
 * Normalized request DTO for the remote-http executor (PR-D2.1c1).
 *
 * <p>The controller layer constructs this from the incoming
 * {@code /data} query params + the resolved JWT/company-id context.
 * The {@link RemoteRequestNormalizer} then transforms it into the
 * downstream service's exact wire format (query params, headers, etc.).
 *
 * <p>Codex 019e8306 iter-2: this DTO captures the <em>flat</em>
 * grid request shape only. {@code /query} (grouping/pivot) is NOT
 * supported by remote-http in PR-D2.1c — controller must return
 * structured 400 REMOTE_GROUPING_NOT_SUPPORTED for grouped requests.
 *
 * @param page              1-indexed page number (frontend convention)
 * @param pageSize          Rows per page (typically 25/50/100/200)
 * @param search            Free-text search term ({@code search=} URL param);
 *                          downstream services like user-service accept this
 *                          as a top-level filter
 * @param sort              AG-Grid {@code [{colId, sort}, ...]} array; the
 *                          normalizer transforms to the downstream's
 *                          expected format (e.g. user-service uses
 *                          {@code "field,dir;field2,dir2"})
 * @param advancedFilter    Downstream-shaped filter payload.
 *                          <strong>Caller responsibility (PR-D2.1c2 dispatcher):</strong>
 *                          AG-Grid filter model
 *                          ({@code {colId: {type, filter, filterTo, filterType}}})
 *                          must be transformed to the downstream service's
 *                          expected format BEFORE this DTO is constructed.
 *                          For user-service / permission-service
 *                          {@code style-api-paged-v1} shape that is
 *                          {@code {logic: "and"|"or", conditions: [{field, op, value}, ...]}}
 *                          (matches {@code UserControllerV1.decodeAdvancedFilter}
 *                          parser contract).
 *                          See ADR-0015 PR-D2.1c2 spec for the AG-Grid →
 *                          {logic, conditions} translation table.
 *                          {@link RemoteRequestNormalizer} serializes this
 *                          map verbatim as the single {@code advancedFilter}
 *                          JSON query param.
 * @param companyId         Tenant scope (X-Company-Id header, validated
 *                          by {@code CompanyHeaderScopeNarrower} at the
 *                          controller layer BEFORE forwarding)
 * @param jwtToken          Raw JWT token string (from
 *                          {@code Jwt.getTokenValue()}) — propagated as
 *                          {@code Authorization: Bearer <token>} to
 *                          downstream service
 */
public record RemoteReportRequest(
        int page,
        int pageSize,
        String search,
        List<SortEntry> sort,
        Map<String, Object> advancedFilter,
        String companyId,
        String jwtToken
) {
    public RemoteReportRequest {
        if (page < 1) {
            throw new IllegalArgumentException("RemoteReportRequest.page must be >= 1: " + page);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("RemoteReportRequest.pageSize must be >= 1: " + pageSize);
        }
        // sort and advancedFilter can be null/empty for default grid views.
        sort = (sort == null) ? List.of() : List.copyOf(sort);
        advancedFilter = (advancedFilter == null) ? Map.of() : Map.copyOf(advancedFilter);
        // jwtToken and companyId can legitimately be null in test paths;
        // executor will reject if downstream requires auth (401 → RemoteAuthException).
    }

    /**
     * Single sort criterion from the AG-Grid sort model.
     *
     * @param colId  Column field name (matches
     *               {@link com.example.report.registry.ColumnDefinition#field()})
     * @param sort   Direction ({@code "asc"} or {@code "desc"})
     */
    public record SortEntry(String colId, String sort) {
        public SortEntry {
            if (colId == null || colId.isBlank()) {
                throw new IllegalArgumentException("SortEntry.colId must not be blank");
            }
            if (sort == null || (!sort.equalsIgnoreCase("asc") && !sort.equalsIgnoreCase("desc"))) {
                throw new IllegalArgumentException(
                        "SortEntry.sort must be 'asc' or 'desc' (case-insensitive): " + sort);
            }
        }
    }
}
