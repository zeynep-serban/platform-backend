package com.example.endpointadmin.service;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Shared write-path guard for device-work creation (Codex 019ea789 iter-2).
 *
 * <p>Every admin transaction that creates new agent work for a device —
 * generic command, agent-update, install, local-password-change,
 * maintenance-token, and the uninstall propose/approve dispatch — MUST load the
 * device through this helper so it:
 * <ol>
 *   <li>takes the SAME {@code PESSIMISTIC_WRITE} row lock the
 *       decommission / reactivate lifecycle takes
 *       ({@link EndpointDeviceRepository#findVisibleToOrgAndIdForUpdate}),
 *       serialising the create against an in-flight decommission + its cascade;
 *       and</li>
 *   <li>fail-closes with {@code 409 CONFLICT} on a {@code DECOMMISSIONED}
 *       device.</li>
 * </ol>
 *
 * <p><b>Why a plain read is not enough.</b> The decommission transaction locks
 * the device row and then cascades — cancelling pending commands, revoking
 * tokens, finalising open uninstalls. A concurrent create that read the device
 * with a non-locking query could observe the <em>pre</em>-decommission status,
 * insert new work, and commit <em>after</em> the cascade sweep had already run,
 * leaving stale, un-cancelled intent that a later reactivate would resurrect.
 * Routing every create through the same device row lock makes writer and
 * decommission serialise on that row: whoever locks first wins, and the loser
 * either has its work cascade-cancelled (writer committed first) or observes
 * {@code DECOMMISSIONED} and is rejected (decommission committed first).
 *
 * <p>Acquiring the device lock FIRST in every path also fixes the uninstall
 * {@code approve} lock-order inversion: approve now locks device → request,
 * the same order decommission uses, so the two can never deadlock.
 *
 * <p>MUST be called inside a transaction.
 */
final class EndpointDeviceWriteGuard {

    private EndpointDeviceWriteGuard() {
    }

    /**
     * Load the device under a {@code PESSIMISTIC_WRITE} lock and reject a
     * {@code DECOMMISSIONED} device with {@code 409 CONFLICT}.
     *
     * @param deviceRepository the device repository (caller-owned bean)
     * @param orgId            canonical tenant/org scope
     * @param deviceId         target device id
     * @return the locked, non-decommissioned device
     * @throws ResponseStatusException 404 if not visible in scope, 409 if
     *                                 the device is decommissioned
     */
    static EndpointDevice loadActiveForUpdate(EndpointDeviceRepository deviceRepository,
                                              UUID orgId,
                                              UUID deviceId) {
        EndpointDevice device = deviceRepository.findVisibleToOrgAndIdForUpdate(orgId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is decommissioned; reactivate it before creating new operations.");
        }
        return device;
    }
}
