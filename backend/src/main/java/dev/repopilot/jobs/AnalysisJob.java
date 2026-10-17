package dev.repopilot.jobs;

import dev.repopilot.shared.persistence.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable unit of background work.
 *
 * <p>The database is the queue. There is no in-memory queue of pending work that
 * would be lost on restart, and no unbounded in-memory structure that could grow
 * without limit: the dispatcher only claims work when a worker slot is free, so
 * backpressure is applied by simply not claiming more than can be executed.
 *
 * <p>Concurrency safety: {@code status} transitions are performed with conditional
 * updates ({@code WHERE status = 'QUEUED'}) so two dispatchers — or two application
 * instances sharing one database — cannot both claim the same job. The {@code @Version}
 * column additionally protects every other field from lost updates.
 */
@Entity
@Table(name = "analysis_job")
public class AnalysisJob extends AbstractAuditedEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 48, updatable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private JobStatus status = JobStatus.QUEUED;

    /** Lower runs first. Reserved for future interactive-priority analysis. */
    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "stage", length = 200)
    private String stage;

    @Column(name = "message", length = 2000)
    private String message;

    /** Job-type-specific parameters as JSON. Never contains source or secrets. */
    @Column(name = "parameters", length = 4000)
    private String parameters;

    /**
     * Client-supplied de-duplication key. A unique constraint turns a duplicate
     * submission into a conflict the caller can resolve by reading the existing job,
     * instead of a second expensive run of the same analysis.
     */
    @Column(name = "idempotency_key", length = 200, updatable = false)
    private String idempotencyKey;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 1;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "analysis_run_id")
    private UUID analysisRunId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "result_summary", length = 2000)
    private String resultSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Written periodically while running. A job whose heartbeat is older than the
     * configured staleness threshold after a restart is presumed dead and reconciled.
     */
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    protected AnalysisJob() {
        // Required by JPA.
    }

    public AnalysisJob(JobType jobType, UUID workspaceId, UUID repositoryId) {
        this.jobType = jobType;
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.maxAttempts = jobType.idempotent() ? 2 : 1;
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    public void markRunning(Instant now) {
        this.status = JobStatus.RUNNING;
        this.startedAt = now;
        this.heartbeatAt = now;
        this.attempt++;
        this.stage = "Starting";
        this.progress = 0;
        this.errorCode = null;
        this.errorMessage = null;
        this.cancelRequested = false;
    }

    public void markSucceeded(String summary, UUID runId, Instant now) {
        this.status = JobStatus.SUCCEEDED;
        this.progress = 100;
        this.stage = "Finished";
        this.resultSummary = truncate(summary, 2000);
        this.analysisRunId = runId;
        this.finishedAt = now;
        this.heartbeatAt = now;
    }

    public void markFailed(String code, String message, Instant now) {
        this.status = JobStatus.FAILED;
        this.errorCode = truncate(code, 64);
        this.errorMessage = truncate(message, 2000);
        this.finishedAt = now;
        this.heartbeatAt = now;
    }

    public void markCancelled(Instant now) {
        this.status = JobStatus.CANCELLED;
        this.stage = "Cancelled";
        this.errorCode = "CANCELLED";
        this.errorMessage = "Cancelled by request";
        this.finishedAt = now;
        this.heartbeatAt = now;
    }

    /** Re-queues after a transient failure, applying exponential backoff. */
    public void reschedule(Instant notBefore) {
        this.status = JobStatus.QUEUED;
        this.availableAt = notBefore;
        this.progress = 0;
        this.stage = "Queued for retry";
        this.startedAt = null;
        this.finishedAt = null;
    }

    public boolean hasAttemptsRemaining() {
        return attempt < maxAttempts;
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }

    public void reportProgress(int percent, String stageName, String detail) {
        this.progress = Math.max(0, Math.min(100, percent));
        this.stage = truncate(stageName, 200);
        this.message = truncate(detail, 2000);
        this.heartbeatAt = Instant.now();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public JobType getJobType() {
        return jobType;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getProgress() {
        return progress;
    }

    public String getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = truncate(resultSummary, 2000);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void touchHeartbeat(Instant now) {
        this.heartbeatAt = now;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }
}
