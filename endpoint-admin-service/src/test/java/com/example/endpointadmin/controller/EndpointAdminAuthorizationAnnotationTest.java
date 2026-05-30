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

    /**
     * BE-020 PR-B (Faz 22.5.3): every approved-software-catalog admin route
     * must reuse the existing {@code module:endpoint-admin} RBAC, not open
     * a new scope (Codex 019e6a3e iter-2 acceptance #3). GET → VIEWER,
     * POST / PUT → MANAGER.
     */
    @Test
    void softwareCatalogControllerSeparatesReadAndWriteRelations() {
        for (Method method :
                AdminEndpointSoftwareCatalogController.class
                        .getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(
                            org.springframework.web.bind.annotation.PutMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.MANAGER);
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    /**
     * BE-020I (Faz 22.5.3A): software inventory admin GET routes reuse the
     * existing {@code module:endpoint-admin} VIEWER relation; no new RBAC
     * scope opens (Codex 019e6ab2 iter-2 acceptance). The agent ingest
     * path uses the existing HMAC {@code DeviceCredentialAuthenticationFilter}
     * and has no {@code @RequireModule} annotation.
     */
    @Test
    void softwareInventoryControllerOnlyExposesViewerGetRoutes() {
        for (Method method :
                AdminEndpointSoftwareInventoryController.class
                        .getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    /**
     * BE-022Q (Faz 22.5.2 hardware query API): hardware inventory admin GET
     * routes reuse the existing {@code module:endpoint-admin} VIEWER
     * relation; no new RBAC scope opens (Codex 019e70c1 plan-time AGREE +
     * post-impl must-fix #6 reflection coverage). The agent ingest path
     * is unchanged (it lives in {@code EndpointAgentCommandService}, not
     * a dedicated controller).
     */
    @Test
    void hardwareInventoryControllerOnlyExposesViewerGetRoutes() {
        for (Method method :
                AdminEndpointHardwareInventoryController.class
                        .getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    /**
     * BE device-health (Faz 22.5, AG-033 query API): device-health admin
     * GET routes ({@code /device-health/latest} + {@code /history}) reuse
     * the existing {@code module:endpoint-admin} VIEWER relation; no new
     * RBAC scope opens (mirrors the BE-022Q hardware-inventory contract).
     * The agent ingest path is unchanged (it lives in
     * {@code EndpointAgentCommandService}, not a dedicated controller).
     */
    @Test
    void deviceHealthControllerOnlyExposesViewerGetRoutes() {
        for (Method method :
                AdminEndpointDeviceHealthController.class
                        .getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertMethodRequires(method, EndpointAdminAuthz.VIEWER);
            }
        }
    }

    /**
     * AG-036 outdated-software (Faz 22.5, query API): outdated-software admin
     * GET routes ({@code /outdated-software/latest} + {@code /history}) reuse
     * the existing {@code module:endpoint-admin} VIEWER relation; no new RBAC
     * scope opens (mirrors the AG-033 device-health + BE-022Q hardware-
     * inventory contract). The agent ingest path is unchanged (it lives in
     * {@code EndpointAgentCommandService}, not a dedicated controller).
     */
    @Test
    void outdatedSoftwareControllerOnlyExposesViewerGetRoutes() {
        for (Method method :
                AdminEndpointOutdatedSoftwareController.class
                        .getDeclaredMethods()) {
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
