package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * BE-025 — Create / update request body for a prohibited-software
 * denylist rule (Faz 22.5).
 *
 * <p>Bean-validation here is the first gate; the
 * {@code ProhibitedSoftwareRuleValidator} in the service layer then
 * enforces the cross-field {@code matchType ↔ pattern} consistency, the
 * blank-pattern rejection, and the CONTAINS minimum length so the operator
 * gets a clean 400 (rather than a 500 / DB CHECK violation). NO regex field
 * exists by design — matching is literal EXACT / bounded CONTAINS only.
 *
 * <p>Pattern column rules (validated server-side):
 * <ul>
 *   <li>{@code NAME} → {@code namePattern} required, {@code publisherPattern}
 *       must be absent.</li>
 *   <li>{@code PUBLISHER} → {@code publisherPattern} required,
 *       {@code namePattern} must be absent.</li>
 *   <li>{@code NAME_AND_PUBLISHER} → both required.</li>
 * </ul>
 */
public record ProhibitedSoftwareRuleRequest(
        @NotNull ProhibitedSoftwareMatchType matchType,
        @NotNull ProhibitedSoftwareMatchMode matchMode,
        @Size(max = 256) String namePattern,
        @Size(max = 256) String publisherPattern,
        Boolean enabled,
        @Size(max = 512) String notes) {
}
