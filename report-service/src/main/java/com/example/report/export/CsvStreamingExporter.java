package com.example.report.export;

import com.example.report.query.SqlBuilder;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class CsvStreamingExporter {

    private static final String SEPARATOR = ";";
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * PR-0.5b backward-compat shim: legacy flat-export call sites
     * still pass a {@code List<String>} where field == header. The
     * canonical implementation is
     * {@link #exportWithColumns(NamedParameterJdbcTemplate,
     * SqlBuilder.BuiltQuery, List, OutputStream)} which honours the
     * {@link ExportColumn#field()} / {@link ExportColumn#header()}
     * split needed by grouped + pivot export.
     */
    public static void export(NamedParameterJdbcTemplate jdbc,
                               SqlBuilder.BuiltQuery query,
                               List<String> columns,
                               OutputStream out) {
        List<ExportColumn> exportColumns = columns.stream()
                .map(ExportColumn::of)
                .toList();
        exportWithColumns(jdbc, query, exportColumns, out);
    }

    public static void exportWithColumns(NamedParameterJdbcTemplate jdbc,
                                          SqlBuilder.BuiltQuery query,
                                          List<ExportColumn> columns,
                                          OutputStream out) {
        try {
            out.write(UTF8_BOM);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

            // Header row uses the human-readable label, not the SQL
            // alias. Flat export's ExportColumn.of(field) collapses
            // header == field so the legacy bytewise output stays
            // identical when the new wrapper is in use.
            StringBuilder headerLine = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) headerLine.append(SEPARATOR);
                headerLine.append(escapeCell(columns.get(i).header()));
            }
            writer.println(headerLine);

            jdbc.query(query.sql(), query.params(), rs -> {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(SEPARATOR);
                    }
                    Object val = rs.getObject(columns.get(i).field());
                    if (val != null) {
                        line.append(escapeCell(val.toString()));
                    }
                }
                writer.println(line);
            });

            writer.flush();
        } catch (Exception e) {
            throw new RuntimeException("CSV export failed", e);
        }
    }

    /**
     * PR-0.5b (Codex 019e2cd7 risk #5): minimum CSV formula injection
     * defence. Excel/Calc evaluate any leading {@code =, +, -, @, \t,
     * \r} character as a formula; we prefix vulnerable values with a
     * single quote so they render as literal text. Combined with the
     * existing separator/quote/newline quoting rules.
     */
    private static String escapeCell(String raw) {
        if (raw == null) return "";
        String str = raw;
        if (!str.isEmpty()) {
            char c = str.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                str = "'" + str;
            }
        }
        // Codex 019e2cd7 post-impl Finding #5: CR inside a value must
        // be wrapped in quotes too, otherwise the formula-injection
        // single-quote prefix leaves a CR free to break the record
        // boundary. Tab is included for safety so a tab-delimited
        // consumer cannot be confused either.
        if (str.contains(SEPARATOR)
                || str.contains("\"")
                || str.contains("\n")
                || str.contains("\r")
                || str.contains("\t")) {
            str = "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
