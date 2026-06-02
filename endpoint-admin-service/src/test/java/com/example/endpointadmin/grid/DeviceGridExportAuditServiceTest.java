package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.endpointadmin.service.EndpointAuditService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * WEB-015 v2-a (Codex 019e8785 iter-3 P2 absorb) — audit metadata captor
 * test. Pins the metadata map the {@link DeviceGridExportAuditService}
 * passes through to {@link EndpointAuditService#record}: the existing
 * {@code format/exportMode/rowCount/columnCount} fields PLUS the new
 * iter-2 fields {@code schemaVersion} and {@code columnIdsHash} from
 * {@link DeviceGridColumns}.
 *
 * <p>Without this assertion the new audit fields could be lost in a
 * future refactor and a stale-or-tampered registry on a different pod
 * would no longer be detectable from the audit row alone.
 */
class DeviceGridExportAuditServiceTest {

    @Test
    void recordExportRequestedPinsSchemaVersionAndColumnIdsHash() {
        EndpointAuditService auditService = mock(EndpointAuditService.class);
        DeviceGridExportAuditService service = new DeviceGridExportAuditService(auditService);

        UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String subject = "user@example.com";

        service.recordExportRequested(tenant, subject, "csv", "VIEW", 42L, 7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(tenant),
                eq(null),
                eq(null),
                eq(DeviceGridExportAuditService.EVENT_TYPE),
                eq("EXPORT"),
                eq(subject),
                eq(null),
                metadataCaptor.capture(),
                eq(null),
                eq(null));

        Map<String, Object> metadata = metadataCaptor.getValue();
        // Pre-existing fields preserved.
        assertThat(metadata)
                .containsEntry("format", "csv")
                .containsEntry("exportMode", "VIEW")
                .containsEntry("rowCount", 42L)
                .containsEntry("columnCount", 7);
        // Codex iter-2 absorb: schema version + canonical column ids hash
        // so a stale/tampered registry is detectable from the audit row.
        assertThat(metadata)
                .containsEntry("schemaVersion", DeviceGridColumns.SCHEMA_VERSION)
                .containsEntry("columnIdsHash", DeviceGridColumns.columnIdsHash());
        // Drift detector: columnIdsHash must be the same lowercase hex
        // value the registry computes — not a stale literal.
        assertThat((String) metadata.get("columnIdsHash"))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void schemaVersionIsExactlyFive() {
        // WEB-015 v2-d (Codex 019e8a39 iter-1 plan AGREE + iter-2 must-fix #P1
        // absorb) — bump 4 to 5 (BE-024c DiffCache 9 cache-fed colIds).
        EndpointAuditService auditService = mock(EndpointAuditService.class);
        DeviceGridExportAuditService service = new DeviceGridExportAuditService(auditService);

        service.recordExportRequested(
                UUID.randomUUID(), "user", "csv", "RAW", 0L, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                any(), any(), any(), any(), any(), any(), any(),
                captor.capture(), any(), any());
        assertThat(captor.getValue()).containsEntry("schemaVersion", 5);
    }

    @Test
    void legacySchemaVersionRegression_NotFourThreeOrTwoAnymore() {
        // Drift detector: a silent revert from v5 to v2/v3/v4 must fail this
        // test immediately. The audit's `columnIdsHash` already changes with
        // every SCHEMA_VERSION bump (5+ new colIds in v3, 6 in v4, 9 in v5),
        // but pinning the integer here catches a partial revert where someone
        // bumps back the constant without touching the registry.
        EndpointAuditService auditService = mock(EndpointAuditService.class);
        DeviceGridExportAuditService service = new DeviceGridExportAuditService(auditService);

        service.recordExportRequested(
                UUID.randomUUID(), "user", "csv", "RAW", 0L, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                any(), any(), any(), any(), any(), any(), any(),
                captor.capture(), any(), any());
        assertThat(captor.getValue()).doesNotContainEntry("schemaVersion", 4);
        assertThat(captor.getValue()).doesNotContainEntry("schemaVersion", 3);
        assertThat(captor.getValue()).doesNotContainEntry("schemaVersion", 2);
    }
}
