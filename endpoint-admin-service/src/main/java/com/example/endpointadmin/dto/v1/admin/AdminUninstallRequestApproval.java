package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 1b — request body for
 * {@code POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{requestId}/approve}
 * (Faz 22.5.6).
 *
 * <p>The approver may attach an optional justification reason that is
 * appended to the maker-checker audit row. Maker-checker invariant
 * (approver != proposer) is enforced server-side from the request
 * tenant context; not carried in the body to prevent client-side
 * spoofing.
 */
public record AdminUninstallRequestApproval(
        @Size(max = 512)
        String reason) {
}
