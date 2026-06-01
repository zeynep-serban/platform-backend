package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPostureInstalled;

import java.time.Instant;

/**
 * BE — per-installed-hotfix facet of a hotfix-posture snapshot response
 * (Faz 22.5, AG-037 query API).
 *
 * <p>Redaction boundary: EXACTLY the contract's installed shape —
 * {@code kbId} (KB regex), nullable {@code installedOn}, and an
 * operator-friendly {@code description}. NO update title / install
 * client / install account / install command / supersedence / product
 * code is ever projected because no such column exists on the entity.
 */
public record AdminHotfixInstalledResponse(
        String kbId,
        Instant installedOn,
        String description) {

    public static AdminHotfixInstalledResponse from(EndpointHotfixPostureInstalled installed) {
        return new AdminHotfixInstalledResponse(
                installed.getKbId(),
                installed.getInstalledOn(),
                installed.getDescription());
    }
}
