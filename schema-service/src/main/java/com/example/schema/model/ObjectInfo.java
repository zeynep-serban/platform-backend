package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Authoritative database object extracted from MSSQL {@code sys.objects} +
 * {@code sys.extended_properties} — {@code authoritative_mssql} truth tier
 * (ADR-0020 §2.3, capability M1 — Codex 019e3270).
 *
 * <p>Phase B1-5. The complete schema-scoped object catalog: user tables,
 * views, stored procedures, scalar / table-valued functions, triggers and
 * synonyms ({@code is_ms_shipped = 0}). This is <strong>metadata only</strong>
 * — procedure / function / trigger <em>bodies</em> are capability M8 (B2
 * scope) and are NOT extracted here.
 *
 * <p>A table appears here AND in {@link SchemaSnapshot#tables()}: {@code tables}
 * carries column / row-count structure, {@code objects} carries object-level
 * {@code create / modify / owner / description / type}. A consumer cross-refs
 * by {@code (schema, name, objectType)} — that triple is the portable identity
 * and resolves the {@code dbo.X} vs {@code workcube.X} ambiguity.
 *
 * <p>{@code objectId} is the MSSQL {@code sys.objects.object_id}: a transient
 * per-database catalog id, NOT a stable cross-environment identifier — it is
 * carried for audit / catalog-join trace only, never for portable identity.
 *
 * <p>{@code createDate} / {@code modifyDate} are {@link LocalDateTime} (SQL
 * Server {@code datetime} is timezone-free) — no zone is imposed on the wire
 * contract.
 */
public record ObjectInfo(
    String name,
    String schema,
    String objectType,
    Integer objectId,
    String owner,
    LocalDateTime createDate,
    LocalDateTime modifyDate,
    Map<String, String> extendedProperties
) {

    /**
     * The {@code MS_Description} extended property (the SSMS "Description"),
     * or {@code null} when absent. Convenience over {@link #extendedProperties()}
     * — {@code @JsonIgnore} keeps it out of the wire contract; the full map is
     * the serialized shape.
     */
    @JsonIgnore
    public String description() {
        return extendedProperties == null ? null : extendedProperties.get("MS_Description");
    }
}
