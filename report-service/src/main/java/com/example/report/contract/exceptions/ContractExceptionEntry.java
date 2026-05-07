package com.example.report.contract.exceptions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Phase 2 Program 1b — Exception entry record matching exceptions.json shape.
 *
 * <p>Spec §2.5: contract exception entry. expiresAt zorunlu (Codex iter-1 §3 absorb)
 * — sürekli bypass YASAK; 90-gün max horizon (Codex iter-3 absorb).
 *
 * @param id          Unique entry id (e.g. "EXCEPTION-001")
 * @param ruleIds     RC rule IDs to suppress (e.g. ["RC-003", "RC-007"])
 * @param reportKey   Target report registry key
 * @param reason      Human-readable rationale (audit trail)
 * @param owner       Responsible owner email/handle
 * @param expiresAt   ISO-8601 instant; auto-expire after this point
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractExceptionEntry(
        @JsonProperty("id") String id,
        @JsonProperty("ruleIds") List<String> ruleIds,
        @JsonProperty("reportKey") String reportKey,
        @JsonProperty("reason") String reason,
        @JsonProperty("owner") String owner,
        @JsonProperty("expiresAt") Instant expiresAt
) {

    @JsonCreator
    public ContractExceptionEntry {
        if (ruleIds == null) {
            ruleIds = List.of();
        }
    }

    public boolean covers(String reportKey, String ruleId) {
        return reportKey != null && reportKey.equals(this.reportKey)
                && ruleIds.contains(ruleId);
    }
}
