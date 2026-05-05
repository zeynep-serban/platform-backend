package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointEnrollmentDto;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointEnrollmentService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/endpoint-enrollments")
public class AdminEndpointEnrollmentController {

    private final EndpointEnrollmentService enrollmentService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointEnrollmentController(EndpointEnrollmentService enrollmentService,
                                             TenantContextResolver tenantContextResolver) {
        this.enrollmentService = enrollmentService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<CreateEndpointEnrollmentResponse> createEnrollment(
            @Valid @RequestBody(required = false) CreateEndpointEnrollmentRequest request) {
        CreateEndpointEnrollmentResponse response = enrollmentService.createEnrollment(
                tenantContextResolver.resolveRequired(),
                request == null ? new CreateEndpointEnrollmentRequest(null, null) : request
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<EndpointEnrollmentDto> listEnrollments() {
        return enrollmentService.listEnrollments(tenantContextResolver.resolveRequired());
    }
}
