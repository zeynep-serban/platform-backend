package com.serban.notify.authz;

import com.serban.notify.config.NotifyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DpoAuthzService unit tests (Faz 23.2.B PR-K6 — Codex thread
 * {@code 019e59ea} iter-3 AGREE absorb).
 *
 * <p>Verifies the fail-closed contract:
 * <ul>
 *   <li>flag=false ⇒ short-circuit allow (legacy bypass)</li>
 *   <li>flag=true + null/blank userId ⇒ deny without HTTP call
 *       (prevents Map.of(...) NPE in AuthzClient)</li>
 *   <li>flag=true + null/blank orgId ⇒ deny defensively</li>
 *   <li>flag=true + AuthzClient returns allow ⇒ allow</li>
 *   <li>flag=true + AuthzClient returns deny ⇒ deny</li>
 *   <li>flag=true + AuthzClient unreachable (deny with reason) ⇒ deny</li>
 * </ul>
 */
class DpoAuthzServiceTest {

    private final AuthzClient authzClient = mock(AuthzClient.class);

    private DpoAuthzService service(boolean enabled) {
        NotifyConfig config = config(enabled);
        return new DpoAuthzService(authzClient, config);
    }

    private NotifyConfig config(boolean enabled) {
        return new NotifyConfig(
            new NotifyConfig.DispatchConfig(false),
            new NotifyConfig.IntakeConfig(10000),
            new NotifyConfig.IdempotencyConfig(24),
            new NotifyConfig.DedupeConfig(5),
            new NotifyConfig.RetryConfig(5, 30000L, 2.5d, 3600000L, 0.25d),
            new NotifyConfig.AuditConfig(90, false, "0 0 2 * * *", 24, false, 3, true),
            new NotifyConfig.RedactionConfig("test-pepper"),
            new NotifyConfig.WorkerConfig(5000L, 25, 50, 60000L, ""),
            new NotifyConfig.SecurityConfig("default",
                List.of("subscriberId", "userId", "sub"), false),
            new NotifyConfig.KvkkConfig(enabled, List.of("userId", "uid"))
        );
    }

    @Test
    void flagOff_returnsTrueWithoutCallingAuthzClient() {
        DpoAuthzService s = service(false);

        boolean result = s.canEraseForOrg("1204", "org-X");

        assertThat(result).isTrue();
        verify(authzClient, never()).check(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        assertThat(s.isEnabled()).isFalse();
    }

    @Test
    void flagOn_nullUserId_returnsFalseWithoutHttpCall() {
        DpoAuthzService s = service(true);

        boolean result = s.canEraseForOrg(null, "org-X");

        assertThat(result).isFalse();
        verify(authzClient, never()).check(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void flagOn_blankUserId_returnsFalseWithoutHttpCall() {
        DpoAuthzService s = service(true);

        boolean result = s.canEraseForOrg("   ", "org-X");

        assertThat(result).isFalse();
        verify(authzClient, never()).check(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void flagOn_nullOrgId_returnsFalseWithoutHttpCall() {
        DpoAuthzService s = service(true);

        boolean result = s.canEraseForOrg("1204", null);

        assertThat(result).isFalse();
        verify(authzClient, never()).check(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void flagOn_blankOrgId_returnsFalseWithoutHttpCall() {
        DpoAuthzService s = service(true);

        boolean result = s.canEraseForOrg("1204", "  ");

        assertThat(result).isFalse();
        // Codex post-impl review 019e59ea: symmetry with other null/blank
        // tests — fail-closed contract must not reach the HTTP client.
        verify(authzClient, never()).check(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void flagOn_authzClientAllows_returnsTrue() {
        when(authzClient.check(
            eq("user"), eq("1204"), eq("can_erasure"),
            eq("organization"), eq("org-X")
        )).thenReturn(new AuthzClient.AuthzDecision(true, "tuple_match"));

        DpoAuthzService s = service(true);
        boolean result = s.canEraseForOrg("1204", "org-X");

        assertThat(result).isTrue();
        assertThat(s.isEnabled()).isTrue();
    }

    @Test
    void flagOn_authzClientDenies_returnsFalse() {
        when(authzClient.check(
            eq("user"), eq("9999"), eq("can_erasure"),
            eq("organization"), eq("org-X")
        )).thenReturn(new AuthzClient.AuthzDecision(false, "no_tuple"));

        DpoAuthzService s = service(true);
        boolean result = s.canEraseForOrg("9999", "org-X");

        assertThat(result).isFalse();
    }

    @Test
    void flagOn_authzUnreachable_returnsFalse() {
        // AuthzClient fail-closed contract: HTTP error / timeout →
        // deny("authz_unreachable"). DpoAuthzService inherits.
        when(authzClient.check(
            eq("user"), eq("1204"), eq("can_erasure"),
            eq("organization"), eq("org-X")
        )).thenReturn(AuthzClient.AuthzDecision.deny("authz_unreachable"));

        DpoAuthzService s = service(true);
        boolean result = s.canEraseForOrg("1204", "org-X");

        assertThat(result).isFalse();
    }

    @Test
    void flagOn_authzHttpError_returnsFalse() {
        // Symmetric to unreachable: any HTTP non-200 from
        // permission-service maps to deny.
        when(authzClient.check(
            eq("user"), eq("1204"), eq("can_erasure"),
            eq("organization"), eq("org-X")
        )).thenReturn(AuthzClient.AuthzDecision.deny("authz_http_500"));

        DpoAuthzService s = service(true);
        boolean result = s.canEraseForOrg("1204", "org-X");

        assertThat(result).isFalse();
    }
}
