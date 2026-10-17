package dev.repopilot.analysis;

/**
 * Lifecycle of one analysis run.
 *
 * <p>Deliberately not the same enum as the job's status. A job can succeed while an
 * analyzer inside it fails, and an analysis run that finishes having analysed 12 of 300
 * files must be able to say so rather than borrowing the job's verdict. {@link #PARTIAL}
 * exists for exactly that case: findings were produced, and the run does not claim to
 * have covered the revision.
 */
public enum AnalysisRunStatus {

    RUNNING,

    /** Every applicable file was analysed. */
    SUCCEEDED,

    /**
     * The run completed but did not cover everything it should have: a file an analyzer
     * could read was not read, or an analyzer failed on at least one file.
     *
     * <p>Files in languages no analyzer supports do <em>not</em> make a run partial. The
     * analyzer set defines what the run covers, so counting its own blind spots as
     * incompleteness would make {@link #SUCCEEDED} unreachable for any repository that
     * contains a Kotlin file. Those files are reported in the run summary instead, where
     * "N files skipped (no analyzer for the language)" is a fact about coverage rather than
     * a verdict on the run.
     */
    PARTIAL,

    FAILED,

    CANCELLED
}
