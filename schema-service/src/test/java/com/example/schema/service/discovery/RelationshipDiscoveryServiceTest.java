package com.example.schema.service.discovery;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 portability (Codex 019e2d7d AGREE — quick win 3+4/5): the FK
 * heuristic maps are config-driven. These tests use the package-private
 * test constructor that injects the alias / common-FK maps directly,
 * keeping the discovery assertions independent of JSON loading (the
 * loader itself is covered by {@link FkHeuristicMapLoaderTest}).
 */
class RelationshipDiscoveryServiceTest {

    private RelationshipDiscoveryService service;

    @BeforeEach
    void setUp() {
        // Minimal heuristic maps covering exactly what the cases below need.
        service = new RelationshipDiscoveryService(
                Map.of("ACC_EMPLOYEE_ID", "EMPLOYEES"),   // alias_pattern
                Map.of("COMPANY_ID", "COMPANY"));          // common_fk
    }

    @Test
    void discoversNameMatchRelationships() {
        var tables = Map.of(
            "COMPANY", new TableInfo("COMPANY", "dbo", List.of(
                new ColumnInfo("COMPANY_ID", "int", 4, false, true, true, 1)
            )),
            "ORDERS", new TableInfo("ORDERS", "dbo", List.of(
                new ColumnInfo("ORDER_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("COMPANY_ID", "int", 4, false, false, false, 2)
            ))
        );

        List<Relationship> rels = service.discoverAll(tables, Map.of());
        assertFalse(rels.isEmpty(), "Should discover at least one relationship");
        assertTrue(rels.stream().anyMatch(r ->
            r.fromTable().equals("ORDERS") && r.toTable().equals("COMPANY")),
            "Should find ORDERS -> COMPANY");
    }

    @Test
    void discoversAliasPatterns() {
        var tables = Map.of(
            "EMPLOYEES", new TableInfo("EMPLOYEES", "dbo", List.of(
                new ColumnInfo("EMPLOYEE_ID", "int", 4, false, true, true, 1)
            )),
            "ACCOUNT_CARD", new TableInfo("ACCOUNT_CARD", "dbo", List.of(
                new ColumnInfo("CARD_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("ACC_EMPLOYEE_ID", "int", 4, true, false, false, 2)
            ))
        );

        List<Relationship> rels = service.discoverAll(tables, Map.of());
        assertTrue(rels.stream().anyMatch(r ->
            r.fromTable().equals("ACCOUNT_CARD") && r.fromColumn().equals("ACC_EMPLOYEE_ID")),
            "Should discover alias ACC_EMPLOYEE_ID -> EMPLOYEES");
    }

    @Test
    void deduplicatesMultiSourceRelationships() {
        var tables = Map.of(
            "COMPANY", new TableInfo("COMPANY", "dbo", List.of(
                new ColumnInfo("COMPANY_ID", "int", 4, false, true, true, 1)
            )),
            "INVOICE", new TableInfo("INVOICE", "dbo", List.of(
                new ColumnInfo("INVOICE_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("COMPANY_ID", "int", 4, false, false, false, 2)
            ))
        );

        List<Relationship> rels = service.discoverAll(tables, Map.of());
        // COMPANY_ID should be found by both name_match and common_fk
        long companyRels = rels.stream()
            .filter(r -> r.fromTable().equals("INVOICE") && r.toTable().equals("COMPANY"))
            .count();
        assertEquals(1, companyRels, "Should deduplicate to single relationship");

        // Multi-source should have boosted confidence
        Relationship rel = rels.stream()
            .filter(r -> r.fromTable().equals("INVOICE") && r.toTable().equals("COMPANY"))
            .findFirst().orElseThrow();
        assertTrue(rel.confidence() > 0.9, "Multi-source should have high confidence");
    }

    @Test
    void emptyTablesReturnEmptyRelationships() {
        List<Relationship> rels = service.discoverAll(Map.of(), Map.of());
        assertTrue(rels.isEmpty());
    }

    @Test
    void emptyHeuristicMaps_discoveryDegradesToNameMatchOnly() {
        // Codex 019e2d7d note 4: an intentionally empty heuristic map
        // switches that technique off — discovery degrades to name_match
        // rather than failing. ACC_EMPLOYEE_ID has no name_match base
        // table, so with an empty alias map it yields no relationship.
        RelationshipDiscoveryService bare =
                new RelationshipDiscoveryService(Map.of(), Map.of());

        var tables = Map.of(
            "EMPLOYEES", new TableInfo("EMPLOYEES", "dbo", List.of(
                new ColumnInfo("EMPLOYEE_ID", "int", 4, false, true, true, 1)
            )),
            "ACCOUNT_CARD", new TableInfo("ACCOUNT_CARD", "dbo", List.of(
                new ColumnInfo("CARD_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("ACC_EMPLOYEE_ID", "int", 4, true, false, false, 2)
            ))
        );

        List<Relationship> rels = bare.discoverAll(tables, Map.of());
        assertTrue(rels.stream().noneMatch(r -> r.fromColumn().equals("ACC_EMPLOYEE_ID")),
            "Empty alias map → ACC_EMPLOYEE_ID alias relationship must not appear");
    }

    // ---------------------------------------------------------------
    // Phase B1-2 — authoritative FK compat layer (Codex 019e2d7d / ADR-0020)
    // ---------------------------------------------------------------

    @Test
    void authoritativeSingleColumnFk_addedAsCompatRelationship() {
        // OUR_COMPANY_REF matches no heuristic → only the FK compat layer
        // can produce this relationship.
        var tables = Map.of(
            "COMPANY", new TableInfo("COMPANY", "dbo", List.of(
                new ColumnInfo("COMPANY_ID", "int", 4, false, true, true, 1))),
            "ORDERS", new TableInfo("ORDERS", "dbo", List.of(
                new ColumnInfo("ORDER_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("OUR_COMPANY_REF", "int", 4, false, false, false, 2))));
        ForeignKeyInfo fk = new ForeignKeyInfo("FK_ORDERS_COMPANY",
            "dbo", "ORDERS", List.of("OUR_COMPANY_REF"),
            "dbo", "COMPANY", List.of("COMPANY_ID"),
            false, false, "NO_ACTION", "NO_ACTION");

        List<Relationship> rels = service.discoverAll(tables, Map.of(), List.of(fk));

        Relationship fkRel = rels.stream()
            .filter(r -> "fk_constraint".equals(r.source()))
            .findFirst().orElseThrow();
        assertEquals("ORDERS", fkRel.fromTable());
        assertEquals("OUR_COMPANY_REF", fkRel.fromColumn());
        assertEquals("COMPANY", fkRel.toTable());
        assertEquals(1.0, fkRel.confidence(), 1e-9);
    }

    @Test
    void compositeFk_notFlattenedIntoRelationships() {
        var tables = Map.of(
            "PARENT", new TableInfo("PARENT", "dbo", List.of(
                new ColumnInfo("KEY_A", "int", 4, false, false, true, 1),
                new ColumnInfo("KEY_B", "int", 4, false, false, true, 2))),
            "CHILD", new TableInfo("CHILD", "dbo", List.of(
                new ColumnInfo("REF_A", "int", 4, false, false, false, 1),
                new ColumnInfo("REF_B", "int", 4, false, false, false, 2))));
        ForeignKeyInfo composite = new ForeignKeyInfo("FK_COMPOSITE",
            "dbo", "CHILD", List.of("REF_A", "REF_B"),
            "dbo", "PARENT", List.of("KEY_A", "KEY_B"),
            false, false, "NO_ACTION", "NO_ACTION");

        List<Relationship> rels = service.discoverAll(tables, Map.of(), List.of(composite));

        assertTrue(rels.stream().noneMatch(r -> "fk_constraint".equals(r.source())),
            "composite FK must stay in the ForeignKeyInfo inventory only, "
                + "not flattened into the relationship compat list");
    }

    @Test
    void crossSchemaForeignKey_staysInventoryOnly_notCompatRelationship() {
        // Codex 019e2d7d REVISE: the FK target COMPANY is NOT in this
        // snapshot's tables (cross-schema). The single-column FK must NOT
        // become a compat relationship — that would inject a graph node
        // outside the snapshot.
        var tables = Map.of(
            "ORDERS", new TableInfo("ORDERS", "dbo", List.of(
                new ColumnInfo("ORDER_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("OUR_COMPANY_REF", "int", 4, false, false, false, 2))));
        ForeignKeyInfo crossSchemaFk = new ForeignKeyInfo("FK_ORDERS_SHARED_COMPANY",
            "dbo", "ORDERS", List.of("OUR_COMPANY_REF"),
            "shared", "COMPANY", List.of("ID"),   // COMPANY absent from `tables`
            false, false, "NO_ACTION", "NO_ACTION");

        List<Relationship> rels = service.discoverAll(tables, Map.of(), List.of(crossSchemaFk));

        assertTrue(rels.stream().noneMatch(r -> "fk_constraint".equals(r.source())),
            "FK to a table outside the snapshot must stay inventory-only, "
                + "not become a compat relationship");
    }

    @Test
    void heuristicAndAuthoritativeFk_dedupedWithMultiSource() {
        // COMPANY_ID → COMPANY: found by name_match + common_fk heuristic
        // AND the authoritative FK → must dedup to one multi-source rel.
        var tables = Map.of(
            "COMPANY", new TableInfo("COMPANY", "dbo", List.of(
                new ColumnInfo("COMPANY_ID", "int", 4, false, true, true, 1))),
            "INVOICE", new TableInfo("INVOICE", "dbo", List.of(
                new ColumnInfo("INVOICE_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("COMPANY_ID", "int", 4, false, false, false, 2))));
        ForeignKeyInfo fk = new ForeignKeyInfo("FK_INVOICE_COMPANY",
            "dbo", "INVOICE", List.of("COMPANY_ID"),
            "dbo", "COMPANY", List.of("COMPANY_ID"),
            false, false, "NO_ACTION", "NO_ACTION");

        List<Relationship> rels = service.discoverAll(tables, Map.of(), List.of(fk));

        long count = rels.stream()
            .filter(r -> r.fromTable().equals("INVOICE") && r.toTable().equals("COMPANY"))
            .count();
        assertEquals(1, count, "heuristic + authoritative FK dedup to a single relationship");

        Relationship rel = rels.stream()
            .filter(r -> r.fromTable().equals("INVOICE") && r.toTable().equals("COMPANY"))
            .findFirst().orElseThrow();
        assertTrue(rel.multiSource(), "found by heuristic AND fk_constraint");
        assertTrue(rel.source().contains("fk_constraint"), "source set includes fk_constraint");
        assertEquals(1.0, rel.confidence(), 1e-9, "fk_constraint 1.0 + multi-source boost caps at 1.0");
    }
}
