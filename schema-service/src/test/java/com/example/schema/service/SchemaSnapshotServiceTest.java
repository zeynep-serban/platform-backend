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
 * break the snapshot. This pins that contract for {@code extractObjects}:
 * extraction failure → empty {@code objects} + snapshot still built;
 * success → objects carried through. Other collaborators are left as Mockito
 * defaults (empty collections), isolating the object-inventory wiring.
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
}
