package com.example.apigateway.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayRouteClassifierTest {

  private final GatewayRouteClassifier classifier = new GatewayRouteClassifier();

  @Test
  void classifiesAuthCookieRefreshSpecifically() {
    assertThat(classifier.classify("/api/auth/cookie/refresh")).isEqualTo("auth_cookie_refresh");
  }

  @Test
  void classifiesAuthCookieRoot() {
    assertThat(classifier.classify("/api/auth/cookie")).isEqualTo("auth_cookie");
  }

  @Test
  void classifiesAuthSessions() {
    assertThat(classifier.classify("/api/v1/auth/sessions")).isEqualTo("auth_sessions");
  }

  @Test
  void classifiesAuthzMe() {
    assertThat(classifier.classify("/api/v1/authz/me")).isEqualTo("authz_me");
  }

  @Test
  void classifiesUsersWithIdLeaf() {
    assertThat(classifier.classify("/api/v1/users/123")).isEqualTo("users");
    assertThat(classifier.classify("/api/users")).isEqualTo("users");
    assertThat(classifier.classify("/users")).isEqualTo("users");
  }

  @Test
  void classifiesReports() {
    assertThat(classifier.classify("/api/v1/reports/metadata")).isEqualTo("reports");
    assertThat(classifier.classify("/reports/list")).isEqualTo("reports");
  }

  @Test
  void classifiesUnknownPaths() {
    assertThat(classifier.classify("/random/path")).isEqualTo("unknown");
    assertThat(classifier.classify("")).isEqualTo("unknown");
    assertThat(classifier.classify(null)).isEqualTo("unknown");
  }

  @Test
  void noUuidLeak() {
    // Cardinality guard: even with a UUID-looking id, the classifier
    // must collapse to the bounded enum value
    String path = "/api/v1/users/8f14e45f-ceea-467a-a3a5-a4bdf90ad8cd";
    assertThat(classifier.classify(path)).isEqualTo("users");
  }

  @Test
  void noSlugLeak() {
    String path = "/api/v1/reports/quarterly-revenue-summary-2026";
    assertThat(classifier.classify(path)).isEqualTo("reports");
  }

  @Test
  void preservesAuthCookieRefreshOverRoot() {
    // Order matters: refresh path must NOT be classified as root
    assertThat(classifier.classify("/api/auth/cookie/refresh")).isEqualTo("auth_cookie_refresh");
    assertThat(classifier.classify("/api/auth/cookie")).isEqualTo("auth_cookie");
  }

  @Test
  void classifiesAuthMetadata() {
    assertThat(classifier.classify("/silent-check-sso.html")).isEqualTo("auth_meta");
    assertThat(classifier.classify("/.well-known/openid-configuration")).isEqualTo("auth_meta");
  }
}
