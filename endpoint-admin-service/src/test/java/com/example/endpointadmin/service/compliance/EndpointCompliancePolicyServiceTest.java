package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemRequest;
import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemResponse;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.ComplianceEnforcementMode;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCompliancePolicyItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointCompliancePolicyServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "admin@example.com");

    @Mock
    private EndpointSoftwareCompliancePolicyItemRepository policyRepository;
    @Mock
    private EndpointSoftwareCatalogItemRepository catalogRepository;

    private EndpointCompliancePolicyService service;

    @BeforeEach
    void setUp() {
        service = new EndpointCompliancePolicyService(policyRepository, catalogRepository);
    }

    @Test
    void createRejectsCatalogItemFromDifferentTenant() {
        UUID catalogId = UUID.randomUUID();
        EndpointSoftwareCatalogItem foreign = buildCatalog(catalogId, OTHER_TENANT_ID);
        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.create(TENANT,
                new CompliancePolicyItemRequest(
                        catalogId, ComplianceEnforcementMode.REQUIRED, true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void createRejectsDuplicatePolicyForSameCatalog() {
        UUID catalogId = UUID.randomUUID();
        EndpointSoftwareCatalogItem c = buildCatalog(catalogId, TENANT_ID);
        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(c));
        when(policyRepository.findByTenantIdAndCatalogItemId(TENANT_ID, catalogId))
                .thenReturn(Optional.of(buildPolicy(c, ComplianceEnforcementMode.REQUIRED)));

        assertThatThrownBy(() -> service.create(TENANT,
                new CompliancePolicyItemRequest(
                        catalogId, ComplianceEnforcementMode.REQUIRED, true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void createPersistsAndReturnsResponseShape() {
        UUID catalogId = UUID.randomUUID();
        EndpointSoftwareCatalogItem c = buildCatalog(catalogId, TENANT_ID);
        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(c));
        when(policyRepository.findByTenantIdAndCatalogItemId(TENANT_ID, catalogId))
                .thenReturn(Optional.empty());
        when(policyRepository.save(any(EndpointSoftwareCompliancePolicyItem.class)))
                .thenAnswer(inv -> {
                    EndpointSoftwareCompliancePolicyItem p = inv.getArgument(0);
                    if (p.getId() == null) {
                        setField(p, "id", UUID.randomUUID());
                    }
                    setField(p, "catalogItem", c);
                    return p;
                });
        when(policyRepository.findByIdAndTenantId(any(), eq(TENANT_ID)))
                .thenAnswer(inv -> {
                    EndpointSoftwareCompliancePolicyItem p = new EndpointSoftwareCompliancePolicyItem();
                    setField(p, "id", inv.getArgument(0));
                    p.setTenantId(TENANT_ID);
                    p.setCatalogItemId(catalogId);
                    setField(p, "catalogItem", c);
                    p.setEnforcementMode(ComplianceEnforcementMode.REQUIRED);
                    p.setEnabled(true);
                    p.setCreatedBySubject("admin@example.com");
                    p.setLastUpdatedBySubject("admin@example.com");
                    setField(p, "version", 0L);
                    return Optional.of(p);
                });

        CompliancePolicyItemResponse response = service.create(TENANT,
                new CompliancePolicyItemRequest(
                        catalogId, ComplianceEnforcementMode.REQUIRED, null));

        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
        assertThat(response.catalogItemId()).isEqualTo(catalogId);
        assertThat(response.enforcementMode()).isEqualTo(ComplianceEnforcementMode.REQUIRED);
        assertThat(response.enabled()).isTrue();
    }

    @Test
    void updateRejectsRetargetingCatalogId() {
        UUID id = UUID.randomUUID();
        UUID originalCatalogId = UUID.randomUUID();
        EndpointSoftwareCatalogItem c = buildCatalog(originalCatalogId, TENANT_ID);
        EndpointSoftwareCompliancePolicyItem existing =
                buildPolicy(c, ComplianceEnforcementMode.REQUIRED);
        setField(existing, "id", id);
        when(policyRepository.findByIdAndTenantId(id, TENANT_ID))
                .thenReturn(Optional.of(existing));

        UUID otherCatalogId = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(TENANT, id,
                new CompliancePolicyItemRequest(
                        otherCatalogId, ComplianceEnforcementMode.FORBIDDEN, true)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void listMapsThroughCatalogReference() {
        EndpointSoftwareCatalogItem catalog =
                buildCatalog(UUID.randomUUID(), TENANT_ID);
        EndpointSoftwareCompliancePolicyItem policy =
                buildPolicy(catalog, ComplianceEnforcementMode.FORBIDDEN);
        setField(policy, "id", UUID.randomUUID());
        Page<EndpointSoftwareCompliancePolicyItem> page =
                new PageImpl<>(List.of(policy));
        when(policyRepository.findByTenantIdOrderByLastUpdatedAtDesc(TENANT_ID,
                PageRequest.of(0, 25))).thenReturn(page);

        Page<CompliancePolicyItemResponse> result =
                service.list(TENANT, PageRequest.of(0, 25));

        assertThat(result.getContent()).hasSize(1);
        CompliancePolicyItemResponse r = result.getContent().get(0);
        assertThat(r.catalogItemKey()).isEqualTo(catalog.getCatalogItemId());
        assertThat(r.catalogDisplayName()).isEqualTo(catalog.getDisplayName());
        assertThat(r.enforcementMode()).isEqualTo(ComplianceEnforcementMode.FORBIDDEN);
    }

    @Test
    void deleteThrows404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(policyRepository.findByIdAndTenantId(id, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TENANT, id))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    private static EndpointSoftwareCatalogItem buildCatalog(UUID id, UUID tenantId) {
        EndpointSoftwareCatalogItem c = new EndpointSoftwareCatalogItem();
        setField(c, "id", id);
        c.setTenantId(tenantId);
        c.setCatalogItemId("7zip.7zip");
        c.setStatus(CatalogItemStatus.APPROVED);
        c.setProvider(CatalogProvider.WINGET);
        c.setSourceType(CatalogSourceType.WINGET);
        c.setSourceName("winget");
        c.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        c.setPackageId("7zip.7zip");
        c.setDisplayName("7-Zip");
        c.setPublisher("7-Zip");
        c.setVersionPolicyType(CatalogVersionPolicyType.LATEST);
        c.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        c.setSilentArgsPolicy(CatalogSilentArgsPolicy.DEFAULT);
        c.setRiskTier(CatalogRiskTier.LOW);
        c.setEnabled(true);
        c.setCreatedBySubject("creator");
        c.setLastUpdatedBySubject("creator");
        setField(c, "version", 1L);
        return c;
    }

    private static EndpointSoftwareCompliancePolicyItem buildPolicy(
            EndpointSoftwareCatalogItem catalog, ComplianceEnforcementMode mode) {
        EndpointSoftwareCompliancePolicyItem p = new EndpointSoftwareCompliancePolicyItem();
        setField(p, "id", UUID.randomUUID());
        p.setTenantId(TENANT_ID);
        p.setCatalogItemId(catalog.getId());
        setField(p, "catalogItem", catalog);
        p.setEnforcementMode(mode);
        p.setEnabled(true);
        p.setCreatedBySubject("creator");
        p.setLastUpdatedBySubject("creator");
        setField(p, "version", 1L);
        return p;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
