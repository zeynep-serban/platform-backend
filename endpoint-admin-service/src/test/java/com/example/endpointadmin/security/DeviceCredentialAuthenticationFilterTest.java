package com.example.endpointadmin.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceCredentialAuthenticationFilterTest {

    private final DeviceCredentialProvider provider = mock(DeviceCredentialProvider.class);
    private final DeviceCredentialAuthenticationFilter filter = new DeviceCredentialAuthenticationFilter(provider);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filterBuildsCanonicalPayloadAndAuthenticates() throws ServletException, IOException {
        Instant timestamp = Instant.parse("2026-04-28T10:00:00Z");
        String body = "{\"status\":\"ok\"}";
        String expectedCanonical = HmacSignatureSupport.canonicalPayload(
                "POST",
                "/api/v1/agent/heartbeat",
                "v=1",
                timestamp.toString(),
                "nonce-1",
                HmacSignatureSupport.sha256Hex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        when(provider.authenticate(argThat(request ->
                "edc-test".equals(request.keyId())
                        && "signature".equals(request.signature())
                        && timestamp.equals(request.requestTimestamp())
                        && "nonce-1".equals(request.nonce())
                        && expectedCanonical.equals(request.canonicalPayload())
        ))).thenReturn(new DeviceCredentialResult("device-1", "credential-1", timestamp));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/heartbeat");
        request.setQueryString("v=1");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader(DeviceCredentialAuthenticationFilter.CREDENTIAL_ID_HEADER, "edc-test");
        request.addHeader(DeviceCredentialAuthenticationFilter.TIMESTAMP_HEADER, timestamp.toString());
        request.addHeader(DeviceCredentialAuthenticationFilter.NONCE_HEADER, "nonce-1");
        request.addHeader(DeviceCredentialAuthenticationFilter.SIGNATURE_HEADER, "signature");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(provider).authenticate(argThat(req -> expectedCanonical.equals(req.canonicalPayload())));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void filterWritesUnauthorizedOnCredentialFailure() throws ServletException, IOException {
        when(provider.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DeviceCredentialException("DEVICE_SIGNATURE_INVALID", "Device request signature is invalid."));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/heartbeat");
        request.addHeader(DeviceCredentialAuthenticationFilter.CREDENTIAL_ID_HEADER, "edc-test");
        request.addHeader(DeviceCredentialAuthenticationFilter.TIMESTAMP_HEADER, Instant.now().toString());
        request.addHeader(DeviceCredentialAuthenticationFilter.NONCE_HEADER, "nonce-1");
        request.addHeader(DeviceCredentialAuthenticationFilter.SIGNATURE_HEADER, "bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("DEVICE_SIGNATURE_INVALID");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
