package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ObjectInfo;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.discovery.RelationshipDiscoveryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-5 (capability M1 — Codex 019e3270): {@code SchemaSnapshotService}
 * integration guard for the B1 authoritative inventories. Each {@code sys.*}
 * extraction is wrapped in a non-fatal try/catch — a failing read must NOT
 * break the snapshot. This pins that contract for {@code extractObjects},
 * {@code extractStorage} and {@code extractChangeData}: a failed extraction →
 * empty inventory + snapshot still built; success → the inventory is carried
 * through. Other collaborators are left as Mockito defaults (empty collections).
 */
class SchemaSnapshotServiceTest {

    private final SchemaExtractService extract = mock(SchemaExtractService.class);
    private final RelationshipDiscoveryService discovery = mock(RelationshipDiscoveryService.class);
    private final DomainClusteringService clustering = mock(DomainClusteringService.class);
    private final SchemaSnapshotService service =
            new SchemaSnapshotService(extract, discovery, clustering);

    @Test
    void extractObjectsThrows_snapshotStillBuilt_objectsEmpty() {
        when(extract.extractObjects(anyString()))
                .thenThrow(new RuntimeException("sys.objects unavailable"));

        SchemaSnapshot snap = service.buildSnapshot("workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.objects()).isEmpty();
    }

    @Test
    void extractObjectsSucceeds_objectsCarriedIntoSnapshot() {
        ObjectInfo obj = new ObjectInfo(
                "INVOICE", "dbo", "USER_TABLE", 100, "dbo",
                LocalDateTime.of(2020, 1, 1, 10, 0),
                LocalDateTime.of(2021, 6, 15, 14, 30), Map.of());
        when(extract.extractObjects(anyString())).thenReturn(List.of(obj));

        SchemaSnapshot snap = service.buildSnapshot("workcube_mikrolink");

        assertThat(snap.objects()).containsExactly(obj);
    }

    @Test
    void extractStorageThrows_snapshotStillBuilt_storageEmpty() {
        // sys.dm_db_partition_stats needs VIEW DATABASE STATE; a permission
        // failure must not collapse the snapshot — storage stays empty.
        when(extract.extractStorage(anyString()))
                .thenThrow(new RuntimeException("VIEW DATABASE STATE denied"));

        SchemaSnapshot snap = service.buildSnapshot("workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.storage()).isEmpty();
    }

    @Test
    void extractChangeDataThrows_snapshotStillBuilt_changeDataEmpty() {
        when(extract.extractChangeData(anyString()))
                .thenThrow(new RuntimeException("sys.change_tracking_tables unavailable"));

        SchemaSnapshot snap = service.buildSnapshot("workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.changeData()).isEmpty();
    }
}
