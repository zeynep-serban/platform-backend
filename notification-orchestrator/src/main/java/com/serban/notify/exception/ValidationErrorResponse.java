package com.serban.notify.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Structured 400 response body produced by
 * {@link MethodArgumentNotValidAdvice} when a {@code @Valid @RequestBody}
 * DTO violates a bean-validation constraint.
 *
 * <p>Declared as a record (not {@code Map}) so springdoc-openapi can
 * generate a stable schema for it — every controller {@code @ApiResponse}
 * that documents a 400 validation case references this type via
 * {@code @Schema(implementation = ValidationErrorResponse.class)},
 * eliminating the pre-#304 "400 exists but body is opaque" contract
 * drift surfaced by Codex thread {@code 019e806a}.
 *
 * @param error   constant {@code "validation"} — discriminator for
 *                clients that key on top-level error kind.
 * @param message human-readable summary; not key/value parseable, do
 *                not pattern-match against it.
 * @param details one entry per offending field; never {@code null},
 *                always non-empty when present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Structured 400 response for @Valid @RequestBody bean-validation failures (#304).")
public record ValidationErrorResponse(
        @Schema(description = "Discriminator constant.", example = "validation")
        String error,
        @Schema(description = "Human-readable summary; not for pattern-matching.",
                example = "request body failed validation; see details")
        String message,
        @Schema(description = "One entry per offending field, including global / cross-field rules.")
        List<ValidationErrorDetail> details) {
}
