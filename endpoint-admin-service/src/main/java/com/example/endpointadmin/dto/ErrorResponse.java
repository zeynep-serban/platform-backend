package com.example.endpointadmin.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ErrorResponse {

    private String error;
    private String message;
    private List<FieldError> fieldErrors;
    private Map<String, Object> meta;

    public ErrorResponse(String error, String message, List<FieldError> fieldErrors, Map<String, Object> meta) {
        this.error = error;
        this.message = message;
        this.fieldErrors = fieldErrors;
        this.meta = meta;
    }

    public static ErrorResponse of(String error, String message, List<FieldError> fieldErrors, String traceId) {
        Map<String, Object> meta = Map.of(
                "traceId", traceId,
                "timestamp", Instant.now().getEpochSecond()
        );
        return new ErrorResponse(error, message, fieldErrors, meta);
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(List<FieldError> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    public static List<FieldError> emptyFieldErrors() {
        return new ArrayList<>();
    }

    public static class FieldError {
        private String field;
        private String message;

        public FieldError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
