package dev.repopilot.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional job state transitions, isolated from {@link JobService} and
 * {@link JobDispatcher}.
 *
 * <p>Separate bean on purpose: Spring's transaction advice is proxy-based, so a
 * {@code REQUIRES_NEW} method called from another method of the same class would
 * silently run in the caller's transaction. Keeping transitions here makes each
 * transition a genuine short transaction that commits or rolls back on its own.
 *
 * <p>Every transition is conditional on the expected current status where it matters,
 * which is what makes the claim step safe against two dispatchers racing.
 */
@Service
public class JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private final AnalysisJobRepository jobs;
    private final Clock clock;

    public JobClaimService(AnalysisJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * Atomically moves a job from QUEUED to RUNNING.
     *
     * @return the claimed job, or empty when another worker won the race or the job was
     *         cancelled/deleted in the meantime
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AnalysisJob> claim(UUID jobId) {
        Optional<AnalysisJob> found = jobs.findById(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AnalysisJob job = found.get();
        if (job.getStatus() != JobStatus.QUEUED) {
            // Lost the race, or the job was cancelled between selection and claim.
            return Optional.empty();
        }
        if (job.isCancelRequested()) {
            job.markCancelled(clock.instant());
            return Optional.empty();
        }
        job.markRunning(clock.instant());
        jobs.save(job);
        return Optional.of(job);
    }

    /** Persists throttled progress. Tolerates a job that has since been cancelled. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistProgress(UUID jobId, JobContext.ProgressUpdate update) {
        jobs.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == JobStatus.RUNNING) {
                job.reportProgress(update.percent(), update.stage(), update.detail());
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, JobResult result) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markSucceeded(result.summary(), result.analysisRunId(), clock.instant());
            log.info("job {} ({}) succeeded: {}", jobId, job.getJobType(), result.summary());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(UUID jobId, String reason) {
        jobs.findById(jobId).ifPresent(job -> {
            if (job.getStatus().isTerminal()) {
                return;
            }
            job.markCancelled(clock.instant());
            log.info("job {} ({}) cancelled: {}", jobId, job.getJobType(), reason);
        });
    }

    /**
     * Records a failure, or re-queues when the failure is retryable and attempts remain.
     *
     * @param retryable whether the handler classified this as safe to retry
     * @param backoff   delay before the retry becomes dispatchable
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String errorCode, String message, boolean retryable, Duration backoff) {
        jobs.findById(jobId).ifPresent(job -> {
            if (retryable && job.hasAttemptsRemaining()) {
                Instant notBefore = clock.instant().plus(backoff);
                job.reschedule(notBefore);
                log.warn("job {} ({}) failed with retryable error {}; requeued until {} (attempt {}/{})",
                        jobId, job.getJobType(), errorCode, notBefore, job.getAttempt(), job.getMaxAttempts());
            } else {
                job.markFailed(errorCode, message, clock.instant());
                log.error("job {} ({}) failed [{}]: {}", jobId, job.getJobType(), errorCode, message);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchHeartbeat(UUID jobId) {
        jobs.findById(jobId).ifPresent(job -> job.touchHeartbeat(clock.instant()));
    }

    /**
     * Reconciles a job that was RUNNING when the process stopped.
     *
     * <p>Idempotent job types are re-queued because re-running them converges on the
     * same result. Non-idempotent types are failed, because re-running something with
     * external side effects after an unknown partial execution is not safe to do
     * automatically — a human decides.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverInterrupted(UUID jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            if (job.getJobType().idempotent() && job.hasAttemptsRemaining()) {
                job.reschedule(clock.instant());
                log.warn("requeued job {} ({}) after an unclean shutdown", jobId, job.getJobType());
            } else {
                job.markFailed("RUNNER_INTERRUPTED",
                        "The RepoPilot process stopped while this job was running. "
                                + "Retry it from the Analysis History screen.",
                        clock.instant());
                log.warn("marked interrupted job {} ({}) as failed", jobId, job.getJobType());
            }
        });
    }

    /** Reads the durable cancellation flag so an out-of-process cancel request is honoured. */
    @Transactional(readOnly = true)
    public boolean isCancelRequested(UUID jobId) {
        return jobs.findById(jobId).map(AnalysisJob::isCancelRequested).orElse(false);
    }
}
