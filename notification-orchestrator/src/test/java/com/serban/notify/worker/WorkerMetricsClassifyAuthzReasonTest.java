package com.serban.notify.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link WorkerMetrics#classifyAuthzReason(String)} — Faz 23.2 v2
 * (Codex 019e59eb REVISE absorb).
 *
 * <p>Locks the normalized-reason whitelist so cardinality-safe labels remain
 * stable for Grafana dashboards / PrometheusRule alerts on
 * {@code notify_authz_denied_total{channel,reason_class}}.
 */
class WorkerMetricsClassifyAuthzReasonTest {

    @Test
    void noTupleReasonMappedToNoTupleClass() {
        assertThat(WorkerMetrics.classifyAuthzReason("no_tuple")).isEqualTo("no_tuple");
        assertThat(WorkerMetrics.classifyAuthzReason("tuple_not_found")).isEqualTo("no_tuple");
        assertThat(WorkerMetrics.classifyAuthzReason("not_authorized")).isEqualTo("no_tuple");
        assertThat(WorkerMetrics.classifyAuthzReason("unknown")).isEqualTo("no_tuple");
    }

    @Test
    void authzUnreachableMappedDistinctClass() {
        // Distinct from no_tuple — connectivity outage requires different
        // alert (escalate / page on-call); no_tuple is data-state error.
        assertThat(WorkerMetrics.classifyAuthzReason("authz_unreachable"))
            .isEqualTo("authz_unreachable");
    }

    @Test
    void authzHttp4xx5xxCollapsedToHttpErrorClass() {
        // Any HTTP non-200 from permission-service → bounded label. Per-code
        // breakdown lives in `permission_service_http_total` upstream, not
        // notification-orchestrator metric.
        assertThat(WorkerMetrics.classifyAuthzReason("authz_http_400")).isEqualTo("authz_http_error");
        assertThat(WorkerMetrics.classifyAuthzReason("authz_http_401")).isEqualTo("authz_http_error");
        assertThat(WorkerMetrics.classifyAuthzReason("authz_http_403")).isEqualTo("authz_http_error");
        assertThat(WorkerMetrics.classifyAuthzReason("authz_http_500")).isEqualTo("authz_http_error");
        assertThat(WorkerMetrics.classifyAuthzReason("authz_http_502")).isEqualTo("authz_http_error");
    }

    @Test
    void validationErrorMappedExactClass() {
        assertThat(WorkerMetrics.classifyAuthzReason("validation_error"))
            .isEqualTo("validation_error");
    }

    @Test
    void authzDisabledMappedDistinctClass() {
        // Codex 019e59f3 REVISE absorb: permission-service controller emits
        // reason="authz_disabled" when OpenFGA bean is absent (200/deny path).
        // MUST be a distinct class — security-config regression cannot hide
        // under "other" or collapse into "no_tuple" (different remediation:
        // re-enable bean vs. seed missing tuple).
        assertThat(WorkerMetrics.classifyAuthzReason("authz_disabled"))
            .isEqualTo("authz_disabled");
    }

    @Test
    void nullOrBlankMappedToOtherClass() {
        assertThat(WorkerMetrics.classifyAuthzReason(null)).isEqualTo("other");
        assertThat(WorkerMetrics.classifyAuthzReason("")).isEqualTo("other");
        assertThat(WorkerMetrics.classifyAuthzReason("   ")).isEqualTo("other");
    }

    @Test
    void unrecognizedReasonsCollapseToOther() {
        // Forward-compat — future upstream reason strings should not silently
        // explode cardinality. Add to whitelist when intentional.
        assertThat(WorkerMetrics.classifyAuthzReason("brand_new_upstream_reason"))
            .isEqualTo("other");
        assertThat(WorkerMetrics.classifyAuthzReason("foo_bar_baz")).isEqualTo("other");
    }
}
