package dev.repopilot.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * The only handle a {@link JobHandler} gets on its own execution.
 *
 * <h2>Design intent</h2>
 * Handlers receive progress reporting, cancellation and deadline checks through this
 * type and nothing else. They cannot touch the database, cannot see other jobs, and
 * cannot decide their own lifecycle outcome — the dispatcher owns transitions. That
 * keeps lifecycle invariants in one place and makes handlers straightforward to unit
 * test with a plain stub context.
 *
 * <h2>Cancellation and deadlines are cooperative</h2>
 * {@link #checkpoint()} is the single interruption point. Handlers must call it
 * inside any loop that can run for more than a fraction of a second. The dispatcher
 * additionally arms a watchdog that interrupts the worker thread a grace period
 * after the deadline, as a last resort against a handler that blocks in library code
 * RepoPilot does not control. Cooperative checks are the primary mechanism because
 * interrupting arbitrary JVM library work is not guaranteed to be safe.
 *
 * <h2>Progress writes are throttled</h2>
 * Reporting progress on every file would issue one database write per file. The
 * context coalesces updates so a scan of 20 000 files performs a bounded number of
 * writes (roughly one per percent or per second, whichever is less frequent).
 */
public class JobContext {

    private static final Logger log = LoggerFactory.getLogger(JobContext.class);

    /** Minimum interval between persisted progress updates. */
    private static final Duration PROGRESS_THROTTLE = Duration.ofMillis(750);
    /** Minimum percentage change that forces a persisted update. */
    private static final int PROGRESS_PERCENT_STEP = 1;

    private final UUID jobId;
    private final JobType jobType;
    private final UUID workspaceId;
    private final UUID repositoryId;
    private final String parametersJson;
    private final Instant deadline;
    private final AtomicBoolean cancelFlag;
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private final AtomicLong lastPersistedAt = new AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger lastPersistedPercent =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /** Sink that persists progress. Injected by the dispatcher. */
    private final BiConsumer<ProgressUpdate, Boolean> progressSink;

    public JobContext(UUID jobId, JobType jobType, UUID workspaceId, UUID repositoryId,
                      String parametersJson, Instant deadline, AtomicBoolean cancelFlag,
                      BiConsumer<ProgressUpdate, Boolean> progressSink) {
        this.jobId = jobId;
        this.jobType = jobType;
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.parametersJson = parametersJson;
        this.deadline = deadline;
        this.cancelFlag = cancelFlag;
        this.progressSink = progressSink;
    }

    public UUID jobId() {
        return jobId;
    }

    public JobType jobType() {
        return jobType;
    }

    /** The workspace the job was submitted for, or null for a global job. */
    public UUID workspaceId() {
        return workspaceId;
    }

    /** The repository the job operates on, or null for a job that is not repository-scoped. */
    public UUID repositoryId() {
        return repositoryId;
    }

    /**
     * Whatever the submitter attached to the job, as JSON.
     *
     * <p>Opaque to the job system: it stores the string and hands it back. A handler that
     * needs an input (a runtime artefact to read, a set of files to restrict to) reads it
     * from here, and is responsible for validating it. Jobs submitted without parameters
     * carry null, which is not an error.
     */
    public String parametersJson() {
        return parametersJson;
    }

    public Instant deadline() {
        return deadline;
    }

    public boolean isCancelled() {
        return cancelFlag.get();
    }

    public boolean isTimedOut() {
        return timedOut.get() || Instant.now().isAfter(deadline);
    }

    /**
     * The single cooperative interruption point.
     *
     * @throws JobAbortedException when cancellation was requested or the deadline passed
     */
    public void checkpoint() {
        if (cancelFlag.get()) {
            throw new JobAbortedException("Job cancellation was requested", false);
        }
        if (Instant.now().isAfter(deadline)) {
            timedOut.set(true);
            throw new JobAbortedException("Job exceeded its deadline of "
                    + Duration.between(Instant.now(), deadline).negated().toSeconds()
                    + " seconds", true);
        }
    }

    /**
     * Reports progress. Persistence is throttled; the in-memory latest value is always
     * available so an SSE subscriber polling the row sees coarse but timely movement.
     *
     * @param percent 0..100
     * @param stage   short stage label, shown in the UI
     * @param detail  optional human-readable detail; must not contain source content
     */
    public void progress(int percent, String stage, String detail) {
        report(percent, stage, detail, false);
    }

    /** Reports progress and forces an immediate persisted write (used for stage changes). */
    public void progressNow(int percent, String stage, String detail) {
        report(percent, stage, detail, true);
    }

    private void report(int percent, String stage, String detail, boolean force) {
        int clamped = Math.max(0, Math.min(100, percent));
        long now = System.currentTimeMillis();

        boolean percentMoved = Math.abs(clamped - lastPersistedPercent.get()) >= PROGRESS_PERCENT_STEP;
        boolean intervalElapsed = now - lastPersistedAt.get() >= PROGRESS_THROTTLE.toMillis();
        boolean terminal = clamped >= 100;

        if (!force && !terminal && !(percentMoved && intervalElapsed)) {
            return;
        }
        lastPersistedAt.set(now);
        lastPersistedPercent.set(clamped);

        try {
            progressSink.accept(new ProgressUpdate(clamped, stage, detail), force);
        } catch (RuntimeException e) {
            // A progress write failure must never fail the job itself: progress is
            // observability, not the deliverable. Logged and swallowed deliberately.
            log.debug("progress update for job {} could not be persisted: {}", jobId, e.getMessage());
        }
    }

    /** Persisted progress snapshot. */
    public record ProgressUpdate(int percent, String stage, String detail) {
    }
}
