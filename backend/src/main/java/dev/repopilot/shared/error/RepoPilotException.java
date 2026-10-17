package dev.repopilot.shared.error;

/**
 * Base type for expected, client-actionable failures.
 *
 * <p>The distinction that matters in RepoPilot: {@code RepoPilotException} means
 * "the caller can do something about this" and its message is safe to return.
 * Every other exception is an implementation defect or an infrastructure
 * failure, is logged with full context, and is reported to clients as a generic
 * {@link ErrorCode#INTERNAL_ERROR}.
 */
public class RepoPilotException extends RuntimeException {

    private final ErrorCode errorCode;

    public RepoPilotException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RepoPilotException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    // ------------------------------------------------------------------
    // Factory helpers keep call sites short and consistent.
    // ------------------------------------------------------------------

    public static RepoPilotException notFound(String what, Object id) {
        return new RepoPilotException(ErrorCode.NOT_FOUND, what + " '" + id + "' was not found");
    }

    public static RepoPilotException validation(String message) {
        return new RepoPilotException(ErrorCode.VALIDATION_FAILED, message);
    }

    public static RepoPilotException conflict(String message) {
        return new RepoPilotException(ErrorCode.CONFLICT, message);
    }

    public static RepoPilotException unsupported(String message) {
        return new RepoPilotException(ErrorCode.UNSUPPORTED_INPUT, message);
    }

    public static RepoPilotException limitExceeded(String message) {
        return new RepoPilotException(ErrorCode.LIMIT_EXCEEDED, message);
    }

    public static RepoPilotException pathRejected(String message) {
        return new RepoPilotException(ErrorCode.PATH_REJECTED, message);
    }

    public static RepoPilotException dependencyUnavailable(String message) {
        return new RepoPilotException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }

    public static RepoPilotException timeout(String message) {
        return new RepoPilotException(ErrorCode.TIMEOUT, message);
    }

    public static RepoPilotException cancelled(String message) {
        return new RepoPilotException(ErrorCode.CANCELLED, message);
    }
}
