package com.example.report.query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * Codex 019e0c99 iter-3 §C absorb: build {@code X-Report-Degraded} response
 * header from a list of {@link DegradationWarning}s. Header value is the
 * deduped, comma-joined list of warning {@code code}s; multi-branch reports
 * (e.g. multi-year same tenant) collapse to a single header value, while the
 * underlying warning list keeps tenant/report/table-level detail for
 * log/metric (Codex iter-3 explicit guidance).
 *
 * <p>If {@code warnings} is null or empty, returns an empty {@link HttpHeaders}
 * instance — caller can chain {@code ResponseEntity.ok().headers(...)} without
 * a null check.
 */
public final class DegradationHeaders {

    public static final String HEADER_NAME = "X-Report-Degraded";

    private DegradationHeaders() {}

    public static HttpHeaders of(List<DegradationWarning> warnings) {
        HttpHeaders headers = new HttpHeaders();
        if (warnings == null || warnings.isEmpty()) {
            return headers;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (DegradationWarning w : warnings) {
            if (w != null && w.code() != null && !w.code().isEmpty()) {
                codes.add(w.code());
            }
        }
        if (!codes.isEmpty()) {
            headers.set(HEADER_NAME, String.join(",", codes));
        }
        return headers;
    }
}
