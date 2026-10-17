package dev.repopilot.jobs;

/**
 * Durable job lifecycle.
 *
 * <p>Transitions:
 * <pre>
 *   QUEUED ──claim──▶ RUNNING ──success──▶ SUCCEEDED
 *      ▲                │  │
 *      │                │  ├──failure (terminal)─────────▶ FAILED
 *      │                │  ├──failure (retryable, attempts left)──▶ QUEUED
 *      │                │  └──cancel requested─────────▶ CANCELLED
 *      └────────────────┘
 *                 (retry re-queue)
 * </pre>
 *
 * <p>{@link #CANCELLED} is a distinct terminal state from {@link #FAILED}: a user
 * cancellation is not a defect and must not inflate failure rates in metrics or
 * trigger alerts.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
