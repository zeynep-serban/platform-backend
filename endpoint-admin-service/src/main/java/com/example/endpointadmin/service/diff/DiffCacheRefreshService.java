package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-024c v2-c-pre-2-B diff cache refresh service (Codex 019e8964 iter-4
 * AGREE). Re-summarizes the diff against the latest committed source state
 * and upserts the cache row. Runs in {@link Propagation#REQUIRES_NEW} so
 * the listener that calls it owns a fresh transaction independent of the
 * ingest transaction that just committed — a failure here cannot pollute
 * the ingest's rollback-or-commit decision (Codex iter-3 direction:
 * inline catch-log over the ingest tx YASAK; AFTER_COMMIT listener +
 * REQUIRES_NEW writer is the production-quality pattern).
 *
 * <p>Exceptions are NOT swallowed here — Spring rolls back the
 * REQUIRES_NEW transaction and re-throws. The {@link DiffCacheRefreshListener}
 * catches at the listener boundary so cache failures degrade gracefully
 * (the on-demand drawer/summarize() read path still returns correct
 * data; only the cache row may temporarily lag).
 */
@Service
public class DiffCacheRefreshService {

    private final EndpointSoftwareInventoryDiffService softwareDiffService;
    private final EndpointOutdatedSoftwareDiffService outdatedDiffService;
    private final DiffCacheService diffCacheService;

    public DiffCacheRefreshService(
            EndpointSoftwareInventoryDiffService softwareDiffService,
            EndpointOutdatedSoftwareDiffService outdatedDiffService,
            DiffCacheService diffCacheService) {
        this.softwareDiffService = softwareDiffService;
        this.outdatedDiffService = outdatedDiffService;
        this.diffCacheService = diffCacheService;
    }

    /**
     * Refreshes the diff cache row for the given event's
     * {@code (tenantId, deviceId)} against the latest committed source
     * state. {@link Propagation#REQUIRES_NEW} so a failure cannot affect
     * the upstream listener catch / ingest tx.
     *
     * @throws RuntimeException if summarize() or upsert*() fails; the
     *                          listener catches and logs so ingest
     *                          completion is preserved.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(DiffCacheRefreshRequested event) {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case SOFTWARE -> {
                SoftwareDiffSummary summary =
                        softwareDiffService.summarize(event.tenantId(), event.deviceId());
                diffCacheService.upsertSoftwareDiffCache(
                        event.tenantId(), event.deviceId(), summary);
            }
            case OUTDATED -> {
                OutdatedDiffSummary summary =
                        outdatedDiffService.summarize(event.tenantId(), event.deviceId());
                diffCacheService.upsertOutdatedDiffCache(
                        event.tenantId(), event.deviceId(), summary);
            }
        }
    }
}
