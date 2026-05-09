package com.serban.notify.api.dto;

import java.util.List;

/**
 * Paged audit history listing response DTO.
 *
 * <p>Faz 23.2.B closure (M3 stale audit 2026-05-09 — KVKK §13
 * right-to-information self-service endpoint).
 *
 * <p>Pagination: 0-indexed; service-side clamped page size {@code [1, 100]}.
 */
public record AuditHistoryListResponse(
    List<AuditHistoryItemResponse> items,
    long totalElements,
    int page,
    int size
) {}
