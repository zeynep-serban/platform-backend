package com.example.apigateway;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Faz 23.5+ PR-Notify-1 (2026-05-07) — gateway route contract for the
 * {@code /api/v1/notify/**} surface owned by {@code notification-orchestrator}.
 *
 * <p>Live evidence (testai.acik.com 2026-05-07): cold-reload after login
 * surfaced 404 on {@code GET /api/v1/notify/inbox/me?page=0&size=20}. Root
 * cause: the local-profile gateway routes table did not carry a
 * {@code /api/v1/notify/**} entry, so the gateway returned 404 NotFound
 * before the {@code notification-orchestrator} pod was even reachable.
 * The k8s overlay configmap had {@code SPRING_CLOUD_GATEWAY_ROUTES_20_*}
 * already (gitops {@code kustomize/base/apps/api-gateway/configmap.yaml});
 * this contract test guards the local-profile parity and prevents a
 * future regression where the notify route silently disappears from
 * {@code application.properties}.
 *
 * <p>Why this test exists: route table drift is normally only caught at
 * the integration / live-cluster level (the bug we just fixed lay
 * dormant locally). A property-file content assertion makes the surface
 * explicit so any rename / removal forces an intentional decision.
 *
 * <p>Approach: parse {@code application.properties} from the test
 * classpath and walk the {@code spring.cloud.gateway.server.webflux.routes[N]}
 * indices. The notify route must point at {@code lb://NOTIFICATION-ORCHESTRATOR}
 * with {@code Path=/api/v1/notify/**} predicate. The ApplicationContext
 * is intentionally NOT loaded — runtime route resolution requires a
 * discovery service or downstream stub which adds infrastructure that
 * this lightweight presence guard does not need (the full HTTP
 * status-code matrix lives in {@link GatewayStatusCodeMatrixTest}).
 */
class NotifyRouteContractTest {

    private static final String ROUTES_PREFIX = "spring.cloud.gateway.server.webflux.routes";

    @Test
    void notify_route_is_declared_in_application_properties() {
        Properties props = loadApplicationProperties();
        List<RouteSnapshot> routes = collectRoutes(props);

        // Sanity check — at least one canonical route must be declared
        // (auth-service-route is routes[0], the oldest entry). If the
        // property file is empty, every other assertion below would be
        // vacuously true so we guard with a precondition.
        assertThat(routes)
                .as("application.properties must declare at least one gateway route")
                .isNotEmpty();

        // Codex iter-1 PARTIAL absorb (thread 019e0423 §1): exact predicate
        // match on `Path=/api/v1/notify/**`. The earlier `contains` check
        // would let a narrower predicate (e.g. `Path=/api/v1/notify/inbox/**`)
        // silently pass — that would still produce 404 on
        // /api/v1/notify/preferences/me. Pin the exact contract.
        var notifyRoutes = routes.stream()
                .filter(NotifyRouteContractTest::predicateMatchesNotifyContract)
                .toList();

        assertThat(notifyRoutes)
                .as("exactly one route with predicate Path=/api/v1/notify/** should exist; "
                        + "current routes: %s",
                        routes.stream().map(NotifyRouteContractTest::summarize).toList())
                .hasSize(1);

        RouteSnapshot notify = notifyRoutes.get(0);

        // Route id is the contract anchor across logs / Prometheus
        // gateway_requests metric labels. Pin it.
        assertThat(notify.id)
                .as("notify route id must be `notification-orchestrator-v1-route`")
                .isEqualTo("notification-orchestrator-v1-route");

        // URI must point at notification-orchestrator (case-insensitive — local
        // profile uses `lb://NOTIFICATION-ORCHESTRATOR`, k8s overlay overrides
        // via configmap to `http://notification-orchestrator:8089`).
        assertThat(notify.uri)
                .as("notify route URI must not be null (route id %s)", notify.id)
                .isNotNull();
        assertThat(notify.uri.toLowerCase(Locale.ROOT))
                .as("notify route URI should reference notification-orchestrator: %s", notify.uri)
                .contains("notification-orchestrator");
    }

    /**
     * Exact contract match: the predicate must declare {@code Path=} with
     * {@code /api/v1/notify/**} as one of its patterns. Spring Cloud Gateway
     * stores Path predicates as the literal string
     * {@code Path=/api/v1/notify/**} in {@code predicates[i]}, with multiple
     * patterns comma-joined. We split on commas, strip the {@code Path=}
     * prefix from the first chunk, and look for {@code /api/v1/notify/**}
     * verbatim. A narrower predicate (e.g. {@code Path=/api/v1/notify/inbox/**})
     * does NOT satisfy this contract — the route must accept the full
     * notify surface ({@code /preferences/me}, {@code /inbox/me/stream},
     * etc.).
     */
    private static boolean predicateMatchesNotifyContract(RouteSnapshot route) {
        String predicate = route.predicate;
        if (predicate == null || !predicate.startsWith("Path=")) return false;
        String patterns = predicate.substring("Path=".length());
        for (String pattern : patterns.split(",")) {
            if (pattern.trim().equals("/api/v1/notify/**")) return true;
        }
        return false;
    }

    private static String summarize(RouteSnapshot r) {
        return (r.id == null ? "<null>" : r.id) + "/" + (r.predicate == null ? "<null>" : r.predicate);
    }

    private static Properties loadApplicationProperties() {
        // Load the *main* resource (src/main/resources/application.properties)
        // explicitly. The test classpath also has src/test/resources/
        // application.properties (a stripped-down test override) — that file
        // would shadow the main one if we used getResourceAsStream("/...").
        Properties props = new Properties();
        java.nio.file.Path mainProps =
                java.nio.file.Paths.get("src", "main", "resources", "application.properties");
        if (!java.nio.file.Files.exists(mainProps)) {
            // Fallback for IDE / parent-directory test runner: walk up to module.
            java.nio.file.Path apiGateway =
                    java.nio.file.Paths.get("api-gateway", "src", "main", "resources", "application.properties");
            if (java.nio.file.Files.exists(apiGateway)) {
                mainProps = apiGateway;
            } else {
                fail("application.properties not found at " + mainProps.toAbsolutePath()
                        + " or " + apiGateway.toAbsolutePath());
            }
        }
        try (InputStream is = java.nio.file.Files.newInputStream(mainProps)) {
            props.load(is);
        } catch (Exception e) {
            fail("failed to load application.properties: " + e.getMessage());
        }
        return props;
    }

    private static List<RouteSnapshot> collectRoutes(Properties props) {
        List<RouteSnapshot> result = new ArrayList<>();
        // Routes are sparsely indexed in this file (audit overrides land
        // at 16/17/18 with explicit `order=-1`); walk a generous range
        // and pick up any non-empty entry.
        for (int i = 0; i < 64; i++) {
            String id = props.getProperty(ROUTES_PREFIX + "[" + i + "].id");
            String uri = props.getProperty(ROUTES_PREFIX + "[" + i + "].uri");
            String predicate = props.getProperty(ROUTES_PREFIX + "[" + i + "].predicates[0]");
            if (id == null && uri == null && predicate == null) {
                continue;
            }
            result.add(new RouteSnapshot(id, uri, predicate));
        }
        return result;
    }

    private record RouteSnapshot(String id, String uri, String predicate) {}
}
