package dev.repopilot.jobs;

/**
 * Signals that a job failed for a reason that may not recur, and that retrying is
 * safe for this job type.
 *
 * <p>Anything else — including {@link RuntimeException} and {@link Error} — is
 * treated as terminal. That default is deliberate: retrying a deterministic failure
 * such as "this file is not a valid JFR recording" wastes resources and hides the
 * real problem behind a delay.
 */
public class RetryableJobException extends RuntimeException {

    private final String errorCode;
    private final java.time.Duration suggestedBackoff;

    public RetryableJobException(String errorCode, String message, Throwable cause,
                                 java.time.Duration suggestedBackoff) {
        super(message, cause);
        this.errorCode = errorCode;
        this.suggestedBackoff = suggestedBackoff;
    }

    public RetryableJobException(String errorCode, String message) {
        this(errorCode, message, null, java.time.Duration.ofSeconds(30));
    }

    public String errorCode() {
        return errorCode;
    }

    public java.time.Duration suggestedBackoff() {
        return suggestedBackoff;
    }
}
