package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.compliancegap.ComplianceGapResponse;
import com.example.endpointadmin.dto.compliancegap.DeviceComplianceGap;
import com.example.endpointadmin.dto.compliancegap.GapDetail;
import com.example.endpointadmin.repository.ComplianceGapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): cross-snapshot compliance gap
 * aggregation service.
 *
 * <p>Surfaces devices with at least one active gap matching the requested
 * {@code gapTypes}. Bounded by {@code pageSize} (max 200) and
 * {@code freshnessWindow} (max P366D) — Codex risk mitigations against
 * unbounded full-table aggregation + sample/fleet confusion.
 *
 * <p>HARD RULE No Fake Work: all filtering pushed to DB via
 * {@link ComplianceGapRepository} native queries; NO client-side reduce
 * over fetched rows.
 *
 * <p>"Observed devices only" semantics — silent out-of-scope for devices
 * with zero snapshots in the freshness window. Operator MUST read
 * {@code filterEcho.freshnessWindow} to understand sample.
 */
@Service
public class EndpointComplianceGapService {

    private static final Logger log = LoggerFactory.getLogger(EndpointComplianceGapService.class);

    /** Max page size — Codex risk mitigation against unbounded fetch. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** Max freshness window. Roughly one year. */
    public static final Duration MAX_FRESHNESS_WINDOW = Duration.ofDays(366);
    public static final Duration DEFAULT_FRESHNESS_WINDOW = Duration.ofDays(7);

    private final ComplianceGapRepository repository;

    public EndpointComplianceGapService(ComplianceGapRepository repository) {
        this.repository = repository;
    }

    public ComplianceGapResponse findGaps(UUID tenantId,
                                          Set<ComplianceGapType> gapTypes,
                                          Duration freshnessWindow,
                                          int page,
                                          int pageSize) {
        // ── Validation (fail-closed) ──────────────────────────────────────
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenantId required");
        }

        Set<ComplianceGapType> effectiveGapTypes = (gapTypes == null || gapTypes.isEmpty())
                ? Set.of(ComplianceGapType.values())  // default: all known types
                : gapTypes;

        Duration effectiveWindow = freshnessWindow == null
                ? DEFAULT_FRESHNESS_WINDOW
                : freshnessWindow;
        if (effectiveWindow.compareTo(MAX_FRESHNESS_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "freshnessWindow exceeds max " + MAX_FRESHNESS_WINDOW
                            + " (got " + effectiveWindow + ")");
        }
        if (effectiveWindow.isNegative() || effectiveWindow.isZero()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "freshnessWindow must be positive (got " + effectiveWindow + ")");
        }

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;

        Instant freshnessThreshold = Instant.now().minus(effectiveWindow);
        Set<String> gapTypeWires = new HashSet<>();
        for (ComplianceGapType t : effectiveGapTypes) {
            gapTypeWires.add(t.wire());
        }

        // ── Query DB ──────────────────────────────────────────────────────
        List<Object[]> rows = repository.findGapDevices(
                tenantId, freshnessThreshold, gapTypeWires, safePageSize, offset);
        long total = repository.countGapDevices(tenantId, freshnessThreshold, gapTypeWires);

        // ── Project rows to DTOs ──────────────────────────────────────────
        List<DeviceComplianceGap> items = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String deviceId = String.valueOf(r[0]);
            String hostname = (String) r[1];
            String displayName = (String) r[2];
            Boolean rdpEnabled = (Boolean) r[3];
            Integer pendingTotalCount = r[5] == null ? null : ((Number) r[5]).intValue();

            // Faz 22.7 D2.1 hotfix (LIVE smoke 2026-06-02): TIMESTAMPTZ columns
            // come back as java.time.OffsetDateTime under Hibernate 6 + PG JDBC,
            // NOT java.sql.Timestamp. A blind `(Timestamp) r[N]` cast threw
            // ClassCastException at runtime ("class java.time.Instant cannot be
            // cast to class java.sql.Timestamp"). Defensive coercion handles all
            // three documented variants — Timestamp (legacy), OffsetDateTime
            // (current Hibernate 6 default), Instant (driver fallback).
            Instant startupCollected = toInstant(r[4]);
            Instant hotfixCollected = toInstant(r[6]);

            List<GapDetail> gaps = new ArrayList<>();
            List<String> staleComponents = new ArrayList<>();
            Instant maxLastSeen = null;

            // RDP_ENABLED gap
            if (effectiveGapTypes.contains(ComplianceGapType.RDP_ENABLED)
                    && Boolean.TRUE.equals(rdpEnabled)
                    && startupCollected != null) {
                gaps.add(new GapDetail(
                        ComplianceGapType.RDP_ENABLED.wire(),
                        ComplianceGapType.RDP_ENABLED.label(),
                        startupCollected,
                        false,  // already filtered by freshness window
                        Map.of("rdpEnabled", true)));
                if (maxLastSeen == null || startupCollected.isAfter(maxLastSeen)) {
                    maxLastSeen = startupCollected;
                }
            }

            // PENDING_SECURITY_UPDATES gap
            if (effectiveGapTypes.contains(ComplianceGapType.PENDING_SECURITY_UPDATES)
                    && pendingTotalCount != null && pendingTotalCount > 0
                    && hotfixCollected != null) {
                gaps.add(new GapDetail(
                        ComplianceGapType.PENDING_SECURITY_UPDATES.wire(),
                        ComplianceGapType.PENDING_SECURITY_UPDATES.label(),
                        hotfixCollected,
                        false,
                        Map.of("pendingTotalCount", pendingTotalCount)));
                if (maxLastSeen == null || hotfixCollected.isAfter(maxLastSeen)) {
                    maxLastSeen = hotfixCollected;
                }
            }

            String deviceName = (displayName != null && !displayName.isBlank())
                    ? displayName : hostname;

            // gapStrength: D2 MVP — all DB-filtered rows are within freshness window
            // → all gaps are "strong". Future: per-snapshot stale detection.
            String gapStrength = "strong";

            items.add(new DeviceComplianceGap(
                    deviceId, deviceName, maxLastSeen, gaps.size(),
                    gapStrength, gaps, staleComponents));
        }

        // ── Build filter echo ─────────────────────────────────────────────
        // Codex 019e88b0 P2-low absorb: gapTypes as sorted List (not Set) for
        // deterministic JSON output. UI cache keys + audit diffs need stable order.
        Map<String, Object> filterEcho = new LinkedHashMap<>();
        List<String> sortedGapTypes = new ArrayList<>(gapTypeWires);
        Collections.sort(sortedGapTypes);
        filterEcho.put("gapTypes", sortedGapTypes);
        filterEcho.put("freshnessWindow", effectiveWindow.toString());
        filterEcho.put("page", safePage);
        filterEcho.put("pageSize", safePageSize);

        return new ComplianceGapResponse(items, total, safePage, safePageSize,
                filterEcho, Instant.now());
    }

    /**
     * Faz 22.7 D2.1 hotfix: coerce a JDBC timestamp-shaped value to
     * {@link Instant}. Hibernate 6 + the current PG driver return
     * {@link OffsetDateTime} for TIMESTAMPTZ columns by default; older
     * stacks returned {@link Timestamp}; some drivers return raw
     * {@link Instant}. This helper handles all three documented variants
     * and rejects unknown types so future regressions fail loudly instead
     * of silently producing null.
     */
    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant inst) {
            return inst;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            // Defensive fallback for TIMESTAMP (no zone) columns; treat as UTC.
            return ldt.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException(
                "Unsupported JDBC timestamp type: " + value.getClass().getName());
    }
}
