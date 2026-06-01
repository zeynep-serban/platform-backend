package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointServicesEntry;

/**
 * BE — per-service-entry response DTO for AG-039 services snapshot.
 * Wire shape: {@code {name, present, state, startupMode}}.
 */
public record AdminServiceEntryResponse(
        Integer rowOrdinal,
        String name,
        Boolean present,
        String state,
        String startupMode) {

    public static AdminServiceEntryResponse from(EndpointServicesEntry e) {
        return new AdminServiceEntryResponse(
                e.getRowOrdinal(),
                e.getName(),
                e.getPresent(),
                e.getState(),
                e.getStartupMode());
    }
}
