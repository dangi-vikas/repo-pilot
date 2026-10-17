package dev.repopilot.jobs;

/**
 * Kinds of background work RepoPilot performs.
 *
 * <p>Each value maps to exactly one {@link JobHandler} implementation. Adding a job
 * type without a handler is a startup failure rather than a job that silently sits
 * in {@code QUEUED} forever.
 *
 * <p>{@link #idempotent} states whether a retry after an infrastructure failure is
 * safe. Deterministic failures (a malformed heap dump, an unsupported file) are never
 * retried regardless of this flag; it only governs transient failures such as a
 * database blip or a killed process.
 */
public enum JobType {

    /** Enumerate, parse and index a repository revision. */
    REPOSITORY_INDEX(true, "Repositories"),
    /** Run source analyzers (custom checks, PMD, optional SpotBugs) over an indexed revision. */
    STATIC_ANALYSIS(true, "Analysis"),
    /** Parse a JFR recording and derive profiling observations. */
    JFR_ANALYSIS(true, "Analysis"),
    /** Ingest a heap dump and, when a supported analyzer is available, derive retention evidence. */
    HEAP_ANALYSIS(true, "Analysis"),
    /** Parse a thread dump and derive thread state observations. */
    THREAD_DUMP_ANALYSIS(true, "Analysis"),
    /** Import a benchmark result file produced by an external harness. */
    BENCHMARK_IMPORT(true, "Analysis"),
    /** Run a retrieval-grounded AI investigation. */
    INVESTIGATION(true, "Investigations"),
    /** Ask the model to propose a patch for a finding. Never applies it. */
    RECOMMENDATION_GENERATE(true, "Investigations"),
    /** Execute an explicitly approved test or benchmark command in the verification boundary. */
    RECOMMENDATION_VERIFY(false, "Investigations"),
    /** Compute optional embeddings for semantic retrieval. */
    EMBEDDING_INDEX(true, "Analysis"),
    /** Prune artefacts and runs past the retention window. */
    ARTIFACT_RETENTION(true, "Maintenance");

    private final boolean idempotent;
    private final String group;

    JobType(boolean idempotent, String group) {
        this.idempotent = idempotent;
        this.group = group;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public String group() {
        return group;
    }
}
