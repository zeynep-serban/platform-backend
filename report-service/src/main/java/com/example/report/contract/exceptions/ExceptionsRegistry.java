package com.example.report.contract.exceptions;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Phase 2 Program 1b — Contract exceptions registry.
 *
 * <p>Spec §2.5 + Codex iter-1 §3 + iter-3 absorb:
 * <ul>
 *   <li>{@code expiresAt} field zorunlu — missing entries surface as
 *       FAIL violations (cannot suppress without an explicit expiry).</li>
 *   <li>Past {@code expiresAt} → entry IGNORED → underlying violation
 *       surfaces (existing Codex spec line behavior).</li>
 *   <li>{@code expiresAt > now + 90d} → FAIL ({@code EXCEPTION_BEYOND_
 *       90D_HORIZON}) — sürekli bypass YASAK.</li>
 * </ul>
 *
 * <p>Build-time only (no @Component / @Configuration); test classpath'inde
 * yüklenir; production runtime'da inactive.
 *
 * <p>Source: {@code reports/exceptions.json} classpath resource (default);
 * test override via constructor.
 */
public final class ExceptionsRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExceptionsRegistry.class);

    public static final long MAX_HORIZON_DAYS = 90L;

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String exceptionsPath;
    private final Clock clock;
    private final Map<String, List<ContractExceptionEntry>> entriesByReport = new ConcurrentHashMap<>();
    /**
     * Codex iter-1 BLOCKING absorb: load/parse error fail-closed.
     * Malformed exceptions.json silently empty registry yapma — governance
     * artifact integrity violation, build-time gate'in yeşil kalmaması için
     * meta-FAIL surface.
     */
    private final List<ContractViolation> loadViolations = new ArrayList<>();

    /**
     * Constructor with injectable Clock for deterministic tests.
     */
    public ExceptionsRegistry(ResourceLoader resourceLoader,
                                ObjectMapper objectMapper,
                                String exceptionsPath,
                                Clock clock) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.exceptionsPath = exceptionsPath;
        this.clock = clock;
    }

    /**
     * Default Spring constructor (production: classpath resource +
     * system Clock). Build-time only — caller decides when to load.
     */
    public ExceptionsRegistry(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this(resourceLoader, objectMapper,
                "classpath:reports/exceptions.json", Clock.systemUTC());
    }

    /**
     * Load exceptions from configured resource. Idempotent.
     */
    @PostConstruct
    public void load() {
        loadViolations.clear();
        try {
            Resource resource = resourceLoader.getResource(exceptionsPath);
            if (!resource.exists()) {
                log.debug("No exceptions.json at {} (empty registry)", exceptionsPath);
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                ContractExceptionEntry[] entries =
                        objectMapper.readValue(in, ContractExceptionEntry[].class);
                Map<String, List<ContractExceptionEntry>> grouped = new ConcurrentHashMap<>();
                for (ContractExceptionEntry entry : entries) {
                    if (entry.reportKey() == null) {
                        continue;
                    }
                    grouped.computeIfAbsent(entry.reportKey(), k -> new ArrayList<>()).add(entry);
                }
                entriesByReport.clear();
                entriesByReport.putAll(grouped);
                log.info("Loaded {} contract exceptions from {}", entries.length, exceptionsPath);
            }
        } catch (Exception e) {
            // Codex iter-1 BLOCKING absorb: load/parse error fail-closed.
            // Missing resource → silent empty (seed []), but parse error →
            // surface as FAIL meta-violation (governance artifact integrity).
            log.error("Failed to load exceptions.json from {}", exceptionsPath, e);
            entriesByReport.clear();
            loadViolations.add(ContractViolation.fail(
                    "EXCEPTION_REGISTRY_LOAD_ERROR",
                    "_exceptions",
                    "exceptions.json",
                    "Failed to load exceptions registry from " + exceptionsPath
                            + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * Filter violations: suppress entries covered by a valid (non-expired,
     * within-horizon) exception. Surface FAIL for missing-expiry +
     * beyond-90d entries.
     *
     * @param violations raw rule violations
     * @return filtered violations + meta-violations for invalid exception entries
     */
    public List<ContractViolation> apply(List<ContractViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            // Codex iter-1 BLOCKING absorb: empty input path da meta + load
            // violations'ı surface etmeli (governance gate fail-closed).
            List<ContractViolation> emptyPath = new ArrayList<>(
                    surfaceMetaViolations(Collections.emptyList()));
            emptyPath.addAll(loadViolations);
            return emptyPath;
        }

        Instant now = clock.instant();
        Instant horizon = now.plus(Duration.ofDays(MAX_HORIZON_DAYS));

        List<ContractViolation> result = new ArrayList<>();
        for (ContractViolation v : violations) {
            ContractExceptionEntry covering = findValidException(
                    v.reportKey(), v.ruleId(), now, horizon);
            if (covering != null) {
                // suppressed; no entry in result
                log.debug("Suppressed {} for report={} via exception {}",
                        v.ruleId(), v.reportKey(), covering.id());
            } else {
                result.add(v);
            }
        }
        // Append meta-violations for invalid exception entries
        result.addAll(surfaceMetaViolations(violations));
        // Codex iter-1 BLOCKING absorb: surface load/parse errors fail-closed
        result.addAll(loadViolations);
        return result;
    }

    /**
     * Find exception entry covering (reportKey, ruleId) that is currently
     * valid (not expired + within horizon). Returns null if none match
     * or matched entry is invalid.
     */
    private ContractExceptionEntry findValidException(String reportKey, String ruleId,
                                                       Instant now, Instant horizon) {
        List<ContractExceptionEntry> entries = entriesByReport.get(reportKey);
        if (entries == null) {
            return null;
        }
        for (ContractExceptionEntry entry : entries) {
            if (!entry.covers(reportKey, ruleId)) {
                continue;
            }
            // Missing expiresAt → INVALID, surfaces as meta-violation; do not suppress.
            if (entry.expiresAt() == null) {
                continue;
            }
            // Past expiresAt → ignored; do not suppress.
            if (entry.expiresAt().isBefore(now) || entry.expiresAt().equals(now)) {
                continue;
            }
            // Beyond 90-day horizon → INVALID, surfaces as meta-violation; do not suppress.
            if (entry.expiresAt().isAfter(horizon)) {
                continue;
            }
            return entry;
        }
        return null;
    }

    /**
     * Surface meta-violations for invalid exception entries (missing or
     * beyond-horizon expiresAt). Independent of whether the underlying
     * rule violations were suppressed — these flag the exception entries
     * themselves.
     */
    private List<ContractViolation> surfaceMetaViolations(List<ContractViolation> originalViolations) {
        List<ContractViolation> meta = new ArrayList<>();
        Instant now = clock.instant();
        Instant horizon = now.plus(Duration.ofDays(MAX_HORIZON_DAYS));

        for (Map.Entry<String, List<ContractExceptionEntry>> e : entriesByReport.entrySet()) {
            for (ContractExceptionEntry entry : e.getValue()) {
                if (entry.expiresAt() == null) {
                    meta.add(ContractViolation.fail(
                            "EXCEPTION_MISSING_EXPIRY",
                            entry.reportKey(), "exceptions[" + entry.id() + "]",
                            "Exception entry '" + entry.id() + "' missing expiresAt — "
                                    + "all exceptions must declare an expiry (Codex iter-1 §3 absorb)"));
                } else if (entry.expiresAt().isAfter(horizon)) {
                    long daysOver = Duration.between(horizon, entry.expiresAt()).toDays();
                    meta.add(ContractViolation.fail(
                            "EXCEPTION_BEYOND_90D_HORIZON",
                            entry.reportKey(), "exceptions[" + entry.id() + "]",
                            "Exception entry '" + entry.id() + "' expiresAt="
                                    + entry.expiresAt() + " exceeds 90-day horizon by "
                                    + daysOver + " days — "
                                    + "no contract bypass beyond 90-day max (Codex iter-3 absorb)"));
                }
                // Past expiresAt → silently ignored (entry inert, underlying violation surfaces)
            }
        }
        return meta;
    }

    public int entryCount() {
        return entriesByReport.values().stream()
                .mapToInt(List::size).sum();
    }
}
