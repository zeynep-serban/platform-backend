package com.example.endpointadmin.service.diff;

/**
 * Diff cache scope discriminator used by {@link DiffCacheRefreshRequested}
 * to tell the {@link DiffCacheRefreshListener} whether the ingest tx that
 * just committed was a software-inventory ingest (BE-024) or an outdated-
 * software ingest (BE-024b). The listener uses this to pick the right
 * summarize() + upsert() pair on the {@link DiffCacheService}.
 *
 * <p>Faz 22.5 P2-A v2-c-pre-2-B (Codex 019e8964 iter-4 AGREE).
 */
public enum DiffType {
    SOFTWARE,
    OUTDATED
}
