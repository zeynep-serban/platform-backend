package com.serban.notify.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthzClient unit test (Codex 019dfaaa PR5 Q4 + lock-in #1, #2).
 *
 * <p>Verifies:
 * <ul>
 *   <li>HTTP request shape (path, body, X-Internal-Api-Key header)</li>
 *   <li>200 → AuthzDecision parsed</li>
 *   <li>Non-200 → fail-closed DENY</li>
 *   <li>Connection refused → fail-closed DENY</li>
 * </ul>
 */
class AuthzClientTest {

    @RegisterExtension
    static WireMockExtension permissionService = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private static final String API_KEY = "test-internal-key";

    @Test
    void allowedWhenPermissionServiceReturns200True() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        AuthzClient client = client();
        var decision = client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("tuple_match");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("X-Internal-Api-Key", equalTo(API_KEY))
            .withRequestBody(equalToJson("""
                {"principal_type":"subscriber","principal_id":"1204",
                 "relation":"can_receive","object_type":"template",
                 "object_id":"auth-password-reset"}
                """)));
    }

    @Test
    void deniedWhenPermissionServiceReturns200False() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":false,\"reason\":\"no_tuple\"}")));

        AuthzClient client = client();
        var decision = client.check("subscriber", "9999", "can_receive", "template", "secret-template");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("no_tuple");
    }

    @Test
    void failClosedDenyOn401() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        AuthzClient client = client();
        var decision = client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("authz_http_401");
    }

    @Test
    void failClosedDenyOn500() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        AuthzClient client = client();
        var decision = client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("authz_http_500");
    }

    @Test
    void failClosedDenyOnConnectionRefused() {
        // Use unrouteable address (TEST-NET-1, RFC 5737 — guaranteed not reachable)
        AuthzClient client = new AuthzClient(new ObjectMapper(),
            "http://192.0.2.1:1", API_KEY);
        var decision = client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("authz_unreachable");
    }

    @Test
    void externalPrincipalShape() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        AuthzClient client = client();
        client.check("external", "abc123def", "can_receive", "template", "marketing-promo");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withRequestBody(equalToJson("""
                {"principal_type":"external","principal_id":"abc123def",
                 "relation":"can_receive","object_type":"template",
                 "object_id":"marketing-promo"}
                """)));
    }

    // Codex 019dfc3e PR-C Q2 absorb — traceparent sampled flag fix.

    @Test
    void traceparentHeaderAbsentWhenNoTracer() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        AuthzClient client = client();
        client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("traceparent", absent()));
    }

    @Test
    void traceparentHeaderAbsentWhenTracerHasNoCurrentSpan() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        Tracer tracer = Mockito.mock(Tracer.class);
        Mockito.when(tracer.currentSpan()).thenReturn(null);

        AuthzClient client = client();
        client.setTracer(tracer);
        client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("traceparent", absent()));
    }

    @Test
    void traceparentSampledFlag01WhenSpanSampled() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        // Simulate a sampled span
        TraceContext ctx = Mockito.mock(TraceContext.class);
        Mockito.when(ctx.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        Mockito.when(ctx.spanId()).thenReturn("b7ad6b7169203331");
        Mockito.when(ctx.sampled()).thenReturn(Boolean.TRUE);

        Span span = Mockito.mock(Span.class);
        Mockito.when(span.context()).thenReturn(ctx);

        Tracer tracer = Mockito.mock(Tracer.class);
        Mockito.when(tracer.currentSpan()).thenReturn(span);

        AuthzClient client = client();
        client.setTracer(tracer);
        client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("traceparent",
                equalTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")));
    }

    @Test
    void traceparentSampledFlag00WhenSpanNotSampled() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        TraceContext ctx = Mockito.mock(TraceContext.class);
        Mockito.when(ctx.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        Mockito.when(ctx.spanId()).thenReturn("b7ad6b7169203331");
        Mockito.when(ctx.sampled()).thenReturn(Boolean.FALSE);

        Span span = Mockito.mock(Span.class);
        Mockito.when(span.context()).thenReturn(ctx);

        Tracer tracer = Mockito.mock(Tracer.class);
        Mockito.when(tracer.currentSpan()).thenReturn(span);

        AuthzClient client = client();
        client.setTracer(tracer);
        client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("traceparent", matching("00-.*-00")));
    }

    @Test
    void traceparentSampledFlag00WhenSampledNull() {
        permissionService.stubFor(post(urlEqualTo("/api/v1/internal/authz/check"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"allowed\":true,\"reason\":\"tuple_match\"}")));

        TraceContext ctx = Mockito.mock(TraceContext.class);
        Mockito.when(ctx.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        Mockito.when(ctx.spanId()).thenReturn("b7ad6b7169203331");
        Mockito.when(ctx.sampled()).thenReturn(null);  // null = unknown → 00

        Span span = Mockito.mock(Span.class);
        Mockito.when(span.context()).thenReturn(ctx);

        Tracer tracer = Mockito.mock(Tracer.class);
        Mockito.when(tracer.currentSpan()).thenReturn(span);

        AuthzClient client = client();
        client.setTracer(tracer);
        client.check("subscriber", "1204", "can_receive", "template", "auth-password-reset");

        permissionService.verify(postRequestedFor(urlEqualTo("/api/v1/internal/authz/check"))
            .withHeader("traceparent", matching("00-.*-00")));
    }

    private AuthzClient client() {
        return new AuthzClient(new ObjectMapper(), permissionService.baseUrl(), API_KEY);
    }
}
