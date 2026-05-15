package com.example.report.export;

import com.example.report.query.SqlBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CsvStreamingExporter}.
 * Verifies CSV format: UTF-8 BOM, semicolon separator, quoting rules.
 */
@ExtendWith(MockitoExtension.class)
class CsvStreamingExporterTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String SEPARATOR = ";";

    private final List<String> columns = List.of("id", "name", "city");

    private SqlBuilder.BuiltQuery buildQuery() {
        return new SqlBuilder.BuiltQuery("SELECT id, name, city FROM test", new MapSqlParameterSource());
    }

    @Test
    void export_withDataRows_producesValidCsv() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(mockResultSet(1, "Alice", "Istanbul"));
            handler.processRow(mockResultSet(2, "Bob", "Ankara"));
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        byte[] bytes = out.toByteArray();
        // Verify UTF-8 BOM
        assertTrue(bytes.length >= 3);
        assertEquals(UTF8_BOM[0], bytes[0]);
        assertEquals(UTF8_BOM[1], bytes[1]);
        assertEquals(UTF8_BOM[2], bytes[2]);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\\r?\\n");

        // Header line
        assertEquals("id;name;city", lines[0]);
        // Data rows
        assertEquals("1;Alice;Istanbul", lines[1]);
        assertEquals("2;Bob;Ankara", lines[2]);
    }

    @Test
    void export_emptyResultSet_producesHeaderOnly() {
        doNothing().when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals(1, lines.length, "Only header line expected");
        assertEquals("id;name;city", lines[0]);
    }

    @Test
    void export_nullValues_producesEmptyFields() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn(null);
            when(rs.getObject("city")).thenReturn(null);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals("1;;", lines[1], "Null values should produce empty fields");
    }

    @Test
    void export_valueContainingSeparator_isQuoted() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Alice;Bob");
            when(rs.getObject("city")).thenReturn("Normal");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals("1;\"Alice;Bob\";Normal", lines[1]);
    }

    @Test
    void export_valueContainingQuotes_areEscaped() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Say \"hello\"");
            when(rs.getObject("city")).thenReturn("OK");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        // Quotes inside value are doubled and whole field is wrapped in quotes
        assertEquals("1;\"Say \"\"hello\"\"\";OK", lines[1]);
    }

    @Test
    void export_valueContainingNewline_isQuoted() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Line1\nLine2");
            when(rs.getObject("city")).thenReturn("Simple");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        // The value with newline should be quoted
        assertTrue(csv.contains("\"Line1\nLine2\""), "Newline-containing value should be quoted");
    }

    @Test
    void export_usesSemicolonSeparator() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(mockResultSet(1, "A", "B"));
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        // Verify semicolon is used, not comma
        assertTrue(lines[0].contains(";"), "Header should use semicolon separator");
        assertFalse(lines[0].contains(","), "Header should not use comma separator");
    }

    @Test
    void export_jdbcThrows_wrapsInRuntimeException() {
        doThrow(new RuntimeException("DB error"))
                .when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                CsvStreamingExporter.export(jdbc, buildQuery(), columns, out));
        assertTrue(ex.getMessage().contains("CSV export failed") || ex.getMessage().contains("DB error"));
    }

    // ── PR-0.5b: ExportColumn header/field split + CSV formula injection defence ──

    @Test
    void exportWithColumns_usesHeaderForRowOneAndFieldForResultSetKey() throws Exception {
        // PR-0.5b: header is user-facing label (registered display
        // name); field is the SQL alias used as ResultSet key.
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("amount")).thenReturn(1000.0);
            when(rs.getObject("qty")).thenReturn(5);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.exportWithColumns(jdbc, buildQuery(),
                List.of(
                        new ExportColumn("amount", "SUM(Tutar TL)"),
                        new ExportColumn("qty", "AVG(Adet)")),
                out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");
        // Header uses display labels — must be escaped because of paren
        assertEquals("SUM(Tutar TL);AVG(Adet)", lines[0]);
        // Data row reads via SQL alias key
        assertEquals("1000.0;5", lines[1]);
    }

    @Test
    void exportWithColumns_leadingCRAndTabWrappedInQuotes() throws Exception {
        // Codex 019e2cd7 post-impl Finding #5: CR/tab inside a value
        // can break CSV record/cell boundaries even after the
        // formula-injection prefix. The escape rule wraps them in
        // double quotes too.
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("cr_first")).thenReturn("\rformula");
            when(rs.getObject("tab_first")).thenReturn("\tformula");
            when(rs.getObject("cr_mid")).thenReturn("a\rb");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.exportWithColumns(jdbc, buildQuery(),
                List.of(
                        new ExportColumn("cr_first", "CRFirst"),
                        new ExportColumn("tab_first", "TabFirst"),
                        new ExportColumn("cr_mid", "CRMid")),
                out);

        String csv = extractCsvContent(out);
        // Every CR / tab value is wrapped in quotes so the CSV record
        // boundary stays intact even when the formula-injection
        // single-quote prefix is the only leading defence.
        assertTrue(csv.contains("\"'\rformula\""),
                "leading CR value must be quote-wrapped (csv was: " + csv + ")");
        assertTrue(csv.contains("\"'\tformula\""),
                "leading tab value must be quote-wrapped");
        assertTrue(csv.contains("\"a\rb\""),
                "mid-string CR must trigger quote-wrap");
    }

    @Test
    void exportWithColumns_csvFormulaInjectionPrefixedWithSingleQuote() throws Exception {
        // PR-0.5b (Codex 019e2cd7 risk #5): leading =/+/-/@/tab/CR must
        // be neutralised so Excel does not evaluate them as formulas.
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("note")).thenReturn("=cmd|'/c calc'!A1");
            when(rs.getObject("plus")).thenReturn("+1+1");
            when(rs.getObject("at")).thenReturn("@SUM(A1)");
            when(rs.getObject("normal")).thenReturn("Hello");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.exportWithColumns(jdbc, buildQuery(),
                List.of(
                        new ExportColumn("note", "Note"),
                        new ExportColumn("plus", "Plus"),
                        new ExportColumn("at", "At"),
                        new ExportColumn("normal", "Normal")),
                out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");
        // Each formula-vulnerable value is prefixed with a single
        // quote so Excel treats it as text, not a formula. Values
        // without separator/quote/newline don't need extra
        // quote-wrapping.
        assertTrue(lines[1].startsWith("'=cmd"),
                "leading '=' must be prefixed with single quote (line was: " + lines[1] + ")");
        assertTrue(lines[1].contains(";'+1+1"),
                "leading '+' must be prefixed with single quote");
        assertTrue(lines[1].contains(";'@SUM(A1)"),
                "leading '@' must be prefixed with single quote");
        assertTrue(lines[1].endsWith("Hello"),
                "non-formula value must round-trip unchanged");
    }

    private ResultSet mockResultSet(Object id, String name, String city) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("name")).thenReturn(name);
        when(rs.getObject("city")).thenReturn(city);
        return rs;
    }

    private String extractCsvContent(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        // Skip UTF-8 BOM (3 bytes)
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
