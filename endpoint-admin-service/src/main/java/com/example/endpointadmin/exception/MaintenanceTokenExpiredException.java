package com.example.endpointadmin.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class MaintenanceTokenExpiredException extends ResponseStatusException {

    public MaintenanceTokenExpiredException() {
        super(HttpStatus.GONE, "Maintenance token has expired.");
    }
}
