package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHardwareInventoryNetworkInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * BE-022Q — network interface facet of a hardware inventory snapshot
 * response (Faz 22.5.2 query API).
 *
 * <p>Whitelist projection of the entity:
 * <ul>
 *   <li>{@code macAddress} is already lowercase canonical
 *       ({@code aa:bb:cc:dd:ee:ff}) thanks to the agent emit
 *       (AG-035 wire) plus the backend ingest normaliser.</li>
 *   <li>{@code interfaceType} and {@code linkState} are projected as
 *       canonical enum names
 *       (ETHERNET/WIFI/LOOPBACK/VIRTUAL/UNKNOWN and UP/DOWN/UNKNOWN).</li>
 *   <li>{@code ipAddresses} is a defensive copy of the persisted
 *       list (jsonb) so a downstream mutation cannot affect the
 *       managed entity collection.</li>
 * </ul>
 */
public record AdminHardwareInventoryNetworkInterfaceResponse(
        String name,
        String macAddress,
        String interfaceType,
        String linkState,
        List<String> ipAddresses) {

    public static AdminHardwareInventoryNetworkInterfaceResponse from(
            EndpointHardwareInventoryNetworkInterface intf) {
        List<String> ips = intf.getIpAddresses();
        return new AdminHardwareInventoryNetworkInterfaceResponse(
                intf.getName(),
                intf.getMacAddress(),
                intf.getInterfaceType() != null ? intf.getInterfaceType().name() : null,
                intf.getLinkState() != null ? intf.getLinkState().name() : null,
                ips != null ? new ArrayList<>(ips) : List.of());
    }
}
