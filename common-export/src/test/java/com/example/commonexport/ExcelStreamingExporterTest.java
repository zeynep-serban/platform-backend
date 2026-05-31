package com.example.commonexport;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ExcelStreamingExporter}.
 * Verifies Excel generation via SXSSFWorkbook with mocked JDBC results.
 *
 * <p>Migrated from report-service {@code ExcelStreamingExporterTest}
 * during the {@code common-export} extraction (board #1154); the query
 * type changed from {@code SqlBuilder.BuiltQuery} to {@link ExportQuery},
 * every behaviour assertion is unchanged.
 */
@ExtendWith(MockitoExtension.class)
class ExcelStreamingExporterTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private final List<String> columns = List.of("id", "name", "amount");

    private ExportQuery buildQuery() {
        return new ExportQuery("SELECT id, name, amount FROM test", new MapSqlParameterSource());
    }

    @Test
    void export_withDataRows_producesValidExcel() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            // Row 1
            ResultSet rs1 = mockResultSet(1, "Alice", 100.50);
            handler.processRow(rs1);
            // Row 2
            ResultSet rs2 = mockResultSet(2, "Bob", 200.75);
            handler.processRow(rs2);
            return null;
        }).when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelStreamingExporter.export(jdbc, buildQuery(), columns, "TestSheet", out);

        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0, "Output should not be empty");

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(1, workbook.getNumberOfSheets());
            Sheet sheet = workbook.getSheet("TestSheet");
            assertNotNull(sheet, "Sheet 'TestSheet' should exist");

            // Header row
            Row header = sheet.getRow(0);
            assertNotNull(header);
            assertEquals("id", header.getCell(0).getStringCellValue());
            assertEquals("name", header.getCell(1).getStringCellValue());
            assertEquals("amount", header.getCell(2).getStringCellValue());

            // Data row 1
            Row row1 = sheet.getRow(1);
            assertNotNull(row1);
            assertEquals(1.0, row1.getCell(0).getNumericCellValue());
            assertEquals("Alice", row1.getCell(1).getStringCellValue());
            assertEquals(100.50, row1.getCell(2).getNumericCellValue());

            // Data row 2
            Row row2 = sheet.getRow(2);
            assertNotNull(row2);
            assertEquals(2.0, row2.getCell(0).getNumericCellValue());
            assertEquals("Bob", row2.getCell(1).getStringCellValue());
            assertEquals(200.75, row2.getCell(2).getNumericCellValue());
        }
    }

    @Test
    void export_emptyResultSet_producesHeaderOnly() throws Exception {
        // jdbc.query callback is never invoked — empty result set
        doNothing().when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelStreamingExporter.export(jdbc, buildQuery(), columns, "Empty", out);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Empty");
            assertNotNull(sheet);

            Row header = sheet.getRow(0);
            assertNotNull(header);
            assertEquals(3, header.getLastCellNum());

            // No data rows
            assertNull(sheet.getRow(1));
        }
    }

    @Test
    void export_nullValues_producesBlankCells() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn(null);
            when(rs.getObject("amount")).thenReturn(null);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelStreamingExporter.export(jdbc, buildQuery(), columns, "NullTest", out);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("NullTest");
            Row row = sheet.getRow(1);
            assertNotNull(row);
            assertEquals(1.0, row.getCell(0).getNumericCellValue());
            assertEquals(CellType.BLANK, row.getCell(1).getCellType());
            assertEquals(CellType.BLANK, row.getCell(2).getCellType());
        }
    }

    @Test
    void export_nullSheetName_defaultsToReport() throws Exception {
        doNothing().when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelStreamingExporter.export(jdbc, buildQuery(), columns, null, out);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertNotNull(workbook.getSheet("Report"), "Default sheet name should be 'Report'");
        }
    }

    @Test
    void export_stringAndNumberTypes_handledCorrectly() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn("string-id");
            when(rs.getObject("name")).thenReturn(42L);
            when(rs.getObject("amount")).thenReturn(3.14f);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelStreamingExporter.export(jdbc, buildQuery(), columns, "TypeTest", out);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("TypeTest");
            Row row = sheet.getRow(1);
            // String value stays as string
            assertEquals("string-id", row.getCell(0).getStringCellValue());
            // Long is a Number -> double
            assertEquals(42.0, row.getCell(1).getNumericCellValue());
            // Float is a Number -> double
            assertEquals(3.14, row.getCell(2).getNumericCellValue(), 0.01);
        }
    }

    @Test
    void export_jdbcThrows_wrapsInRuntimeException() {
        doThrow(new RuntimeException("DB error"))
                .when(jdbc).query(any(String.class), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                ExcelStreamingExporter.export(jdbc, buildQuery(), columns, "Fail", out));
        assertTrue(ex.getMessage().contains("Excel export failed") || ex.getMessage().contains("DB error"));
    }

    private ResultSet mockResultSet(Object id, String name, double amount) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("name")).thenReturn(name);
        when(rs.getObject("amount")).thenReturn(amount);
        return rs;
    }
}
