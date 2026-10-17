package dev.repopilot.jobs;

/**
 * Thrown by {@link JobContext#checkpoint()} when a job has been cancelled or has
 * exceeded its deadline.
 *
 * <p>Separating cancellation from failure keeps metrics honest: a user cancelling a
 * scan is not an error, and counting it as one makes failure rates meaningless.
 */
public class JobAbortedException extends RuntimeException {

    private final boolean timeout;

    public JobAbortedException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
