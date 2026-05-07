package com.example.schema.yearly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 2b — Yearly schema discovery.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): MSSQL
 * {@code sys.schemas} discovery + Java regex normalization.
 * SQL {@code LIKE} alone is insufficient (underscore wildcard +
 * similar-named schemas); regex parses the canonical
 * {@code workcube_mikrolink_<year>_<companyId>} pattern.
 *
 * <p>Build-time/CLI use only — exposed as Spring Service so the
 * {@code YearlySchemaCoverageExporter} runner can invoke it; no
 * REST endpoint surface.
 */
@Service
public class YearlySchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(YearlySchemaDiscoveryService.class);

    /** Strict pattern: workcube_mikrolink_<4-digit-year>_<digits-companyId> */
    static final Pattern YEARLY_SCHEMA_PATTERN =
            Pattern.compile("^workcube_mikrolink_(\\d{4})_(\\d+)$");

    private final JdbcTemplate jdbc;

    @Autowired
    public YearlySchemaDiscoveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Discover all yearly partition schemas matching the canonical pattern.
     *
     * @param yearFilter      optional whitelist of years (empty → all)
     * @param companyFilter   optional whitelist of companyIds (empty → all)
     * @return deterministic-ordered list (year asc, companyId asc)
     */
    public List<DiscoveredSchema> discover(List<Integer> yearFilter, List<String> companyFilter) {
        List<String> candidates = jdbc.queryForList(
                "SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink_%_%'",
                String.class);
        log.debug("Yearly schema discovery candidates: {}", candidates.size());

        List<DiscoveredSchema> matched = new ArrayList<>();
        for (String name : candidates) {
            Matcher m = YEARLY_SCHEMA_PATTERN.matcher(name);
            if (!m.matches()) {
                continue;
            }
            int year = Integer.parseInt(m.group(1));
            String companyId = m.group(2);

            if (!yearFilter.isEmpty() && !yearFilter.contains(year)) {
                continue;
            }
            if (!companyFilter.isEmpty() && !companyFilter.contains(companyId)) {
                continue;
            }
            matched.add(new DiscoveredSchema(name, year, companyId));
        }

        matched.sort(Comparator
                .comparingInt(DiscoveredSchema::year)
                .thenComparing(DiscoveredSchema::companyId));
        log.info("Yearly schema discovery matched {} schemas (years={} companies={})",
                matched.size(), yearFilter, companyFilter);
        return Collections.unmodifiableList(matched);
    }

    /** Discovered schema record (year + companyId parsed from name). */
    public record DiscoveredSchema(String name, int year, String companyId) {}
}
