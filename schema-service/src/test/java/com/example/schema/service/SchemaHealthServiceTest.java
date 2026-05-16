package com.example.schema.service;

import com.example.schema.model.*;
import com.example.schema.service.SchemaHealthService.HealthReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaHealthServiceTest {

    private SchemaHealthService service;

    @BeforeEach
    void setUp() {
        service = new SchemaHealthService();
    }

    @Test
    void healthySchemaGetsHighScore() {
        var tables = Map.of(
            "USERS", new TableInfo("USERS", "dbo", List.of(
                new ColumnInfo("USER_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("NAME", "nvarchar", 100, false, false, false, 2)
            )),
            "ORDERS", new TableInfo("ORDERS", "dbo", List.of(
                new ColumnInfo("ORDER_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("USER_ID", "int", 4, false, false, false, 2)
            ))
        );

        var rels = List.of(
            new Relationship("ORDERS", "USER_ID", "USERS", "USER_ID", 0.95, "common_fk")
        );

        var snapshot = SchemaSnapshot.builder()
            .version("1.0")
            .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "dbo", Instant.now(), 2, 4, 1, 1))
            .tables(tables)
            .relationships(rels)
            .domains(Map.of("MAIN", List.of("USERS", "ORDERS")))
            .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
            .build();

        HealthReport report = service.evaluate(snapshot);
        assertTrue(report.score() > 50, "Healthy schema should score > 50, got " + report.score());
        assertNotNull(report.grade());
    }

    @Test
    void orphanTablesReduceScore() {
        var tables = Map.of(
            "ORPHAN1", new TableInfo("ORPHAN1", "dbo", List.of(
                new ColumnInfo("ID", "int", 4, false, true, true, 1)
            )),
            "ORPHAN2", new TableInfo("ORPHAN2", "dbo", List.of(
                new ColumnInfo("ID", "int", 4, false, true, true, 1)
            ))
        );

        var snapshot = SchemaSnapshot.builder()
            .version("1.0")
            .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "dbo", Instant.now(), 2, 2, 0, 1))
            .tables(tables)
            .domains(Map.of("MAIN", List.of("ORPHAN1", "ORPHAN2")))
            .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
            .build();

        HealthReport report = service.evaluate(snapshot);
        assertTrue(report.totalIssues() >= 2, "Should have orphan issues");
        assertTrue(report.issues().stream().anyMatch(i -> i.rule().equals("orphan_table")));
    }

    @Test
    void tempTablesDetected() {
        var tables = Map.of(
            "EMPLOYEES_YEDEK", new TableInfo("EMPLOYEES_YEDEK", "dbo", List.of(
                new ColumnInfo("ID", "int", 4, false, true, true, 1)
            ))
        );

        var snapshot = SchemaSnapshot.builder()
            .version("1.0")
            .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "dbo", Instant.now(), 1, 1, 0, 1))
            .tables(tables)
            .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
            .build();

        HealthReport report = service.evaluate(snapshot);
        assertTrue(report.issues().stream().anyMatch(i -> i.rule().equals("temp_table")));
    }
}
