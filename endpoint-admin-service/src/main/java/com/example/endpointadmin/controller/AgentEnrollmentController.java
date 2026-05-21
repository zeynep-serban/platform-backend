package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentResponse;
import com.example.endpointadmin.service.EndpointEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/enrollments")
public class AgentEnrollmentController {

    private final EndpointEnrollmentService enrollmentService;

    public AgentEnrollmentController(EndpointEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/consume")
    public ResponseEntity<ConsumeEnrollmentResponse> consumeEnrollment(
            @Valid @RequestBody ConsumeEnrollmentRequest request,
            HttpServletRequest servletRequest) {
        ConsumeEnrollmentResponse response = enrollmentService.consumeEnrollment(
                request,
                resolveRemoteAddress(servletRequest)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private String resolveRemoteAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
