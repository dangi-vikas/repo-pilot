package dev.repopilot.jobs;

import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API-facing job operations: submit, inspect, cancel.
 *
 * <p>Submission never executes work. It persists a QUEUED row and returns; the
 * dispatcher picks it up. That keeps HTTP latency independent of analysis cost and
 * means a restart cannot lose accepted work.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /**
     * Job types that are housekeeping rather than work somebody asked for.
     *
     * <p>They are excluded from the per-repository activity guard, because a guard exists to stop two
     * heavy passes fighting over one repository, not to let a scheduled tidy-up block a user.
     */
    private static final List<JobType> MAINTENANCE_TYPES = List.of(JobType.ARTIFACT_RETENTION);

    private final AnalysisJobRepository jobs;
    private final JobRegistry registry;

    public JobService(AnalysisJobRepository jobs, JobRegistry registry) {
        this.jobs = jobs;
        this.registry = registry;
    }

    /**
     * Persists a new job.
     *
     * @param idempotencyKey optional client-supplied de-duplication key. When a job with
     *                       the same key exists it is returned instead of creating a second
     *                       run, so a double-click cannot start two identical scans.
     */
    @Transactional
    public Submission submit(JobType type,
                             UUID workspaceId,
                             UUID repositoryId,
                             String parametersJson,
                             String idempotencyKey) {
        if (!registry.isSupported(type)) {
            throw RepoPilotException.unsupported(
                    "Job type " + type + " is not available in this build. No handler is registered for it.");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<AnalysisJob> existing = jobs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                AnalysisJob job = existing.get();
                log.info("idempotent submit for {} returned existing job {} ({})",
                        type, job.getId(), job.getStatus());
                return new Submission(job, false);
            }
        }

        AnalysisJob job = new AnalysisJob(type, workspaceId, repositoryId);
        job.setParameters(parametersJson);
        job.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        job.setPriority(defaultPriority(type));
        // saveAndFlush, not save: the identifier is generated and @PrePersist runs at
        // flush time, so a plain save would return a job whose id and createdAt are still
        // null. The HTTP layer serializes this instance straight into the 202 response,
        // so the flush must happen before we return. The cost is one insert per submit,
        // which is exactly the work this method exists to do.
        AnalysisJob saved = jobs.saveAndFlush(job);
        log.info("queued job {} ({}) for repository {}", saved.getId(), type, repositoryId);
        return new Submission(saved, true);
    }

    @Transactional(readOnly = true)
    public AnalysisJob require(UUID id) {
        return jobs.findById(id).orElseThrow(() -> RepoPilotException.notFound("Job", id));
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisJob> find(UUID id) {
        return jobs.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<AnalysisJob> list(JobStatus status, Pageable pageable) {
        return status == null
                ? jobs.findAllByOrderByCreatedAtDesc(pageable)
                : jobs.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AnalysisJob> listForRepository(UUID repositoryId, Pageable pageable) {
        return jobs.findByRepositoryIdOrderByCreatedAtDesc(repositoryId, pageable);
    }

    /**
     * Requests cancellation.
     *
     * <p>Sets the durable flag and returns immediately. A RUNNING job observes the flag
     * at its next {@link JobContext#checkpoint()}; a QUEUED job is cancelled outright by
     * the dispatcher before it starts. The caller is told which of these applies so the
     * UI can say "stopping…" rather than claiming the job has stopped.
     */
    @Transactional
    public CancellationOutcome requestCancel(UUID id) {
        AnalysisJob job = require(id);
        if (job.getStatus().isTerminal()) {
            throw RepoPilotException.conflict(
                    "Job is already " + job.getStatus() + " and cannot be cancelled");
        }
        job.requestCancel();
        log.info("cancellation requested for job {} ({}, {})", id, job.getJobType(), job.getStatus());
        return new CancellationOutcome(job.getId(), job.getStatus(), true);
    }

    @Transactional(readOnly = true)
    public JobCounters counters() {
        return new JobCounters(
                jobs.countByStatus(JobStatus.QUEUED),
                jobs.countByStatus(JobStatus.RUNNING),
                jobs.countByStatus(JobStatus.SUCCEEDED),
                jobs.countByStatus(JobStatus.FAILED),
                jobs.countByStatus(JobStatus.CANCELLED));
    }

    @Transactional(readOnly = true)
    public long countActiveForRepository(UUID repositoryId) {
        return jobs.countByRepositoryIdAndStatusIn(repositoryId,
                List.copyOf(EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING)));
    }

    /**
     * The same count, without the jobs that are housekeeping rather than work a user asked for.
     *
     * <p>Retention is submitted by a scheduler, not by anybody watching a spinner, and it is queued at
     * the lowest priority so that it runs when nothing else wants the repository. Counting it as
     * activity would invert that: the maintenance pass would hold the repository and refuse the
     * analysis that was supposed to go first. See {@code ArtifactRetentionJobHandler}.
     */
    @Transactional(readOnly = true)
    public long countActiveAnalysisForRepository(UUID repositoryId) {
        return jobs.countByRepositoryIdAndStatusInAndJobTypeNotIn(repositoryId,
                List.copyOf(EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING)),
                MAINTENANCE_TYPES);
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return jobs.countByStatusIn(List.copyOf(EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING)));
    }

    /**
     * Interactive work (a user is waiting on the result) jumps ahead of background
     * maintenance. Values are small integers because ordering is all that matters.
     */
    private static int defaultPriority(JobType type) {
        return switch (type) {
            case INVESTIGATION, RECOMMENDATION_GENERATE, REPOSITORY_INDEX -> 10;
            case STATIC_ANALYSIS, JFR_ANALYSIS, HEAP_ANALYSIS, THREAD_DUMP_ANALYSIS,
                 BENCHMARK_IMPORT -> 50;
            case RECOMMENDATION_VERIFY -> 30;
            case EMBEDDING_INDEX -> 200;
            case ARTIFACT_RETENTION -> 500;
        };
    }

    /** Result of a submission, distinguishing a fresh job from an idempotent replay. */
    public record Submission(AnalysisJob job, boolean created) {
    }

    /** Result of a cancellation request. */
    public record CancellationOutcome(UUID jobId, JobStatus statusWhenRequested, boolean accepted) {
    }

    /** Aggregate job counts for the overview screen. */
    public record JobCounters(long queued, long running, long succeeded, long failed, long cancelled) {
        public long active() {
            return queued + running;
        }
    }
}
