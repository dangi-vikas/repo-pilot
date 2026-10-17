package dev.repopilot.jobs;

import java.util.UUID;

/**
 * What a completed job achieved.
 *
 * @param summary       one-line outcome for the job list and the UI toast
 * @param analysisRunId the {@code analysis_run} produced, when the job produced one
 */
public record JobResult(String summary, UUID analysisRunId) {

    public static JobResult of(String summary) {
        return new JobResult(summary, null);
    }

    public static JobResult of(String summary, UUID analysisRunId) {
        return new JobResult(summary, analysisRunId);
    }

    public static JobResult empty() {
        return new JobResult(null, null);
    }
}
