package dev.repopilot.api;

import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Turns every failure into the single documented {@link ApiErrorResponse} shape.
 *
 * <p>Policy:
 * <ul>
 *   <li>Expected, actionable failures carry their own code and safe message.</li>
 *   <li>Unexpected failures are logged with the correlation id and returned as a
 *       generic message, so internal class names, SQL and paths never leak.</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RepoPilotException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(RepoPilotException ex, ServerWebExchange exchange) {
        ErrorCode code = ex.errorCode();
        // Client-visible by design: an expected failure is an operational fact, not a defect.
        log.warn("domain error [{}] {}: {}", code.name(), path(exchange), ex.getMessage());
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code.name(), ex.getMessage(), code.status().value(),
                        Correlation.current(exchange), path(exchange)));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiErrorResponse> handleBinding(WebExchangeBindException ex,
                                                          ServerWebExchange exchange) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldViolation(
                        error.getField(), error.getDefaultMessage(), null))
                .toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED.name(),
                "One or more fields are invalid",
                HttpStatus.BAD_REQUEST.value(),
                Correlation.current(exchange),
                path(exchange),
                violations));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraint(ConstraintViolationException ex,
                                                             ServerWebExchange exchange) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(this::toViolation)
                .toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED.name(),
                "One or more parameters are invalid",
                HttpStatus.BAD_REQUEST.value(),
                Correlation.current(exchange),
                path(exchange),
                violations));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorResponse> handleInput(ServerWebInputException ex,
                                                        ServerWebExchange exchange) {
        // Malformed JSON, wrong content type, unreadable body. The framework message
        // can embed parser details, so it is replaced with a stable description.
        log.warn("malformed request [{}]: {}", path(exchange), ex.getReason());
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED.name(),
                "The request body could not be read",
                HttpStatus.BAD_REQUEST.value(),
                Correlation.current(exchange),
                path(exchange)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                            ServerWebExchange exchange) {
        log.warn("data integrity violation [{}]: {}", path(exchange), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                ErrorCode.CONFLICT.name(),
                "The request conflicts with an existing record",
                HttpStatus.CONFLICT.value(),
                Correlation.current(exchange),
                path(exchange)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                 ServerWebExchange exchange) {
        // Two workers or a worker and a user touched the same job/entity. Retrying is safe.
        log.warn("optimistic lock conflict [{}]: {}", path(exchange), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                ErrorCode.CONFLICT.name(),
                "The resource was modified concurrently. Reload it and retry.",
                HttpStatus.CONFLICT.value(),
                Correlation.current(exchange),
                path(exchange)));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeout(TimeoutException ex, ServerWebExchange exchange) {
        log.warn("operation timed out [{}]: {}", path(exchange), ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(ApiErrorResponse.of(
                ErrorCode.TIMEOUT.name(),
                "The operation exceeded its deadline",
                HttpStatus.GATEWAY_TIMEOUT.value(),
                Correlation.current(exchange),
                path(exchange)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException ex,
                                                         ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        ErrorCode code = switch (resolved) {
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case UNAUTHORIZED -> ErrorCode.UNAUTHENTICATED;
            case UNPROCESSABLE_ENTITY -> ErrorCode.UNSUPPORTED_INPUT;
            case PAYLOAD_TOO_LARGE -> ErrorCode.LIMIT_EXCEEDED;
            default -> resolved.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
        String message = ex.getReason() == null ? code.defaultMessage() : ex.getReason();
        return ResponseEntity.status(resolved).body(ApiErrorResponse.of(
                code.name(), message, resolved.value(), Correlation.current(exchange), path(exchange)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                  ServerWebExchange exchange) {
        log.warn("invalid argument [{}]: {}", path(exchange), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                ErrorCode.VALIDATION_FAILED.name(),
                ex.getMessage() == null ? "Invalid argument" : ex.getMessage(),
                HttpStatus.BAD_REQUEST.value(),
                Correlation.current(exchange),
                path(exchange)));
    }

    /**
     * Last resort. The client receives a correlation id and nothing else; the full
     * stack trace goes to the server log only.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Throwable ex, ServerWebExchange exchange) {
        log.error("unhandled failure [{}] correlation={}", path(exchange), Correlation.current(exchange), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(),
                        "An internal error occurred. Reference correlation id "
                                + Correlation.current(exchange) + " in a bug report.",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        Correlation.current(exchange),
                        path(exchange)));
    }

    private ApiErrorResponse.FieldViolation toViolation(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        return new ApiErrorResponse.FieldViolation(propertyPath, violation.getMessage(), null);
    }

    private String path(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        return request.getPath().value();
    }

    /** Exposed for the health endpoint's self-description of the error contract. */
    public static Map<String, String> errorContract() {
        Map<String, String> contract = new java.util.LinkedHashMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            contract.put(code.name(), code.status().value() + " " + code.defaultMessage());
        }
        return contract;
    }
}
