package dev.repopilot.investigation;

/**
 * Lifecycle of one investigation.
 *
 * <p>Deliberately separate from the job status. A job is a unit of scheduling and can be
 * retried; an investigation is a unit of evidence, and its status answers "is there an
 * answer to read", not "is a worker busy". A retried job appends to the same investigation.
 */
public enum InvestigationStatus {

    /** Accepted and waiting for a worker. */
    QUEUED,
    /** Retrieval, prompting or validation is in progress. */
    RUNNING,
    /** An answer exists. Whether it is grounded is a separate question. */
    SUCCEEDED,
    /** No answer was produced. The reason is in {@code errorMessage}. */
    FAILED,
    /** The operator cancelled it. Not a failure, and never counted as one. */
    CANCELLED
}
