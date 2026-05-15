package com.example.schema.service.discovery;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RelationshipDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RelationshipDiscoveryService.class);

    /**
     * Phase 1 portability (Codex 019e2d7d AGREE — quick win 3+4/5): the
     * FK-heuristic dictionaries are loaded from JSON instead of being
     * hard-coded. Defaults stay Workcube-compatible; a new ERP supplies
     * its own files via {@code schema.fk-heuristics.alias-path} /
     * {@code common-fk-path}. See {@link FkHeuristicMapLoader} for the
     * fallback contract.
     */
    static final String DEFAULT_ALIAS_RESOURCE = "classpath:fk-heuristics/aliases-workcube.json";
    static final String DEFAULT_COMMON_FK_RESOURCE = "classpath:fk-heuristics/common-fk-workcube.json";

    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)", Pattern.CASE_INSENSITIVE
    );

    /** Technique 3: alias column → table dictionary (config-driven). */
    private final Map<String, String> aliasMap;

    /** Technique 4: common-FK column → table dictionary (config-driven). */
    private final Map<String, String> commonFkMap;

    @Value("${schema.discovery.enable-view-parsing:true}")
    private boolean enableViewParsing;

    @Autowired
    public RelationshipDiscoveryService(
            FkHeuristicMapLoader loader,
            @Value("${schema.fk-heuristics.alias-path:}") String aliasPath,
            @Value("${schema.fk-heuristics.common-fk-path:}") String commonFkPath) {
        this(loader.load(aliasPath, DEFAULT_ALIAS_RESOURCE, "alias"),
             loader.load(commonFkPath, DEFAULT_COMMON_FK_RESOURCE, "common-fk"));
    }

    /**
     * Test-friendly constructor — injects the heuristic maps directly,
     * bypassing JSON loading. Package-private; production wiring uses the
     * {@link FkHeuristicMapLoader}-based constructor above.
     */
    RelationshipDiscoveryService(Map<String, String> aliasMap, Map<String, String> commonFkMap) {
        this.aliasMap = Map.copyOf(aliasMap);
        this.commonFkMap = Map.copyOf(commonFkMap);
    }

    public List<Relationship> discoverAll(Map<String, TableInfo> tables,
                                          Map<String, String> viewDefinitions) {
        Set<String> tableNames = tables.keySet();
        List<Relationship> all = new ArrayList<>();

        // Technique 1-2: Name match
        all.addAll(discoverByNameMatch(tables, tableNames));

        // Technique 3: Alias patterns
        all.addAll(discoverByAlias(tables, tableNames));

        // Technique 4: Common FK patterns
        all.addAll(discoverByCommonFKs(tables, tableNames));

        // Technique 7: View/SP parsing
        if (enableViewParsing && viewDefinitions != null) {
            all.addAll(discoverFromViewDefinitions(viewDefinitions, tableNames));
        }

        // Deduplicate and score
        return deduplicateAndScore(all);
    }

    private List<Relationship> discoverByNameMatch(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            String tableName = entry.getKey();
            for (ColumnInfo col : entry.getValue().columns()) {
                if (!col.name().endsWith("_ID") || col.name().equals("ID")) continue;
                String base = col.name().substring(0, col.name().length() - 3);

                if (tableNames.contains(base) && !base.equals(tableName)) {
                    rels.add(new Relationship(tableName, col.name(), base, col.name(), 0.85, "name_match_exact"));
                } else if (tableNames.contains(base + "S") && !(base + "S").equals(tableName)) {
                    rels.add(new Relationship(tableName, col.name(), base + "S", col.name(), 0.80, "name_match_plural"));
                }
            }
        }
        log.info("Name match: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverByAlias(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                String target = aliasMap.get(col.name());
                if (target != null && tableNames.contains(target) && !target.equals(entry.getKey())) {
                    rels.add(new Relationship(entry.getKey(), col.name(), target, col.name(), 0.90, "alias_pattern"));
                }
            }
        }
        log.info("Alias patterns: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverByCommonFKs(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                String target = commonFkMap.get(col.name());
                if (target != null && tableNames.contains(target) && !target.equals(entry.getKey())) {
                    rels.add(new Relationship(entry.getKey(), col.name(), target, col.name(), 0.92, "common_fk"));
                }
            }
        }
        log.info("Common FKs: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverFromViewDefinitions(Map<String, String> viewDefs, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (var entry : viewDefs.entrySet()) {
            if (entry.getValue() == null) continue;
            Matcher m = JOIN_PATTERN.matcher(entry.getValue());
            while (m.find()) {
                String t1 = m.group(1).toUpperCase();
                String c1 = m.group(2).toUpperCase();
                String t2 = m.group(3).toUpperCase();
                String c2 = m.group(4).toUpperCase();

                // Resolve to actual table names
                String resolved1 = resolveAlias(t1, entry.getValue(), tableNames);
                String resolved2 = resolveAlias(t2, entry.getValue(), tableNames);
                if (resolved1 == null || resolved2 == null || resolved1.equals(resolved2)) continue;

                String key = resolved1 + "." + c1 + "=" + resolved2 + "." + c2;
                if (seen.add(key)) {
                    rels.add(new Relationship(resolved1, c1, resolved2, c2, 0.88,
                        "view_parse:" + entry.getKey()));
                }
            }
        }
        log.info("View parsing: {} relationships from {} definitions", rels.size(), viewDefs.size());
        return rels;
    }

    private String resolveAlias(String alias, String sql, Set<String> tableNames) {
        if (tableNames.contains(alias)) return alias;
        String upper = sql.toUpperCase();
        for (String tbl : tableNames) {
            if (upper.contains(tbl + " " + alias) || upper.contains(tbl + " AS " + alias)) {
                return tbl;
            }
        }
        return null;
    }

    private List<Relationship> deduplicateAndScore(List<Relationship> all) {
        Map<String, List<Relationship>> grouped = new LinkedHashMap<>();
        for (Relationship rel : all) {
            String key = rel.fromTable() + "|" + rel.fromColumn() + "|" + rel.toTable();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(rel);
        }

        List<Relationship> deduped = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<Relationship> rels = entry.getValue();
            Relationship best = rels.stream()
                .max(Comparator.comparingDouble(Relationship::confidence))
                .orElse(rels.getFirst());

            Set<String> sources = rels.stream()
                .map(r -> r.source().split(":")[0])
                .collect(Collectors.toSet());

            double conf = best.confidence();
            boolean multi = sources.size() > 1;
            if (multi) conf = Math.min(1.0, conf + 0.05 * (sources.size() - 1));

            deduped.add(new Relationship(
                best.fromTable(), best.fromColumn(), best.toTable(), best.toColumn(),
                conf, multi ? String.join("+", sources) : best.source(), multi
            ));
        }

        deduped.sort(Comparator.comparingDouble(Relationship::confidence).reversed()
            .thenComparing(Relationship::fromTable));

        log.info("Dedup: {} raw -> {} unique ({} multi-source)", all.size(), deduped.size(),
            deduped.stream().filter(Relationship::multiSource).count());
        return deduped;
    }
}
