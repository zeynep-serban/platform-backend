package com.example.endpointadmin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public class DeviceCredentialAuthenticationFilter extends OncePerRequestFilter {

    public static final String CREDENTIAL_ID_HEADER = "X-Device-Credential-Id";
    public static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
    public static final String NONCE_HEADER = "X-Request-Nonce";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private final DeviceCredentialProvider credentialProvider;

    public DeviceCredentialAuthenticationFilter(DeviceCredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || !path.startsWith("/api/v1/agent/")
                || "/api/v1/agent/enrollments/consume".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        try {
            DeviceCredentialRequest credentialRequest = buildCredentialRequest(wrappedRequest);
            DeviceCredentialResult result = credentialProvider.authenticate(credentialRequest);
            DeviceCredentialAuthenticationToken authentication = DeviceCredentialAuthenticationToken.authenticated(result);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(wrappedRequest));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(wrappedRequest, response);
        } catch (DeviceCredentialException ex) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, ex.getErrorCode(), ex.getMessage());
        }
    }

    private DeviceCredentialRequest buildCredentialRequest(CachedBodyHttpServletRequest request) {
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String bodyHash = HmacSignatureSupport.sha256Hex(request.getBody());
        String canonicalPayload = HmacSignatureSupport.canonicalPayload(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                timestamp,
                nonce,
                bodyHash
        );
        return new DeviceCredentialRequest(
                null,
                request.getHeader(CREDENTIAL_ID_HEADER),
                request.getHeader(SIGNATURE_HEADER),
                parseTimestamp(timestamp),
                nonce,
                canonicalPayload
        );
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ex) {
            throw new DeviceCredentialException("DEVICE_TIMESTAMP_INVALID", "Device request timestamp is invalid.");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String json = "{\"error\":\"" + jsonEscape(errorCode) + "\",\"message\":\"" + jsonEscape(message) + "\"}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
