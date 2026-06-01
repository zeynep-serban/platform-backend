package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;

/**
 * Single source of truth for the {@code installedPossiblyTruncated} and
 * {@code pendingPossiblyTruncated} signals exposed on every admin-side
 * hotfix-posture DTO (Faz 22.5, AG-037-be — parallel to
 * {@link OutdatedSnapshotTruncation}).
 *
 * <h4>Rules</h4>
 *
 * <p>Installed:
 * <pre>
 *   installedPossiblyTruncated =
 *       installedTruncated == TRUE
 *    || (installedCount != null
 *        AND maxInstalled != null
 *        AND installedCount &gt;= maxInstalled)
 * </pre>
 *
 * <p>Pending:
 * <pre>
 *   pendingPossiblyTruncated =
 *       pendingTruncated == TRUE
 *    || (pendingTotalCount != null
 *        AND maxPending != null
 *        AND pendingTotalCount &gt;= maxPending)
 * </pre>
 *
 * <p>The {@code &gt;=} fallback is intentionally widened so an above-cap
 * aggregate count (e.g. a fleet rollup) cannot fail-open the hint —
 * same semantics as {@link OutdatedSnapshotTruncation}.
 *
 * <h4>Why the heuristic is still kept as a fallback</h4>
 *
 * <p>The DB columns {@code installed_truncated} and {@code pending_truncated}
 * are {@code BOOLEAN NOT NULL} (V22 migration) and the policy requires the
 * field on every ingest, so in steady state both flags are never null. The
 * bare count-vs-cap branch is a conservative belt-and-braces that keeps the
 * hint stable if a future migration ever relaxed the NOT NULL constraint, or
 * if a derived projection surfaces a null.
 */
public final class HotfixPostureSnapshotTruncation {

    private HotfixPostureSnapshotTruncation() {
        // utility — no instances
    }

    /**
     * @param snapshot the persisted snapshot (must not be {@code null}).
     * @return whether the installed-hotfix list should be rendered with a
     *         "possibly truncated" hint per the rule above.
     */
    public static boolean isInstalledPossiblyTruncated(EndpointHotfixPostureSnapshot snapshot) {
        if (Boolean.TRUE.equals(snapshot.getInstalledTruncated())) {
            return true;
        }
        Integer count = snapshot.getInstalledCount();
        Integer max = snapshot.getMaxInstalled();
        return count != null && max != null && count >= max;
    }

    /**
     * @param snapshot the persisted snapshot (must not be {@code null}).
     * @return whether the pending-updates list should be rendered with a
     *         "possibly truncated" hint per the rule above.
     */
    public static boolean isPendingPossiblyTruncated(EndpointHotfixPostureSnapshot snapshot) {
        if (Boolean.TRUE.equals(snapshot.getPendingTruncated())) {
            return true;
        }
        Integer count = snapshot.getPendingTotalCount();
        Integer max = snapshot.getMaxPending();
        return count != null && max != null && count >= max;
    }
}
