package com.serban.notify.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One offending field per {@link ValidationErrorResponse#details()}
 * entry, produced by {@link MethodArgumentNotValidAdvice}.
 *
 * <p>For a class-level / cross-field constraint
 * ({@code @ScriptAssert}-style), {@link #field()} carries the bean's
 * {@code objectName} so the client can distinguish a per-field
 * violation from a cross-field one without reading {@link #message()}.
 *
 * @param field   bean property path (e.g. {@code "intentId"}) for a
 *                field error; bean object name for a global error.
 * @param message developer-authored constraint message declared on
 *                the DTO (e.g. {@code "intent_id required"}). NEVER
 *                the rejected value.
 */
@Schema(description = "Per-field bean-validation violation (#304).")
public record ValidationErrorDetail(
        @Schema(description = "Bean property path; objectName for class-level rules.",
                example = "intentId")
        String field,
        @Schema(description = "Developer-authored constraint message; NEVER carries the rejected value.",
                example = "intent_id required")
        String message) {
}
