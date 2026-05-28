package com.example.endpointadmin.dto.v1.admin;

import java.util.List;

/**
 * BE-023 — Paginated list envelope shared by:
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/compliance/devices} (cross-device list)</li>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluations} (history)</li>
 *   <li>{@code GET /api/v1/admin/compliance/policy-items} (policy CRUD list)</li>
 * </ul>
 */
public record ComplianceEvaluationListResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
