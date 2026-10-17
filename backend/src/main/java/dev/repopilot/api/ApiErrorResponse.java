package dev.repopilot.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error body shape returned by every RepoPilot endpoint.
 *
 * <p>Deliberately excludes exception class names, stack traces and SQL. Those are
 * logged server-side against {@link #correlationId()} so an operator can join the
 * client-visible failure to the server-side detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String errorCode,
        String message,
        int status,
        Instant timestamp,
        String correlationId,
        String path,
        List<FieldViolation> violations,
        Map<String, Object> context
) {

    /** A single field-level validation failure. */
    public record FieldViolation(String field, String message, Object rejectedValue) {
    }

    public static ApiErrorResponse of(String errorCode, String message, int status,
                                      String correlationId, String path) {
        return new ApiErrorResponse(errorCode, message, status, Instant.now(),
                correlationId, path, null, null);
    }

    public static ApiErrorResponse of(String errorCode, String message, int status,
                                      String correlationId, String path,
                                      List<FieldViolation> violations) {
        return new ApiErrorResponse(errorCode, message, status, Instant.now(),
                correlationId, path, violations, null);
    }
}
