package com.example.report.workcube;

import org.junit.jupiter.api.BeforeEach;
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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CompanyOptionsRepository}.
 *
 * <p>Codex 019dfb15 iter-2 absorb #5: covers the SQL-construction details
 * that matter for safety:
 * <ul>
 *   <li>{@code sys.schemas} probe runs before the UNION ALL, so missing
 *       tenant schemas don't 500 the catalog query.</li>
 *   <li>The UNION ALL only references schemas that actually exist.</li>
 *   <li>Empty allowlist short-circuits without touching the database.</li>
 *   <li>{@link DataAccessResourceFailureException} from MSSQL propagates
 *       (degraded mode → 503 at controller; cache won't poison).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyOptionsRepositoryTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void emptyAllowlistRange_returnsEmpty_withoutTouchingDb() {
        // min > max → IntStream.rangeClosed empty
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc, 5, 4);

        List<CompanyOptionsRepository.CompanyOption> result = repo.findAll();

        assertTrue(result.isEmpty());
        verify(jdbc, never()).queryForList(any(String.class), eq(String.class));
        verify(jdbc, never()).query(any(String.class), any(RowMapper.class));
    }

    @Test
    void noSchemasExist_returnsEmpty_andSkipsUnion() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc, 1, 3);
        // sys.schemas probe returns nothing
        when(jdbc.queryForList(contains("sys.schemas"), eq(String.class)))
                .thenReturn(Collections.emptyList());

        List<CompanyOptionsRepository.CompanyOption> result = repo.findAll();

        assertTrue(result.isEmpty());
        // UNION ALL must not run when no schemas exist
        verify(jdbc, never()).query(contains("UNION ALL"), any(RowMapper.class));
    }

    @Test
    void someSchemasExist_unionsOnlyExistingOnes() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc, 1, 3);
        // 1 + 3 exist, 2 missing
        when(jdbc.queryForList(contains("sys.schemas"), eq(String.class)))
                .thenReturn(List.of("workcube_mikrolink_1", "workcube_mikrolink_3"));
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenReturn(List.of(
                        new CompanyOptionsRepository.CompanyOption(1, "ARC", "ARÇELİK A.Ş."),
                        new CompanyOptionsRepository.CompanyOption(3, "VST", "VESTEL A.Ş.")
                ));

        List<CompanyOptionsRepository.CompanyOption> result = repo.findAll();

        assertEquals(2, result.size());
        // Verify the actual UNION SQL only references schemas 1 and 3.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("workcube_mikrolink_1"), "schema 1 must be in UNION");
        assertTrue(sql.contains("workcube_mikrolink_3"), "schema 3 must be in UNION");
        assertTrue(!sql.contains("workcube_mikrolink_2"),
                "schema 2 (missing) must NOT be in UNION");
        assertTrue(sql.contains("UNION ALL"), "must use UNION ALL across schemas");
        assertTrue(sql.contains("OUR_COMPANY"), "must select from OUR_COMPANY");
    }

    @Test
    void mssqlDownOnSysSchemasProbe_propagates() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc, 1, 3);
        when(jdbc.queryForList(contains("sys.schemas"), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThrows(DataAccessResourceFailureException.class, repo::findAll,
                "sys.schemas failure must propagate so controller returns 503 and cache won't poison");
    }

    @Test
    void mssqlDownOnUnionQuery_propagates() {
        CompanyOptionsRepository repo = new CompanyOptionsRepository(jdbc, 1, 3);
        when(jdbc.queryForList(contains("sys.schemas"), eq(String.class)))
                .thenReturn(List.of("workcube_mikrolink_1"));
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("read timeout"));

        assertThrows(DataAccessResourceFailureException.class, repo::findAll,
                "UNION ALL failure must propagate");
    }
}
