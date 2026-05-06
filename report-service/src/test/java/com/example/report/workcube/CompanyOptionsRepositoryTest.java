package com.example.report.workcube;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CompanyOptionsRepository}.
 *
 * <p>Covers the two production-relevant behaviours:
 * <ul>
 *   <li>The query targets the canonical master schema
 *       ({@code [workcube_mikrolink].[OUR_COMPANY]}) with the correct
 *       column aliases — the PR #68 design used non-existent columns
 *       and 43-schema UNION; this regression guard pins the corrected
 *       SQL shape.</li>
 *   <li>{@link DataAccessResourceFailureException} from MSSQL propagates
 *       (degraded mode → 503 at controller; cache won't poison).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyOptionsRepositoryTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void findAll_queriesCanonicalOurCompanyTable_withCorrectColumns() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc);
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenReturn(List.of(
                        new CompanyOptionsRepository.CompanyOption(1, "ARC", "ARÇELİK A.Ş."),
                        new CompanyOptionsRepository.CompanyOption(7, "VST", "VESTEL A.Ş.")
                ));

        List<CompanyOptionsRepository.CompanyOption> result = repo.findAll();

        assertEquals(2, result.size());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        // Pin the exact table reference.
        assertTrue(sql.contains("[workcube_mikrolink].[OUR_COMPANY]"),
                "must target the canonical master schema's OUR_COMPANY, not the per-tenant schemas");
        // Pin the actual live column names (NICK_NAME with underscore — different
        // from what PR #68 originally guessed).
        assertTrue(sql.contains("COMP_ID"), "must select COMP_ID as id");
        assertTrue(sql.contains("NICK_NAME"), "must select NICK_NAME (with underscore)");
        assertTrue(sql.contains("COMPANY_NAME"), "must select COMPANY_NAME");
        // No UNION ALL needed — single table.
        assertTrue(!sql.contains("UNION ALL"),
                "single-schema design should not produce a UNION query");
    }

    @Test
    void findAll_orderingIsStable() {
        // ORDER BY COMP_ID is part of the contract — cache-friendly +
        // dropdown shows tenants in id order, which matches the user's
        // mental model of "Şirket #1, #2, ..."
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc);
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        repo.findAll();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY COMP_ID"),
                "must ORDER BY COMP_ID for deterministic dropdown ordering");
    }

    @Test
    void mssqlDown_propagatesException_forControllerToSurface503() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc);
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThrows(DataAccessResourceFailureException.class, repo::findAll,
                "must propagate so controller returns 503 and cache won't poison");
    }
}
