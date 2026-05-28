package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentResponse;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * Faz 22.3 — Backend mTLS self-enrollment endpoint.
 *
 * <p>Codex 019e6dc9 P0-3 absorb. Two cert-presentation modes are supported,
 * chosen by where mTLS terminates:
 *
 * <ol>
 *   <li><b>Backend direct mTLS</b> (Tomcat {@code server.ssl.client-auth=need}
 *       or similar): the servlet container sets
 *       {@code jakarta.servlet.request.X509Certificate} after chain
 *       validation.</li>
 *   <li><b>Gateway-forwarded PEM header</b>: the upstream gateway / ingress
 *       (NGINX {@code ssl_client_escaped_cert}, Envoy
 *       {@code forward_client_cert_details}) terminates mTLS, validates the
 *       chain, and forwards the PEM in the {@code X-Client-Cert} header
 *       (URL-encoded so newlines survive). The backend re-parses the PEM —
 *       chain validation is NOT repeated; the gateway is the trusted
 *       terminator. The gateway MUST strip any inbound {@code X-Client-Cert}
 *       so an unauthenticated client cannot inject a header.</li>
 * </ol>
 *
 * <p>Tenant resolution (Codex 019e6dc9 P1-4 absorb): {@code X-Tenant-Id}
 * header is REQUIRED. There is no default-tenant fallback — a missing or
 * blank header always produces {@code 400 TENANT_HEADER_REQUIRED}. The
 * gateway is responsible for stripping any client-supplied tenant header
 * and injecting its own validated value.
 */
@RestController
@RequestMapping("/api/v1/endpoint-agent/endpoint-enrollments")
public class AgentMachineCertEnrollmentController {

    private static final Logger log = LoggerFactory.getLogger(AgentMachineCertEnrollmentController.class);

    public static final String CERT_REQUEST_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    public static final String CERT_FORWARD_HEADER = "X-Client-Cert";
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final MachineCertAutoEnrollService service;
    private final boolean forwardHeaderEnabled;

    /**
     * @param forwardHeaderEnabled when {@code false} (default) the controller
     *                             only accepts certs from the servlet
     *                             attribute set by a directly-terminating
     *                             TLS layer (Tomcat / Spring Boot SSL). When
     *                             {@code true} the controller also accepts a
     *                             gateway-forwarded PEM via
     *                             {@link #CERT_FORWARD_HEADER}. Enable this
     *                             only in deployments where an upstream
     *                             gateway TERMINATES mTLS, VALIDATES the
     *                             chain, and STRIPS any client-supplied
     *                             {@code X-Client-Cert} before reaching this
     *                             service. Codex 019e6dc9 P0-B absorb: a
     *                             default-on header mode would let any
     *                             caller with raw service access spoof an
     *                             enrollment by injecting their own PEM.
     */
    public AgentMachineCertEnrollmentController(
            MachineCertAutoEnrollService service,
            @Value("${endpoint-admin.mtls.forward-header.enabled:false}") boolean forwardHeaderEnabled) {
        this.service = service;
        this.forwardHeaderEnabled = forwardHeaderEnabled;
        if (forwardHeaderEnabled) {
            log.info("X-Client-Cert forwarded-header mode ENABLED. "
                    + "Upstream gateway MUST validate the cert chain and strip "
                    + "any client-supplied X-Client-Cert / X-Tenant-Id before "
                    + "this service receives the request.");
        }
    }

    @PostMapping("/auto")
    public ResponseEntity<AutoEnrollmentResponse> autoEnroll(
            @Valid @RequestBody AutoEnrollmentRequest request,
            HttpServletRequest servletRequest) {
        X509Certificate cert = resolveClientCert(servletRequest);
        UUID tenantId = resolveTenantId(servletRequest);

        MachineCertAutoEnrollService.Outcome outcome = service.autoEnroll(cert, tenantId, request);

        return ResponseEntity.status(outcome.status())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(outcome.body());
    }

    /**
     * Resolve the X.509 client cert from either the standard servlet
     * attribute (backend direct mTLS) or the {@code X-Client-Cert}
     * gateway-forwarded PEM header. See class javadoc.
     */
    private X509Certificate resolveClientCert(HttpServletRequest request) {
        Object attr = request.getAttribute(CERT_REQUEST_ATTRIBUTE);
        if (attr instanceof X509Certificate[] arr && arr.length > 0) {
            return arr[0];
        }
        if (attr instanceof X509Certificate single) {
            return single;
        }

        // Codex 019e6dc9 P0-B absorb: forwarded-header mode is config-gated.
        // Default OFF — a caller with direct service access cannot spoof an
        // enrollment by injecting an X-Client-Cert header. Only deployments
        // that explicitly trust the upstream gateway flip the flag in their
        // profile config.
        if (forwardHeaderEnabled) {
            String pemHeader = request.getHeader(CERT_FORWARD_HEADER);
            if (pemHeader != null && !pemHeader.isBlank()) {
                return parseForwardedPem(pemHeader);
            }
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MTLS_CERT_MISSING");
    }

    /**
     * Parse an NGINX/Envoy-style URL-encoded PEM from {@code X-Client-Cert}.
     * Chain validation is the gateway's job; here we only parse the bytes.
     */
    private X509Certificate parseForwardedPem(String pemHeader) {
        try {
            String pem = URLDecoder.decode(pemHeader, StandardCharsets.UTF_8);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream in = new ByteArrayInputStream(
                    pem.getBytes(StandardCharsets.UTF_8))) {
                return (X509Certificate) cf.generateCertificate(in);
            }
        } catch (CertificateException | java.io.IOException ex) {
            log.warn("Failed to parse forwarded X-Client-Cert header: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MTLS_CERT_FORWARD_INVALID");
        }
    }

    private UUID resolveTenantId(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_REQUIRED");
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_INVALID");
        }
    }
}
