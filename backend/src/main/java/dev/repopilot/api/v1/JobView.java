package dev.repopilot.api.v1;

import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.jobs.JobType;

import java.time.Instant;
import java.util.UUID;

/** A background job as returned by the jobs API. */
public record JobView(
        UUID id,
        JobType jobType,
        JobStatus status,
        UUID workspaceId,
        UUID repositoryId,
        int priority,
        int progress,
        String stage,
        String message,
        int attempt,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        String resultSummary,
        UUID analysisRunId,
        boolean cancelRequested,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant heartbeatAt
) {

    public static JobView from(AnalysisJob job) {
        return new JobView(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getWorkspaceId(),
                job.getRepositoryId(),
                job.getPriority(),
                job.getProgress(),
                job.getStage(),
                job.getMessage(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getResultSummary(),
                job.getAnalysisRunId(),
                job.isCancelRequested(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getHeartbeatAt());
    }
}
