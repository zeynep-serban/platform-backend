package com.example.report.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Maps {@link RemoteReportRequest} to downstream service query params
 * according to the declared {@code requestShape} (PR-D2.1c1).
 *
 * <p>Codex 019e8306 iter-2: request normalizer is critical because
 * dynamic-report frontend ↔ backend protocol differs from each
 * downstream service's protocol. Example: user-service expects
 * {@code sort=field,dir;...} but dynamic-report uses JSON
 * {@code sort=[{colId,sort}]}.
 *
 * <p>Supported shapes (PR-D2.1c1 initial set):
 * <ul>
 *   <li>{@code style-api-paged-v1} — user-service / permission-service
 *       style: {@code page}, {@code pageSize}, {@code search}, {@code sort}
 *       (comma+semicolon), and a single {@code advancedFilter} JSON query
 *       param containing the caller-supplied downstream-shaped payload
 *       verbatim. Caller (PR-D2.1c2 dispatcher) translates AG-Grid filter
 *       model → {@code {logic, conditions: [{field, op, value}]}} BEFORE
 *       constructing {@link RemoteReportRequest}.</li>
 * </ul>
 *
 * <p>Future shapes (not yet implemented): {@code style-api-paged-v2},
 * {@code audit-events-v1}, {@code aggregation-mart-v1}.
 */
@Component
public class RemoteRequestNormalizer {

    public static final String SHAPE_STYLE_API_PAGED_V1 = "style-api-paged-v1";
    /**
     * PR-D2.1c5 (Codex 019e83fd iter-1 amend): permission-service
     * /api/audit/events transport shape — flat per-field query params
     * with {@code filter[<key>]=<value>} pattern and direct passthrough
     * for the canonical audit filter set (dateFrom/dateTo/action/level/
     * service/correlationId/search/user → userEmail alias). NO
     * {@code advancedFilter} JSON param (downstream parser does not
     * understand the user-service contract).
     *
     * <p>Boundary: this normalizer DOES NOT validate field/op semantics
     * downstream-side — it transports caller-supplied condition list
     * verbatim. Caller (PR-D2.3 audit-report ReportController dispatch)
     * must already have whitelisted fields to the audit endpoint's
     * accepted set.
     *
     * <p>Sort: only the first {@link RemoteReportRequest.SortEntry} is
     * honored ({@code field,dir} format). Multi-sort throws
     * {@link RemoteRequestNormalizationException} with code
     * {@code REMOTE_SORT_MULTI_UNSUPPORTED} so the controller can return
     * structured 400.
     */
    public static final String SHAPE_AUDIT_EVENTS_V1 = "audit-events-v1";

    /**
     * Direct-passthrough field map for {@link #SHAPE_AUDIT_EVENTS_V1}.
     * Fields in this set go to {@code <field>=<value>} query params
     * (not {@code filter[<field>]=<value>}). Mirrors
     * {@code AuditEventController.extractFilters()} accepted aliases.
     */
    private static final java.util.Set<String> AUDIT_DIRECT_FIELDS = java.util.Set.of(
            "dateFrom", "dateTo", "action", "correlationId",
            "search", "level", "service",
            "from", "to", "user");

    private final ObjectMapper objectMapper;

    public RemoteRequestNormalizer() {
        this.objectMapper = new ObjectMapper();
    }

    public RemoteRequestNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper == null) ? new ObjectMapper() : objectMapper;
    }

    /**
     * Translate a normalized {@link RemoteReportRequest} into downstream
     * query params per the declared shape.
     *
     * @return MultiValueMap of query params; the executor appends these
     *         to the configured base-url + path.
     * @throws IllegalArgumentException if the shape is not recognized.
     */
    public MultiValueMap<String, String> toQueryParams(
            String requestShape, RemoteReportRequest request) {
        if (requestShape == null || requestShape.isBlank()) {
            throw new IllegalArgumentException("requestShape must not be blank");
        }
        return switch (requestShape) {
            case SHAPE_STYLE_API_PAGED_V1 -> toStyleApiPagedV1(request);
            case SHAPE_AUDIT_EVENTS_V1 -> toAuditEventsV1(request);
            default -> throw new IllegalArgumentException(
                    "Unknown requestShape: " + requestShape
                            + " (supported: " + SHAPE_STYLE_API_PAGED_V1
                            + ", " + SHAPE_AUDIT_EVENTS_V1 + ")");
        };
    }

    private MultiValueMap<String, String> toStyleApiPagedV1(RemoteReportRequest request) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

        // Required: page + pageSize
        params.add("page", String.valueOf(request.page()));
        params.add("pageSize", String.valueOf(request.pageSize()));

        // Optional: search (free-text)
        Optional.ofNullable(request.search())
                .filter(s -> !s.isBlank())
                .ifPresent(s -> params.add("search", s));

        // Sort: AG-Grid [{colId, sort}, ...] → "field,dir;field2,dir2"
        if (!request.sort().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RemoteReportRequest.SortEntry entry : request.sort()) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append(entry.colId()).append(",").append(entry.sort().toLowerCase());
            }
            params.add("sort", sb.toString());
        }

        // Advanced filter: serialize the caller-supplied downstream-shaped
        // payload as a single JSON-string `advancedFilter` query param.
        //
        // Codex 019e8306 iter-3 HIGH + iter-4 PARTIAL absorb:
        //
        // 1. Transport: ONE `advancedFilter` param (NOT field-keyed) so
        //    user-service.UserControllerV1.decodeAdvancedFilter() can
        //    Jackson.readValue() the URL-decoded JSON. Field-keyed map
        //    would break downstream parsing.
        //
        // 2. Payload shape: the caller (PR-D2.1c2 ReportController dispatcher)
        //    transforms the incoming AG-Grid filter model
        //    ({colId: {type, filter, filterType, filterTo}})
        //    into the downstream service's {logic, conditions} format
        //    BEFORE constructing RemoteReportRequest. This normalizer is
        //    transport-only; it serializes whatever payload the caller
        //    provided verbatim.
        //
        // Contract: see RemoteReportRequest.advancedFilter() javadoc.
        if (!request.advancedFilter().isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(request.advancedFilter());
                params.add("advancedFilter", json);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException(
                        "Failed to serialize advancedFilter to JSON: " + ex.getMessage(), ex);
            }
        }

        return params;
    }

    /**
     * PR-D2.1c5 (Codex 019e83fd): map {@link RemoteReportRequest} to
     * permission-service {@code /api/audit/events} transport.
     *
     * <p>Filter conditions extracted from the canonical
     * {@code {logic, conditions:[{field, op, value}]}} payload that the
     * controller layer pre-translates (AgGridFilterTranslator output).
     * Per-condition mapping:
     *
     * <ul>
     *   <li>Field in {@link #AUDIT_DIRECT_FIELDS} → direct
     *       {@code field=<value>} param (dateFrom, dateTo, action, ...)</li>
     *   <li>Otherwise → {@code filter[<field>]=<value>} pattern (mirrors
     *       {@code AuditEventController.extractFilters()} parsing)</li>
     * </ul>
     *
     * <p>The downstream endpoint ignores filter operator semantics —
     * audit reports are read as "match value substring within field" by
     * the upstream service. Multi-condition with logic=or is not
     * representable; controller must reject upstream (or send one
     * condition per call).
     *
     * @throws RemoteRequestNormalizationException with code
     *         {@code REMOTE_SORT_MULTI_UNSUPPORTED} when sort list has
     *         more than one entry (audit endpoint accepts single
     *         {@code field,dir} only)
     */
    @SuppressWarnings("unchecked")
    private MultiValueMap<String, String> toAuditEventsV1(RemoteReportRequest request) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

        // Required: page + pageSize (audit endpoint accepts these directly,
        // 1-based page numbering — matches RemoteReportRequest contract).
        params.add("page", String.valueOf(request.page()));
        params.add("pageSize", String.valueOf(request.pageSize()));

        // Optional: top-level search — distinct from filter[search].
        Optional.ofNullable(request.search())
                .filter(s -> !s.isBlank())
                .ifPresent(s -> params.add("search", s));

        // Sort: audit endpoint takes single "field,dir" only.
        if (request.sort().size() > 1) {
            throw new RemoteRequestNormalizationException(
                    "REMOTE_SORT_MULTI_UNSUPPORTED",
                    "audit-events-v1 transport supports a single sort field only; got "
                            + request.sort().size() + " entries. Reduce to one or "
                            + "send the secondary sort as a separate request.");
        }
        if (!request.sort().isEmpty()) {
            RemoteReportRequest.SortEntry entry = request.sort().get(0);
            params.add("sort", entry.colId() + "," + entry.sort().toLowerCase());
        }

        // Filter conditions: read from the canonical {logic, conditions}
        // payload. {@code logic=or} cannot be represented in flat query
        // params — reject with structured 400 so controller can map.
        Object logicObj = request.advancedFilter().get("logic");
        Object condsObj = request.advancedFilter().get("conditions");
        if (logicObj instanceof String logicStr
                && "or".equalsIgnoreCase(logicStr)
                && condsObj instanceof java.util.List<?> condsList
                && condsList.size() > 1) {
            throw new RemoteRequestNormalizationException(
                    "REMOTE_FILTER_OR_UNSUPPORTED",
                    "audit-events-v1 transport cannot express logic=or with multiple "
                            + "conditions in flat query params; downstream applies AND "
                            + "semantics. Send single condition or wait for c2.6 "
                            + "multi-call aggregation.");
        }
        if (condsObj instanceof java.util.List<?> conds) {
            for (Object condRaw : conds) {
                if (!(condRaw instanceof java.util.Map<?, ?> cond)) continue;
                Object fieldObj = cond.get("field");
                Object valueObj = cond.get("value");
                if (!(fieldObj instanceof String field) || field.isBlank()) continue;
                if (valueObj == null) continue;
                String value = String.valueOf(valueObj);
                if (AUDIT_DIRECT_FIELDS.contains(field)) {
                    params.add(field, value);
                } else {
                    // filter[<field>]=<value> — audit controller parses
                    // these via extractFilters() name pattern.
                    params.add("filter[" + field + "]", value);
                }
            }
        }

        return params;
    }
}
