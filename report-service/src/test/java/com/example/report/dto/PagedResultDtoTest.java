package com.example.report.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-0.5a (Codex thread 019e2c61 post-impl §High): regression coverage
 * for {@link PagedResultDto}'s {@code grandTotalRow} null-value
 * tolerance. The JDBC aggregate row legitimately carries NULL values
 * (empty filter set → SUM/AVG/STDEV null; weightedavg denominator zero
 * → null; percentile over empty set → null). The canonical constructor
 * must tolerate these without NPE so the dominant grouped flow keeps
 * rendering.
 */
class PagedResultDtoTest {

    @Nested
    @DisplayName("grandTotalRow null-value tolerance")
    class GrandTotalRowNullTolerance {

        @Test
        @DisplayName("accepts a map with null aggregate values without NPE")
        void acceptsNullValueMap() {
            LinkedHashMap<String, Object> aggRow = new LinkedHashMap<>();
            aggRow.put("amount", null);
            aggRow.put("qty", 42L);
            aggRow.put("median_price", null);
            aggRow.put("weighted", null);

            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50,
                    List.of(), List.<PivotResultColumnDto>of(), aggRow);

            assertNotNull(dto.grandTotalRow());
            assertEquals(4, dto.grandTotalRow().size());
            assertNull(dto.grandTotalRow().get("amount"));
            assertEquals(42L, dto.grandTotalRow().get("qty"));
            assertNull(dto.grandTotalRow().get("median_price"));
            assertNull(dto.grandTotalRow().get("weighted"));
        }

        @Test
        @DisplayName("empty map collapses to null so @JsonInclude(NON_NULL) omits the field")
        void emptyMapCollapsesToNull() {
            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50,
                    List.of(), List.<PivotResultColumnDto>of(),
                    new LinkedHashMap<>());

            assertNull(dto.grandTotalRow());
        }

        @Test
        @DisplayName("immutable view rejects post-construction mutation")
        void immutableViewRejectsMutation() {
            LinkedHashMap<String, Object> aggRow = new LinkedHashMap<>();
            aggRow.put("amount", 100.0);

            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50,
                    List.of(), List.<PivotResultColumnDto>of(), aggRow);

            assertThrows(UnsupportedOperationException.class,
                    () -> dto.grandTotalRow().put("evil", "injection"));
        }

        @Test
        @DisplayName("null input stays null (no NPE)")
        void nullInputStaysNull() {
            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50,
                    List.of(), List.<PivotResultColumnDto>of(), null);

            assertNull(dto.grandTotalRow());
        }

        @Test
        @DisplayName("Jackson serializes a populated grandTotalRow")
        void serializesPopulatedGrandTotalRow() throws Exception {
            LinkedHashMap<String, Object> aggRow = new LinkedHashMap<>();
            aggRow.put("amount", 100.0);
            aggRow.put("qty", null);

            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50,
                    List.of(), List.<PivotResultColumnDto>of(), aggRow);

            String json = new ObjectMapper().writeValueAsString(dto);

            assertTrue(json.contains("\"grandTotalRow\""),
                    "populated grandTotalRow must serialize");
            assertTrue(json.contains("\"amount\":100.0"),
                    "non-null aggregate value must round-trip");
            assertTrue(json.contains("\"qty\":null"),
                    "null aggregate value must round-trip as JSON null");
        }

        @Test
        @DisplayName("Jackson omits absent grandTotalRow (NON_NULL include)")
        void omitsAbsentGrandTotalRow() throws Exception {
            PagedResultDto<Map<String, Object>> dto = new PagedResultDto<>(
                    List.of(), 0L, 1, 50);

            String json = new ObjectMapper().writeValueAsString(dto);

            assertFalse(json.contains("grandTotalRow"),
                    "non-grouped flat response must not surface the field");
        }
    }
}
