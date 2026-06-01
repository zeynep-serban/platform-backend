package com.example.report.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps raw downstream JSON response to {@link RemoteReportResult}
 * according to the declared {@code responseShape} (PR-D2.1c1).
 *
 * <p>Supported shapes (PR-D2.1c1 initial set):
 * <ul>
 *   <li>{@code paged-items-total} — Standard paged response:
 *       {@code {items: [...], total: N}}. Total comes from the
 *       {@code total} field; rows from the {@code items} array.</li>
 *   <li>{@code items-array} — Flat array response: {@code [...]}.
 *       Total equals array length.</li>
 * </ul>
 *
 * <p>Codex 019e8306 iter-2: malformed response shape (missing fields,
 * wrong type) → {@link RemoteExecutionException} with
 * {@code downstreamStatus=null} (the HTTP status was OK but the body
 * didn't parse). Caller maps this to 502 Bad Gateway.
 */
@Component
public class RemoteResponseNormalizer {

    public static final String SHAPE_PAGED_ITEMS_TOTAL = "paged-items-total";
    public static final String SHAPE_ITEMS_ARRAY = "items-array";
    /**
     * PR-D2.1c5 (Codex 019e83fd iter-1 amend): permission-service
     * /api/audit/events response shape — {@code {events: [...], total: N, page: P}}.
     * Differs from {@link #SHAPE_PAGED_ITEMS_TOTAL} only in the rows field
     * name ({@code events} vs {@code items}); same total semantics + same
     * row validation contract.
     */
    public static final String SHAPE_PAGED_EVENTS_TOTAL = "paged-events-total";

    /**
     * Transform a raw downstream {@link JsonNode} to a {@link RemoteReportResult}
     * per the declared shape.
     *
     * @throws RemoteExecutionException if the response shape doesn't match
     *         the declared {@code responseShape}.
     * @throws IllegalArgumentException if {@code responseShape} is unknown.
     */
    public RemoteReportResult normalize(
            String responseShape, JsonNode body, String service, String path) {
        if (responseShape == null || responseShape.isBlank()) {
            throw new IllegalArgumentException("responseShape must not be blank");
        }
        if (body == null) {
            throw new RemoteExecutionException(service, path, null,
                    "downstream response body was null");
        }
        return switch (responseShape) {
            case SHAPE_PAGED_ITEMS_TOTAL -> normalizePagedTotal(body, service, path, SHAPE_PAGED_ITEMS_TOTAL, "items");
            case SHAPE_PAGED_EVENTS_TOTAL -> normalizePagedTotal(body, service, path, SHAPE_PAGED_EVENTS_TOTAL, "events");
            case SHAPE_ITEMS_ARRAY -> normalizeItemsArray(body, service, path);
            default -> throw new IllegalArgumentException(
                    "Unknown responseShape: " + responseShape
                            + " (supported: " + SHAPE_PAGED_ITEMS_TOTAL
                            + ", " + SHAPE_PAGED_EVENTS_TOTAL
                            + ", " + SHAPE_ITEMS_ARRAY + ")");
        };
    }

    /**
     * PR-D2.1c5: shared paged-total handler (Codex 019e83fd amend —
     * "ortak private helper ile items/events field adını parametreleştir").
     * Body shape: {@code {<rowsField>: [...], total: N}}.
     */
    private RemoteReportResult normalizePagedTotal(JsonNode body, String service, String path,
                                                    String shape, String rowsField) {
        if (!body.isObject()) {
            throw new RemoteExecutionException(service, path, null,
                    "expected JSON object for shape " + shape
                            + " but got " + body.getNodeType());
        }
        JsonNode rowsNode = body.get(rowsField);
        if (rowsNode == null || !rowsNode.isArray()) {
            throw new RemoteExecutionException(service, path, null,
                    "shape " + shape
                            + " requires '" + rowsField + "' array field");
        }
        JsonNode totalNode = body.get("total");
        if (totalNode == null || !totalNode.canConvertToLong()) {
            throw new RemoteExecutionException(service, path, null,
                    "shape " + shape
                            + " requires 'total' integer field");
        }
        long total = totalNode.asLong();
        if (total < 0) {
            throw new RemoteExecutionException(service, path, null,
                    "shape " + shape
                            + " 'total' must be >= 0, got " + total);
        }
        return new RemoteReportResult(extractRows(rowsNode, service, path), total);
    }

    private RemoteReportResult normalizeItemsArray(JsonNode body, String service, String path) {
        if (!body.isArray()) {
            throw new RemoteExecutionException(service, path, null,
                    "expected JSON array for shape " + SHAPE_ITEMS_ARRAY
                            + " but got " + body.getNodeType());
        }
        List<Map<String, Object>> rows = extractRows(body, service, path);
        return new RemoteReportResult(rows, rows.size());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRows(JsonNode arrayNode, String service, String path) {
        List<Map<String, Object>> rows = new ArrayList<>(arrayNode.size());
        Iterator<JsonNode> iter = arrayNode.elements();
        int idx = 0;
        while (iter.hasNext()) {
            JsonNode row = iter.next();
            if (!row.isObject()) {
                // Codex 019e8306 iter-3 Low absorb: fail-closed on non-object
                // rows. Silent drop would create total ↔ rows.size() drift and
                // produce confusing empty grids. Grid contract requires object
                // rows; non-object → RemoteExecutionException (downstreamStatus=null).
                throw new RemoteExecutionException(service, path, null,
                        "downstream response row at index " + idx
                                + " is not a JSON object (got " + row.getNodeType()
                                + "); grid contract requires object rows");
            }
            Map<String, Object> rowMap = new java.util.LinkedHashMap<>();
            row.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                rowMap.put(entry.getKey(), jsonToJava(value));
            });
            rows.add(rowMap);
            idx++;
        }
        return rows;
    }

    /**
     * Minimal JSON → Java value converter. Sufficient for grid row data
     * (primitives + null); deep object/array support not needed at this
     * layer because grid columns are flat.
     */
    private Object jsonToJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        // For nested objects/arrays, return the JsonNode itself; caller can
        // re-serialize if needed. Grid columns rarely need this.
        return node;
    }
}
