package com.example.endpointadmin.service;

import java.util.List;

/**
 * Service-layer result of a fleet-wide "latest snapshot per device"
 * fetch (Faz 22.5, #1146).
 *
 * <p>{@code truncated} means the tenant had MORE latest-per-device rows
 * than the configured cap; the caller MUST then treat the (empty)
 * {@code snapshots} list as "do not surface this group" rather than
 * "no snapshots exist", so a cap never reads as a real absence. The
 * truncated factory returns an empty list precisely so an over-cap group
 * carries no partial data a consumer could misread.
 *
 * @param <T> the snapshot entity type
 */
public record BulkLatestSnapshots<T>(List<T> snapshots, boolean truncated) {

    /** Over-cap result: intentionally empty list + {@code truncated=true}.
     *  Named {@code overCap} (not {@code truncated}) to avoid clashing with
     *  the record's {@code truncated()} component accessor. */
    public static <T> BulkLatestSnapshots<T> overCap() {
        return new BulkLatestSnapshots<>(List.of(), true);
    }

    /** In-cap result carrying the loaded snapshots. */
    public static <T> BulkLatestSnapshots<T> of(List<T> snapshots) {
        return new BulkLatestSnapshots<>(List.copyOf(snapshots), false);
    }
}
