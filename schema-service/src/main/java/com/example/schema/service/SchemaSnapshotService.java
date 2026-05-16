package com.example.schema.service;

import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import com.example.schema.model.UniqueConstraintInfo;
import com.example.schema.service.discovery.RelationshipDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SchemaSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotService.class);

    private final SchemaExtractService extractService;
    private final RelationshipDiscoveryService discoveryService;
    private final DomainClusteringService clusteringService;

    public SchemaSnapshotService(SchemaExtractService extractService,
                                  RelationshipDiscoveryService discoveryService,
                                  DomainClusteringService clusteringService) {
        this.extractService = extractService;
        this.discoveryService = discoveryService;
        this.clusteringService = clusteringService;
    }

    @Cacheable(value = "snapshot", key = "#schema")
    public SchemaSnapshot buildSnapshot(String schema) {
        log.info("Building schema snapshot for '{}'...", schema);
        long start = System.currentTimeMillis();

        // 1. Extract tables
        Map<String, TableInfo> tables = extractService.extractTables(schema);

        // 2. Extract view definitions for parsing
        Map<String, String> viewDefs = Collections.emptyMap();
        try {
            viewDefs = extractService.getViewDefinitions(schema);
        } catch (Exception e) {
            log.warn("View extraction failed: {}", e.getMessage());
        }

        // Authoritative FK + unique constraint extraction (B1-2 — Codex
        // 019e2d7d). Non-fatal: snapshot continues with empty inventory.
        List<ForeignKeyInfo> foreignKeys = List.of();
        try {
            foreignKeys = extractService.extractForeignKeys(schema);
        } catch (Exception e) {
            log.warn("Foreign key extraction failed: {}", e.getMessage());
        }
        List<UniqueConstraintInfo> uniqueConstraints = List.of();
        try {
            uniqueConstraints = extractService.extractUniqueConstraints(schema);
        } catch (Exception e) {
            log.warn("Unique constraint extraction failed: {}", e.getMessage());
        }

        // 3. Discover relationships (heuristic + authoritative FK compat layer)
        List<Relationship> relationships = discoveryService.discoverAll(tables, viewDefs, foreignKeys);

        // 4. Domain clustering
        Map<String, List<String>> domains = clusteringService.detectDomains(
            tables.keySet(), relationships
        );

        // 5. Row counts (optional, may fail)
        Map<String, Long> rowCounts = Collections.emptyMap();
        try {
            rowCounts = extractService.getRowCounts(schema);
        } catch (Exception e) {
            log.warn("Row count extraction failed: {}", e.getMessage());
        }

        // 6. Enrich tables with row counts
        if (!rowCounts.isEmpty()) {
            Map<String, Long> finalRowCounts = rowCounts;
            Map<String, TableInfo> enriched = new LinkedHashMap<>();
            tables.forEach((name, table) -> {
                Long count = finalRowCounts.get(name);
                enriched.put(name, new TableInfo(name, table.schema(), table.columns(), count, table.columnCount()));
            });
            tables = enriched;
        }

        // 7. Analysis
        Set<String> connected = new HashSet<>();
        relationships.forEach(r -> { connected.add(r.fromTable()); connected.add(r.toTable()); });

        Map<String, Long> finalRowCounts2 = rowCounts;
        List<SchemaSnapshot.DeadTable> deadTables = tables.keySet().stream()
            .filter(t -> !connected.contains(t))
            .map(t -> new SchemaSnapshot.DeadTable(t, "no_relationships",
                finalRowCounts2.getOrDefault(t, null)))
            .sorted(Comparator.comparing(SchemaSnapshot.DeadTable::table))
            .toList();

        Map<String, Long> refCounts = new HashMap<>();
        relationships.forEach(r -> refCounts.merge(r.toTable(), 1L, Long::sum));
        List<SchemaSnapshot.HubTable> hubTables = refCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(30)
            .map(e -> new SchemaSnapshot.HubTable(e.getKey(), e.getValue().intValue()))
            .toList();

        int totalCols = tables.values().stream().mapToInt(t -> t.columns().size()).sum();

        SchemaSnapshot snapshot = new SchemaSnapshot(
            "1.1",
            new SchemaSnapshot.Metadata(
                "mssql", "", "", schema, Instant.now(),
                tables.size(), totalCols, relationships.size(), domains.size()
            ),
            tables,
            relationships,
            foreignKeys,
            uniqueConstraints,
            domains,
            new SchemaSnapshot.Analysis(deadTables, hubTables)
        );

        long elapsed = System.currentTimeMillis() - start;
        log.info("Snapshot built in {}ms: {} tables, {} columns, {} relationships, {} domains",
            elapsed, tables.size(), totalCols, relationships.size(), domains.size());

        return snapshot;
    }
}
