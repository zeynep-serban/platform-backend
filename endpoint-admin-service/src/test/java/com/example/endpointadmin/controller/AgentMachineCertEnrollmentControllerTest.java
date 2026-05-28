package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentResponse;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.3 — Controller-layer test for the mTLS auto-enroll endpoint.
 */
@WebMvcTest(AgentMachineCertEnrollmentController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@org.springframework.test.context.TestPropertySource(properties =
        "endpoint-admin.mtls.forward-header.enabled=true")
class AgentMachineCertEnrollmentControllerTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService service;

    private static RequestPostProcessor withClientCert(X509Certificate cert) {
        return request -> {
            request.setAttribute(
                    AgentMachineCertEnrollmentController.CERT_REQUEST_ATTRIBUTE,
                    new X509Certificate[]{cert});
            return request;
        };
    }

    private static String requestBody() {
        return """
                {
                  "machineFingerprint": "fp-001",
                  "hostname": "DESKTOP-ABC123",
                  "osName": "windows",
                  "osVersion": "10.0.26200",
                  "osBuild": "26200",
                  "domain": "acik.local",
                  "architecture": "amd64",
                  "agentVersion": "0.1.0",
                  "schemaVersion": 1
                }
                """;
    }

    @Test
    void firstEnrollmentReturns201() throws Exception {
        UUID guid = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);
        Instant now = Instant.parse("2026-05-28T10:00:00Z");

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenReturn(new MachineCertAutoEnrollService.Outcome(
                        HttpStatus.CREATED,
                        new AutoEnrollmentResponse(
                                deviceId,
                                "enrolled",
                                now,
                                new AutoEnrollmentResponse.CertInfo(
                                        "adcomputer:" + guid.toString().toLowerCase(),
                                        guid,
                                        "abc123",
                                        Instant.parse("2027-05-28T10:00:00Z")
                                )
                        )
                ));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.status").value("enrolled"))
                .andExpect(jsonPath("$.certInfo.sanUri")
                        .value("adcomputer:" + guid.toString().toLowerCase()));
    }

    @Test
    void idempotentRepeatReturns200() throws Exception {
        UUID guid = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);
        Instant enrolledAt = Instant.parse("2026-05-28T09:00:00Z");

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenReturn(new MachineCertAutoEnrollService.Outcome(
                        HttpStatus.OK,
                        new AutoEnrollmentResponse(
                                deviceId,
                                "already-enrolled",
                                enrolledAt,
                                new AutoEnrollmentResponse.CertInfo(
                                        "adcomputer:" + guid.toString().toLowerCase(),
                                        guid,
                                        "abc123",
                                        Instant.parse("2027-05-28T10:00:00Z")
                                )
                        )
                ));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already-enrolled"))
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()));
    }

    @Test
    void missingCertReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingTenantHeaderReturns400() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTenantHeaderReturns400() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, "not-a-uuid")
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationFailsOnMissingFingerprint() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        String bodyMissingFp = """
                {
                  "hostname": "DESKTOP-ABC",
                  "osName": "windows",
                  "agentVersion": "0.1.0"
                }
                """;

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(bodyMissingFp)
                        .with(withClientCert(cert)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void certInvalidPropagatesAs401() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CERT_EXPIRED"));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fingerprintConflictPropagatesAs409() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "FINGERPRINT_CONFLICT"));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTenantPropagatesAs403() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_BOUNDARY"));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .content(requestBody())
                        .with(withClientCert(cert)))
                .andExpect(status().isForbidden());
    }

    /**
     * Codex 019e6dc9 P0-3 absorb: cert presented via the
     * {@code X-Client-Cert} gateway-forwarded PEM header (NGINX /
     * Envoy style) MUST be accepted equivalently to the servlet attribute.
     */
    @Test
    void enrollViaGatewayForwardedPemHeaderReturns201() throws Exception {
        UUID guid = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);
        Instant now = Instant.parse("2026-05-28T10:00:00Z");

        when(service.autoEnroll(any(), eq(TENANT_A), any()))
                .thenReturn(new MachineCertAutoEnrollService.Outcome(
                        HttpStatus.CREATED,
                        new AutoEnrollmentResponse(
                                deviceId,
                                "enrolled",
                                now,
                                new AutoEnrollmentResponse.CertInfo(
                                        "adcomputer:" + guid.toString().toLowerCase(),
                                        guid,
                                        "abc123",
                                        Instant.parse("2027-05-28T10:00:00Z")
                                )
                        )
                ));

        // PEM encoding of the test cert, URL-encoded as NGINX
        // ssl_client_escaped_cert would produce.
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String urlEncoded = java.net.URLEncoder.encode(pem, java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, urlEncoded)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("enrolled"));
    }

    @Test
    void malformedForwardedPemHeaderReturns401() throws Exception {
        // Garbage that survives URL-decoding but does not parse as PEM.
        String junk = "%2D%2D%2D%2DGARBAGE%2D%2D%2D%2D";

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, junk)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }
}
