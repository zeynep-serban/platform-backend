package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPosturePending;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingKb;

import java.util.ArrayList;
import java.util.List;

/**
 * BE — per-pending-update facet of a hotfix-posture snapshot response
 * (Faz 22.5, AG-037 query API).
 *
 * <p>Redaction boundary: EXACTLY the contract pending shape —
 * {@code kbIds[]}, {@code primaryCategory} (enum), {@code severity}
 * (enum). NO raw update title / description / vendor / install client
 * app id / deployment action is ever projected — those have no column
 * on the entity.
 */
public record AdminHotfixPendingResponse(
        List<String> kbIds,
        String primaryCategory,
        String severity) {

    public static AdminHotfixPendingResponse from(EndpointHotfixPosturePending pending) {
        List<String> kbIds = new ArrayList<>();
        if (pending.getKbs() != null) {
            for (EndpointHotfixPosturePendingKb kb : pending.getKbs()) {
                kbIds.add(kb.getKbId());
            }
        }
        return new AdminHotfixPendingResponse(
                kbIds,
                pending.getPrimaryCategory(),
                pending.getSeverity());
    }
}
