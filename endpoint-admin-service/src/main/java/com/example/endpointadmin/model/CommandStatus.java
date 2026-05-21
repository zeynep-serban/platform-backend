package com.example.endpointadmin.model;

public enum CommandStatus {
    QUEUED,
    DELIVERED,
    ACKED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED
}
