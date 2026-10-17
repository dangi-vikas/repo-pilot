package dev.repopilot.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes.
 *
 * <p>The code is part of the API contract; the message is not. Clients switch on
 * {@link #code()}, humans read the message. Internal exception types and stack
 * traces are never serialised.
 */
public enum ErrorCode {

    /** Request payload failed validation. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    /** The referenced entity does not exist or is not visible in this workspace. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    /** The request conflicts with current state (duplicate, illegal transition). */
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with the current state"),
    /** Input was understood but cannot be handled (unsupported format, unsupported language). */
    UNSUPPORTED_INPUT(HttpStatus.UNPROCESSABLE_ENTITY, "Input is not supported"),
    /** A configured hard limit was exceeded (file count, artefact size, finding count). */
    LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "A configured limit was exceeded"),
    /** A path attempt was rejected by the repository sandbox. */
    PATH_REJECTED(HttpStatus.FORBIDDEN, "Path is outside the permitted workspace roots"),
    /** Authentication missing or invalid. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    /** Authenticated but not permitted. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "Operation not permitted"),
    /** Too many expensive requests in flight for this client. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    /** A required external dependency is unavailable (database, model provider, analyzer). */
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required dependency is unavailable"),
    /** The operation took longer than its deadline. */
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Operation timed out"),
    /** The operation was cancelled by the user. */
    CANCELLED(HttpStatus.CONFLICT, "Operation was cancelled"),
    /** Anything unexpected. Details are logged, never returned. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
