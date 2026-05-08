package com.example.apigateway.telemetry;

import org.springframework.stereotype.Component;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2 absorb): bounded
 * route-group classifier for gateway auth telemetry. Maps an incoming
 * request path to a low-cardinality enum value used as a Micrometer
 * counter tag.
 *
 * <p>Cardinality contract: the returned set is finite and known at
 * compile time. URL ids, slugs, query strings, and tenant variants
 * all collapse into a single bucket. {@code unknown} is the catch-all
 * for paths that don't match any classifier branch.
 *
 * <p>Path family coverage:
 * <ul>
 *   <li>K8s prod (gateway routed via Spring Cloud Gateway): {@code /users},
 *       {@code /reports}, {@code /schemas}, {@code /notify}, etc.</li>
 *   <li>Local/v1 (legacy + new): {@code /api/users}, {@code /api/v1/users},
 *       {@code /api/v1/reports}, etc.</li>
 *   <li>Auth surface: {@code /api/auth/cookie} root + refresh,
 *       {@code /api/v1/auth/sessions}, {@code /api/v1/authz/me}</li>
 *   <li>Auth metadata: {@code /silent-check-sso.html}, {@code /.well-known/...}</li>
 * </ul>
 */
@Component
public class GatewayRouteClassifier {

  /**
   * Classify the given path into a bounded route group enum value.
   * Never returns {@code null}; unknown paths return {@code "unknown"}.
   *
   * <p>Order matters: more specific patterns are checked first so
   * {@code /api/auth/cookie/refresh} doesn't collapse into the
   * {@code /api/auth/cookie} root bucket.
   */
  public String classify(String path) {
    if (path == null || path.isEmpty()) {
      return "unknown";
    }

    // Auth surface (specific before general)
    if (path.equals("/api/auth/cookie/refresh")) {
      return "auth_cookie_refresh";
    }
    if (path.equals("/api/auth/cookie")) {
      return "auth_cookie";
    }
    if (path.startsWith("/api/v1/auth/sessions")) {
      return "auth_sessions";
    }
    if (path.startsWith("/api/v1/authz/me")) {
      return "authz_me";
    }

    // Domain endpoints (K8s prod + local/v1 alias)
    if (matchesGroup(path, "users")) {
      return "users";
    }
    if (matchesGroup(path, "reports")) {
      return "reports";
    }
    if (matchesGroup(path, "schemas")) {
      return "schemas";
    }
    if (matchesGroup(path, "notify")) {
      return "notify";
    }
    if (matchesGroup(path, "theme-registry")) {
      return "theme_registry";
    }
    if (matchesGroup(path, "audit")) {
      return "audit";
    }
    if (matchesGroup(path, "access")) {
      return "access";
    }
    if (matchesGroup(path, "variants")) {
      return "variants";
    }
    if (matchesGroup(path, "admin")) {
      return "admin";
    }

    // Auth metadata
    if (path.startsWith("/silent-check-sso") || path.startsWith("/.well-known")) {
      return "auth_meta";
    }

    return "unknown";
  }

  /**
   * Path-boundary-safe match for both K8s prod ({@code /<group>}) and
   * local/v1 ({@code /api/<group>} or {@code /api/v1/<group>})
   * variants. Codex iter-3 P2 #6 absorb: prevents
   * {@code /usersx} from being misclassified as {@code users}.
   */
  private static boolean matchesGroup(String path, String group) {
    String slashGroup = "/" + group;
    String slashGroupSlash = slashGroup + "/";
    String apiSlashGroup = "/api" + slashGroup;
    String apiSlashGroupSlash = "/api" + slashGroupSlash;
    String apiV1SlashGroup = "/api/v1" + slashGroup;
    String apiV1SlashGroupSlash = "/api/v1" + slashGroupSlash;
    return path.equals(slashGroup)
        || path.startsWith(slashGroupSlash)
        || path.equals(apiSlashGroup)
        || path.startsWith(apiSlashGroupSlash)
        || path.equals(apiV1SlashGroup)
        || path.startsWith(apiV1SlashGroupSlash);
  }
}
