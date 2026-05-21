package com.example.endpointadmin.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class EnrollmentExpiredException extends ResponseStatusException {

    public EnrollmentExpiredException() {
        super(HttpStatus.GONE, "Enrollment token has expired.");
    }
}
