package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.compliancegap.ComplianceGapResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.ComplianceGapType;
import com.example.endpointadmin.service.EndpointComplianceGapService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): cross-snapshot compliance gap
 * aggregate REST endpoint.
 *
 * <p>Surfaces devices with at least one active gap matching the requested
 * filters. Read-only — no remediation iddiası (Codex risk mitigation:
 * overclaim guard).
 *
 * <p>Authorized by {@link EndpointAdminAuthz#MODULE} {@code can_view}.
 * Tenant boundary enforced via {@link TenantContextResolver}.
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-devices")
public class AdminEndpointComplianceGapController {

    private final EndpointComplianceGapService gapService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointComplianceGapController(EndpointComplianceGapService gapService,
                                                TenantContextResolver tenantContextResolver) {
        this.gapService = gapService;
        this.tenantContextResolver = tenantContextResolver;
    }

    /**
     * Query devices with active compliance gaps.
     *
     * <p>Optional query params:
     * <ul>
     *   <li>{@code gapType} / {@code gapTypes} — canonical wire values
     *       (repeatable or comma-separated; default: all known types)</li>
     *   <li>{@code freshnessWindow} — ISO-8601 duration (default {@code PT168H} = 7 days, max {@code P366D})</li>
     *   <li>{@code page} — 1-based page (default 1)</li>
     *   <li>{@code pageSize} — items per page (default 50, max 200)</li>
     * </ul>
     */
    @GetMapping("/compliance-gap")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public ResponseEntity<ComplianceGapResponse> queryComplianceGaps(
            @RequestParam(name = "gapType", required = false) List<String> gapTypeWires,
            @RequestParam(name = "gapTypes", required = false) List<String> gapTypesWires,
            @RequestParam(name = "freshnessWindow", required = false) String freshnessWindowRaw,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize) {

        AdminTenantContext context = tenantContextResolver.resolveRequired();

        Set<ComplianceGapType> gapTypes = parseGapTypes(gapTypeWires, gapTypesWires);
        Duration freshnessWindow = parseFreshnessWindow(freshnessWindowRaw);

        ComplianceGapResponse response = gapService.findGaps(
                context.tenantId(), gapTypes, freshnessWindow, page, pageSize);
        return ResponseEntity.ok(response);
    }

    private static Set<ComplianceGapType> parseGapTypes(
            List<String> gapTypeWires, List<String> gapTypesWires) {
        List<String> tokens = new ArrayList<>();
        addGapTypeTokens(tokens, gapTypeWires);
        addGapTypeTokens(tokens, gapTypesWires);
        if (tokens.isEmpty()) {
            return null;  // service default = all
        }
        Set<ComplianceGapType> result = new HashSet<>();
        for (String w : tokens) {
            try {
                result.add(ComplianceGapType.fromWire(w));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ex.getMessage());
            }
        }
        return result;
    }

    private static void addGapTypeTokens(List<String> tokens, List<String> wires) {
        if (wires == null || wires.isEmpty()) {
            return;
        }
        for (String raw : wires) {
            if (raw == null) {
                continue;
            }
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed);
                }
            }
        }
    }

    private static Duration parseFreshnessWindow(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;  // service default
        }
        try {
            return Duration.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "freshnessWindow must be ISO-8601 duration (e.g. PT168H, P7D)");
        }
    }
}
