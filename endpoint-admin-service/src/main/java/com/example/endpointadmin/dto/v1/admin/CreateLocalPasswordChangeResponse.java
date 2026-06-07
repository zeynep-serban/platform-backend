package com.example.endpointadmin.dto.v1.admin;

public record CreateLocalPasswordChangeResponse(
        EndpointCommandDto command,
        String oneTimePassword) {
}
