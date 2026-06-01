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
            default -> throw new IllegalArgumentException(
                    "Unknown requestShape: " + requestShape
                            + " (supported: " + SHAPE_STYLE_API_PAGED_V1 + ")");
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
}
