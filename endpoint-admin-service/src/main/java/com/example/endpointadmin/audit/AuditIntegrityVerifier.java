package com.example.endpointadmin.audit;

import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * BE-016 — Verifies the integrity of a tenant's audit hash-chain
 * (Codex 019e4f8e plan-time AGREE).
 *
 * <p>Internal service / test helper only — there is intentionally NO admin
 * HTTP endpoint in BE-016 (Codex scope-lock: an endpoint would pull in authz,
 * pagination, timeout and abuse-surface concerns out of scope for the audit
 * integrity core). A later CLI / scheduled job / admin endpoint can call this.
 *
 * <p>Coverage boundary: only rows that already carry an {@code event_hash} are
 * verified. Legacy pre-BE-016 rows (null hash) are skipped — BE-016 makes no
 * retroactive integrity claim over them. The first hashed row for a tenant is
 * its GENESIS ({@code prev_event_hash == null}).
 */
@Service
public class AuditIntegrityVerifier {

    private final EndpointAuditEventRepository repository;

    public AuditIntegrityVerifier(EndpointAuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Verify the full hashed chain for one tenant.
     *
     * @param tenantId tenant to verify
     * @return a {@link Result} — {@code valid=true} when every hashed row links
     *         correctly and re-hashes to its stored value
     */
    @Transactional(readOnly = true)
    public Result verifyTenant(UUID tenantId) {
        List<EndpointAuditEvent> chain =
                repository.findByTenantIdAndEventHashIsNotNullOrderByOccurredAtAscIdAsc(tenantId);
        if (chain.isEmpty()) {
            return new Result(tenantId, true, 0, null, "No hashed audit rows for tenant (empty chain).");
        }

        String expectedPrev = null; // GENESIS row must have prev_event_hash == null
        int checked = 0;
        for (EndpointAuditEvent event : chain) {
            // 1. Linkage: stored prev_event_hash must match the running chain.
            if (!equalsNullable(expectedPrev, event.getPrevEventHash())) {
                return new Result(tenantId, false, checked, event.getId(),
                        "Chain linkage broken at event " + event.getId()
                                + ": expected prev_event_hash=" + describe(expectedPrev)
                                + " but stored=" + describe(event.getPrevEventHash()));
            }
            // 2. Recompute: stored event_hash must match a fresh hash of the row.
            String recomputed = AuditChainSupport.computeEventHash(event.getPrevEventHash(), event);
            if (!recomputed.equals(event.getEventHash())) {
                return new Result(tenantId, false, checked, event.getId(),
                        "Tamper detected at event " + event.getId()
                                + ": stored event_hash=" + describe(event.getEventHash())
                                + " but recomputed=" + describe(recomputed));
            }
            expectedPrev = event.getEventHash();
            checked++;
        }
        return new Result(tenantId, true, checked, null,
                "Chain verified: " + checked + " hashed audit row(s) intact.");
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String describe(String hash) {
        return hash == null ? "<GENESIS/null>" : hash;
    }

    /**
     * Verification outcome. {@code firstFailureEventId} + {@code message} are
     * populated only when {@code valid == false}.
     */
    public record Result(
            UUID tenantId,
            boolean valid,
            int checkedCount,
            UUID firstFailureEventId,
            String message
    ) {
    }
}
