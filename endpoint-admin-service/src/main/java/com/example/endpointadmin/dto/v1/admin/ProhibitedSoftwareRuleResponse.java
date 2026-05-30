package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-025 — Read shape for a prohibited-software denylist rule (the admin
 * CRUD surface, Faz 22.5).
 *
 * <p>This is the rule-management projection, distinct from the
 * device-facing {@link ProhibitedSoftwareFindingResponse}. {@code notes}
 * IS exposed here (the rule owner manages it) but is NEVER carried on the
 * device-facing read surface or in compliance evidence.
 */
public record ProhibitedSoftwareRuleResponse(
        UUID id,
        UUID tenantId,
        ProhibitedSoftwareMatchType matchType,
        ProhibitedSoftwareMatchMode matchMode,
        String namePattern,
        String publisherPattern,
        boolean enabled,
        String notes,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant lastUpdatedAt,
        Long version) {

    public static ProhibitedSoftwareRuleResponse from(EndpointProhibitedSoftwareRule rule) {
        return new ProhibitedSoftwareRuleResponse(
                rule.getId(),
                rule.getTenantId(),
                rule.getMatchType(),
                rule.getMatchMode(),
                rule.getNamePattern(),
                rule.getPublisherPattern(),
                rule.isEnabled(),
                rule.getNotes(),
                rule.getCreatedBySubject(),
                rule.getCreatedAt(),
                rule.getLastUpdatedBySubject(),
                rule.getLastUpdatedAt(),
                rule.getVersion());
    }
}
