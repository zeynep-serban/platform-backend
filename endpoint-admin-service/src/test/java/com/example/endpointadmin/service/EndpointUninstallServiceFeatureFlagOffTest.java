package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestCreate;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-028 Phase 1b — feature-flag-off regression for
 * {@link EndpointUninstallService} (Faz 22.5.6).
 *
 * <p>Default state: {@code endpoint-admin.uninstall.enabled=false}. The
 * service MUST throw 503 SERVICE_UNAVAILABLE on every public method (read +
 * write) so the surface ships off-by-default and can be flipped on per
 * overlay once Phase 2 (agent) lands.
 *
 * <p>Separate context from {@code EndpointUninstallServiceTest} because the
 * {@code @Value}-injected feature flag is bound at bean construction time;
 * a single test class cannot exercise both true and false.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointUninstallService.class,
        EndpointAuditService.class,
        EndpointSoftwareCatalogService.class,
        DetectionRuleValidator.class,
        NoOpAuditChainLock.class
})
@TestPropertySource(properties = {
        "endpoint-admin.uninstall.enabled=false"
})
class EndpointUninstallServiceFeatureFlagOffTest {

    private static final UUID TENANT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID DEVICE = UUID.randomUUID();

    @Autowired
    private EndpointUninstallService uninstallService;

    @Test
    void propose_featureFlagOff_throws503() {
        assertThatThrownBy(() -> uninstallService.propose(
                new AdminTenantContext(TENANT, "alice@example.com"),
                DEVICE,
                new AdminUninstallRequestCreate("7zip", null, "test")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessageContaining("disabled");
    }

    @Test
    void approve_featureFlagOff_throws503() {
        assertThatThrownBy(() -> uninstallService.approve(
                new AdminTenantContext(TENANT, "bob@example.com"),
                DEVICE,
                UUID.randomUUID(),
                null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void listForDevice_featureFlagOff_throws503() {
        assertThatThrownBy(() -> uninstallService.listForDevice(
                new AdminTenantContext(TENANT, "alice@example.com"),
                DEVICE, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void getHistory_featureFlagOff_throws503() {
        assertThatThrownBy(() -> uninstallService.getHistory(
                new AdminTenantContext(TENANT, "alice@example.com"),
                DEVICE, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
