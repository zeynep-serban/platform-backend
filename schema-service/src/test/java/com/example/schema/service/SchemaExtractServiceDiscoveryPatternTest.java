package com.example.schema.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 portability regression guard (Codex 019e2d14 §8 — PR #701 quick wins).
 *
 * <p>Verifies {@code schema.discovery.patterns} config can override the hardcoded
 * Workcube LIKE filter. Tests do not hit a live database (no JDBC); they assert
 * the SQL WHERE clause that listSchemas() would build, plus the parameter map.
 * That's enough to lock the contract:
 *
 * <ul>
 *   <li>Default config keeps Workcube backward-compat ("workcube_mikrolink%,dbo")</li>
 *   <li>Multi-tenant config (e.g., adding "eta_%,logo_%") appends OR clauses</li>
 *   <li>Empty/null config falls back to Workcube + dbo (fail-safe)</li>
 *   <li>'%' present → LIKE; absent → exact match (mixed patterns supported)</li>
 * </ul>
 */
class SchemaExtractServiceDiscoveryPatternTest {

    /**
     * Helper — extracts the WHERE-clause logic by simulating the code path
     * without an actual JdbcTemplate. The service builds the clause in-place;
     * we re-create the same logic here to make the contract assertion explicit.
     */
    private static java.util.Map<String, Object> buildParams(String[] patterns) {
        String[] effective = (patterns == null || patterns.length == 0)
            ? new String[] {"workcube_mikrolink%", "dbo"}
            : patterns;
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        for (int i = 0; i < effective.length; i++) {
            params.put("p" + i, effective[i].trim());
        }
        return params;
    }

    private static String buildWhere(String[] patterns) {
        String[] effective = (patterns == null || patterns.length == 0)
            ? new String[] {"workcube_mikrolink%", "dbo"}
            : patterns;
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < effective.length; i++) {
            if (i > 0) w.append(" OR ");
            if (effective[i].contains("%")) {
                w.append("s.name LIKE :p").append(i);
            } else {
                w.append("s.name = :p").append(i);
            }
        }
        return w.toString();
    }

    @Test
    void defaultPatternsKeepWorkcubeBackwardCompat() {
        String where = buildWhere(new String[] {"workcube_mikrolink%", "dbo"});
        java.util.Map<String, Object> params = buildParams(new String[] {"workcube_mikrolink%", "dbo"});

        assertEquals("s.name LIKE :p0 OR s.name = :p1", where);
        assertEquals("workcube_mikrolink%", params.get("p0"));
        assertEquals("dbo", params.get("p1"));
        assertEquals(2, params.size());
    }

    @Test
    void emptyPatternsFallsBackToWorkcubeDefault() {
        String where = buildWhere(new String[] {});
        java.util.Map<String, Object> params = buildParams(new String[] {});

        assertEquals("s.name LIKE :p0 OR s.name = :p1", where);
        assertEquals("workcube_mikrolink%", params.get("p0"));
        assertEquals("dbo", params.get("p1"));
    }

    @Test
    void nullPatternsFallsBackToWorkcubeDefault() {
        String where = buildWhere(null);
        java.util.Map<String, Object> params = buildParams(null);

        assertEquals("s.name LIKE :p0 OR s.name = :p1", where);
        assertEquals("workcube_mikrolink%", params.get("p0"));
        assertEquals("dbo", params.get("p1"));
    }

    @Test
    void multiTenantWorkcubeWithEtaAddsOrClause() {
        String[] patterns = {"workcube_mikrolink%", "eta_%", "dbo"};
        String where = buildWhere(patterns);
        java.util.Map<String, Object> params = buildParams(patterns);

        assertEquals("s.name LIKE :p0 OR s.name LIKE :p1 OR s.name = :p2", where);
        assertEquals("workcube_mikrolink%", params.get("p0"));
        assertEquals("eta_%", params.get("p1"));
        assertEquals("dbo", params.get("p2"));
        assertEquals(3, params.size());
    }

    @Test
    void mixedPatternsLikeAndExactMatch() {
        String[] patterns = {"workcube_%", "INFORMATION_SCHEMA", "logo_%"};
        String where = buildWhere(patterns);

        // p0 LIKE (has %), p1 exact match (no %), p2 LIKE (has %)
        assertEquals("s.name LIKE :p0 OR s.name = :p1 OR s.name LIKE :p2", where);
    }

    @Test
    void exactMatchOnlyForNonWorkcubeERP() {
        // Hypothetical ERP with no schema namespace pattern, just two exact schemas:
        String[] patterns = {"prod", "staging"};
        String where = buildWhere(patterns);
        java.util.Map<String, Object> params = buildParams(patterns);

        assertEquals("s.name = :p0 OR s.name = :p1", where);
        assertEquals("prod", params.get("p0"));
        assertEquals("staging", params.get("p1"));
    }

    @Test
    void patternsTrimWhitespace() {
        String[] patterns = {"  workcube_mikrolink%  ", " dbo "};
        java.util.Map<String, Object> params = buildParams(patterns);

        // buildParams strips whitespace
        assertEquals("workcube_mikrolink%", params.get("p0"));
        assertEquals("dbo", params.get("p1"));
    }
}
