package com.example.report.export;

import com.example.report.query.SqlBuilder;
import java.io.OutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class ExcelStreamingExporter {

    private static final int FLUSH_WINDOW = 100;

    /**
     * PR-0.5b backward-compat shim: legacy flat-export call sites
     * still pass a {@code List<String>}. The canonical implementation
     * is {@link #exportWithColumns(NamedParameterJdbcTemplate,
     * SqlBuilder.BuiltQuery, List, String, OutputStream)}.
     */
    public static void export(NamedParameterJdbcTemplate jdbc,
                               SqlBuilder.BuiltQuery query,
                               List<String> columns,
                               String sheetName,
                               OutputStream out) {
        List<ExportColumn> exportColumns = columns.stream()
                .map(ExportColumn::of)
                .toList();
        exportWithColumns(jdbc, query, exportColumns, sheetName, out);
    }

    public static void exportWithColumns(NamedParameterJdbcTemplate jdbc,
                                          SqlBuilder.BuiltQuery query,
                                          List<ExportColumn> columns,
                                          String sheetName,
                                          OutputStream out) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(FLUSH_WINDOW)) {
            Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Report");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i).header());
            }

            int[] rowNum = {1};

            jdbc.query(query.sql(), query.params(), rs -> {
                Row row = sheet.createRow(rowNum[0]++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.createCell(i);
                    Object val = rs.getObject(columns.get(i).field());
                    if (val == null) {
                        cell.setBlank();
                    } else if (val instanceof Number num) {
                        cell.setCellValue(num.doubleValue());
                    } else {
                        cell.setCellValue(val.toString());
                    }
                }
            });

            workbook.write(out);
            workbook.dispose();
        } catch (Exception e) {
            throw new RuntimeException("Excel export failed", e);
        }
    }
}
