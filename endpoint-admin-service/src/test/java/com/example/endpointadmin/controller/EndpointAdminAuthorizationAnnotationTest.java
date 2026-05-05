package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointAdminAuthorizationAnnotationTest {

    @Test
    void readOnlyAdminControllersRequireViewerRelationAtClassLevel() {
        assertClassRequires(AdminEndpointDeviceController.class, EndpointAdminAuthz.VIEWER);
        assertClassRequires(AdminEndpointAuditController.class, EndpointAdminAuthz.VIEWER);
    }

    @Test
    void enrollmentControllerSeparatesReadAndWriteRelations() throws Exception {
        assertMethodRequires(AdminEndpointEnrollmentController.class, "createEnrollment", EndpointAdminAuthz.MANAGER);
        assertMethodRequires(AdminEndpointEnrollmentController.class, "listEnrollments", EndpointAdminAuthz.VIEWER);
    }

    @Test
    void commandControllerSeparatesReadAndWriteRelations() {
        for (Method method : AdminEndpointCommandController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.MANAGER);
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    @Test
    void maintenanceTokenControllerSeparatesReadAndWriteRelations() {
        for (Method method : AdminMaintenanceTokenController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(DeleteMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.MANAGER);
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    private void assertClassRequires(Class<?> controllerClass, String relation) {
        RequireModule annotation = controllerClass.getAnnotation(RequireModule.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(EndpointAdminAuthz.MODULE);
        assertThat(annotation.relation()).isEqualTo(relation);
    }

    private void assertMethodRequires(Class<?> controllerClass, String methodName, String relation) {
        Method method = java.util.Arrays.stream(controllerClass.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
        assertMethodRequires(method, relation);
    }

    private void assertMethodRequires(Method method, String relation) {
        RequireModule annotation = method.getAnnotation(RequireModule.class);
        assertThat(annotation)
                .as("%s must declare @RequireModule", method.getName())
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(EndpointAdminAuthz.MODULE);
        assertThat(annotation.relation()).isEqualTo(relation);
    }
}
