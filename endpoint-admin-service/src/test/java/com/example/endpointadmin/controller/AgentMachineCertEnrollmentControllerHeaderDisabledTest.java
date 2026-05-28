package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.3 — Codex 019e6dc9 P0-B trust-boundary assertion: when the
 * forwarded-header mode is DISABLED (the default outside the k8s profile),
 * a caller hitting the service directly with an {@code X-Client-Cert}
 * header MUST be rejected with 401 — even if the header carries a
 * structurally valid PEM. This prevents direct-service-access spoofing.
 */
@WebMvcTest(AgentMachineCertEnrollmentController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = "endpoint-admin.mtls.forward-header.enabled=false")
class AgentMachineCertEnrollmentControllerHeaderDisabledTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private MachineCertAutoEnrollService service;

    private static String requestBody() {
        return """
                {
                  "machineFingerprint": "fp-spoof",
                  "hostname": "DESKTOP-SPOOF",
                  "osName": "windows",
                  "agentVersion": "0.1.0"
                }
                """;
    }

    @Test
    void forwardedHeaderRejectedWhenModeDisabled() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String urlEncoded = java.net.URLEncoder.encode(pem, StandardCharsets.UTF_8);

        // No servlet attribute (mTLS not direct), only the header — and the
        // mode is OFF. Must surface MTLS_CERT_MISSING (401).
        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, urlEncoded)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }
}
