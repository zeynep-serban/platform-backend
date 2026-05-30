package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleResponse;
import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import com.example.endpointadmin.repository.EndpointProhibitedSoftwareRuleRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-025 — CRUD + duplicate-guard tests for
 * {@link ProhibitedSoftwareRuleService} (Faz 22.5).
 */
@ExtendWith(MockitoExtension.class)
class ProhibitedSoftwareRuleServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RULE_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "admin@example.com");

    @Mock
    private EndpointProhibitedSoftwareRuleRepository ruleRepository;

    private ProhibitedSoftwareRuleService service;

    @BeforeEach
    void setUp() {
        service = new ProhibitedSoftwareRuleService(
                ruleRepository, new ProhibitedSoftwareRuleValidator());
    }

    private static ProhibitedSoftwareRuleRequest nameRequest(String name, String publisher) {
        return new ProhibitedSoftwareRuleRequest(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.EXACT,
                name, publisher, true, "banned per policy");
    }

    private static EndpointProhibitedSoftwareRule persistedRule() {
        EndpointProhibitedSoftwareRule rule = new EndpointProhibitedSoftwareRule();
        rule.setTenantId(TENANT_ID);
        rule.setMatchType(ProhibitedSoftwareMatchType.NAME);
        rule.setMatchMode(ProhibitedSoftwareMatchMode.EXACT);
        rule.setNamePattern("uTorrent");
        rule.setEnabled(true);
        rule.setCreatedBySubject("admin@example.com");
        rule.setLastUpdatedBySubject("admin@example.com");
        return rule;
    }

    @Test
    void createPersistsTrimmedPatternAndAuditSubject() {
        when(ruleRepository.findDuplicate(eq(TENANT_ID), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT_ID)))
                .thenReturn(Optional.of(persistedRule()));

        service.create(TENANT, nameRequest("  uTorrent  ", null));

        ArgumentCaptor<EndpointProhibitedSoftwareRule> captor =
                ArgumentCaptor.forClass(EndpointProhibitedSoftwareRule.class);
        verify(ruleRepository).save(captor.capture());
        EndpointProhibitedSoftwareRule saved = captor.getValue();
        assertThat(saved.getNamePattern()).isEqualTo("uTorrent"); // trimmed, case preserved
        assertThat(saved.getPublisherPattern()).isNull();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getCreatedBySubject()).isEqualTo("admin@example.com");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void createRejectedByValidatorReturns400WithoutPersist() {
        // NAME type but publisher supplied → validator 400 BEFORE any repo call.
        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", "Vendor")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).findDuplicate(any(), any(), any(), any(), any());
    }

    @Test
    void createDuplicateReturns409() {
        when(ruleRepository.findDuplicate(eq(TENANT_ID),
                eq(ProhibitedSoftwareMatchType.NAME),
                eq(ProhibitedSoftwareMatchMode.EXACT),
                eq("utorrent"), eq("")))
                .thenReturn(Optional.of(persistedRule()));

        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void getMissingReturns404() {
        when(ruleRepository.findByIdAndTenantId(RULE_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT, RULE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void deleteMissingReturns404() {
        when(ruleRepository.findByIdAndTenantId(RULE_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TENANT, RULE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(ruleRepository, never()).delete(any());
    }

    // ── persist() status routing (Codex 019e763a REVISE #2) ──
    // A CHECK-constraint violation that reaches the backstop must surface as
    // 400 (invalid input), NOT 409 (duplicate). A UNIQUE-dedup race stays 409.

    @Test
    void checkConstraintViolationOnPersistMapsTo400() {
        when(ruleRepository.findDuplicate(eq(TENANT_ID), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any()))
                .thenThrow(dataIntegrityViolation(
                        "ck_endpoint_prohibited_software_rules_name_pattern_blank", "23514"));

        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void uniqueConstraintViolationOnPersistMapsTo409() {
        when(ruleRepository.findDuplicate(eq(TENANT_ID), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any()))
                .thenThrow(dataIntegrityViolation(
                        "uq_endpoint_prohibited_software_rules_tenant_dedup", "23505"));

        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void checkViolationRoutedBySqlStateWhenConstraintNameAbsentMapsTo400() {
        // Robustness: even with a null constraint name, SQLSTATE 23514
        // (check_violation) routes to 400 rather than the 409 default.
        when(ruleRepository.findDuplicate(eq(TENANT_ID), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any()))
                .thenThrow(dataIntegrityViolation(null, "23514"));

        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void unclassifiableViolationFallsBackTo409() {
        // No constraint name + unrelated SQLSTATE → keep the historical 409
        // default (concurrent-equivalent-create race) rather than guessing 400.
        when(ruleRepository.findDuplicate(eq(TENANT_ID), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any()))
                .thenThrow(dataIntegrityViolation(null, "40001"));

        assertThatThrownBy(() -> service.create(TENANT, nameRequest("uTorrent", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    private static DataIntegrityViolationException dataIntegrityViolation(
            String constraintName, String sqlState) {
        java.sql.SQLException sqlEx =
                new java.sql.SQLException("backstop violation", sqlState);
        org.hibernate.exception.ConstraintViolationException hibernateEx =
                new org.hibernate.exception.ConstraintViolationException(
                        "could not execute statement", sqlEx, constraintName);
        return new DataIntegrityViolationException("persist failed", hibernateEx);
    }

    @Test
    void updateExcludesSelfFromDuplicateCheck() {
        EndpointProhibitedSoftwareRule existing = persistedRule();
        // give it the id we are updating so the dup result == self
        setId(existing, RULE_ID);
        when(ruleRepository.findByIdAndTenantId(RULE_ID, TENANT_ID))
                .thenReturn(Optional.of(existing));
        when(ruleRepository.findDuplicate(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(existing)); // the duplicate IS the same row
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProhibitedSoftwareRuleResponse out =
                service.update(TENANT, RULE_ID, nameRequest("uTorrent", null));

        // No 409 — the self-collision is excluded.
        assertThat(out).isNotNull();
        verify(ruleRepository).save(any());
    }

    private static void setId(EndpointProhibitedSoftwareRule rule, UUID id) {
        try {
            var field = EndpointProhibitedSoftwareRule.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(rule, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
