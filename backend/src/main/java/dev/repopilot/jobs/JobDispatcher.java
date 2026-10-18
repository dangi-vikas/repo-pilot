package dev.repopilot.jobs;

import dev.repopilot.config.RepoPilotProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pulls queued jobs from the database and runs them on a bounded worker pool.
 *
 * <h2>Concurrency model (the important part)</h2>
 * The database is the queue. The dispatcher never accepts unbounded work into
 * memory: it computes the number of free worker slots and claims <em>at most</em> that
 * many candidates per pass. There is no {@code ThreadPoolExecutor} queue of pending
 * jobs to grow without limit, because work is only submitted when a slot exists. If
 * the database holds 10 000 queued jobs, the memory footprint is still one bounded
 * batch of ids per pass.
 *
 * <h2>Durability</h2>
 * A restart mid-job is handled by {@link JobRecovery}: the row survives, and the job
 * is either re-queued (idempotent type) or failed with an explicit reason. Nothing
 * about job state lives only in memory.
 *
 * <h2>Deadline enforcement</h2>
 * Cooperative first: handlers call {@link JobContext#checkpoint()}. As a last resort
 * a watchdog interrupts the worker thread a grace period past the deadline, so a
 * handler blocked in library code cannot hold a worker slot indefinitely. Interruption
 * is used only for this purpose and the grace period prevents it from firing on a job
 * that is merely slow to observe its own deadline.
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    /** Extra time past the deadline before the watchdog interrupts a worker thread. */
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(30);

    /** Heartbeat cadence for running jobs. Also the resolution of staleness detection. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** How many dispatch passes between cheap reconciliation sweeps. */
    private static final int RECONCILE_EVERY_N_PASSES = 20;

    private final AnalysisJobRepository jobs;
    private final JobClaimService claimService;
    private final JobRegistry registry;
    private final RepoPilotProperties properties;
    private final Clock clock;

    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor watchdog;
    private final ScheduledThreadPoolExecutor heartbeats;

    /** Fast in-process cancellation flags, mirrored from the durable flag. */
    private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    /** Worker threads currently executing a job, for the deadline watchdog. */
    private final Map<UUID, Thread> workerThreads = new ConcurrentHashMap<>();
    /** Scheduled watchdog tasks, cancelled when a job finishes normally. */
    private final Map<UUID, java.util.concurrent.ScheduledFuture<?>> watchdogs = new ConcurrentHashMap<>();

    private final AtomicInteger passes = new AtomicInteger();
    private volatile boolean shuttingDown;

    private final Counter dispatched;
    private final Counter completed;
    private final Counter failed;
    private final Counter cancelled;
    private final Timer executionTime;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger inFlight = new AtomicInteger();

    public JobDispatcher(AnalysisJobRepository jobs,
                         JobClaimService claimService,
                         JobRegistry registry,
                         RepoPilotProperties properties,
                         Clock clock,
                         MeterRegistry meterRegistry) {
        this.jobs = jobs;
        this.claimService = claimService;
        this.registry = registry;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;

        int workerCount = Math.max(1, properties.jobs().workerThreads());
        int queueCapacity = Math.max(1, properties.jobs().queueCapacity());

        // A bounded queue with a caller-runs refusal: if the queue were ever full we
        // would rather reject (and re-queue the job) than block the dispatcher thread
        // or grow memory. In practice the queue stays empty because dispatch is
        // gated on free worker slots.
        this.workers = new ThreadPoolExecutor(
                workerCount, workerCount,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory("repopilot-job"),
                new ThreadPoolExecutor.AbortPolicy());

        this.watchdog = new ScheduledThreadPoolExecutor(1, namedThreadFactory("repopilot-watchdog"), (task, executor) -> {
            log.warn("watchdog task rejected; running inline");
            task.run();
        });
        this.watchdog.setRemoveOnCancelPolicy(true);

        this.heartbeats = new ScheduledThreadPoolExecutor(1, namedThreadFactory("repopilot-heartbeat"));

        this.dispatched = Counter.builder("repopilot.jobs.dispatched").register(meterRegistry);
        this.completed = Counter.builder("repopilot.jobs.completed").register(meterRegistry);
        this.failed = Counter.builder("repopilot.jobs.failed").register(meterRegistry);
        this.cancelled = Counter.builder("repopilot.jobs.cancelled").register(meterRegistry);
        this.executionTime = Timer.builder("repopilot.jobs.execution").register(meterRegistry);

        log.info("job dispatcher configured with {} worker thread(s) and a bounded queue of {}",
                workerCount, queueCapacity);

        this.heartbeats.scheduleWithFixedDelay(this::beat, HEARTBEAT_INTERVAL.toSeconds(),
                HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Number of workers currently executing a job. Exposed for readiness reporting. */
    public int inFlight() {
        return inFlight.get();
    }

    /** Number of free worker slots. */
    public int freeSlots() {
        return Math.max(0, workers.getMaximumPoolSize() - inFlight.get());
    }

    public int workerThreads() {
        return workers.getMaximumPoolSize();
    }

    /**
     * One dispatch pass.
     *
     * <p>Runs on the application scheduler, which is single-threaded and uses a fixed
     * delay, so passes never overlap with themselves.
     */
    @Scheduled(fixedDelayString = "${repopilot.jobs.dispatch-interval-millis:1500}",
            initialDelayString = "${repopilot.jobs.dispatch-initial-delay-millis:2000}")
    public void dispatchPass() {
        if (shuttingDown) {
            return;
        }
        long pass = passes.incrementAndGet();

        try {
            mirrorCancellationRequests();
            if (pass % RECONCILE_EVERY_N_PASSES == 1) {
                reconcileStale();
            }
            dispatchAvailable();
        } catch (RuntimeException e) {
            // The scheduler suppresses a thrown exception by cancelling future runs, which
            // would silently stop all job processing. Catching here keeps the dispatcher alive.
            log.error("job dispatch pass failed; processing will continue on the next pass", e);
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private void dispatchAvailable() {
        int free = freeSlots();
        if (free == 0) {
            return;
        }

        List<UUID> candidates = jobs.findDispatchableIds(clock.instant(), PageRequest.of(0, free));
        for (UUID candidate : candidates) {
            if (freeSlots() == 0) {
                break;
            }
            claimAndSubmit(candidate);
        }
    }

    private void claimAndSubmit(UUID jobId) {
        var claimed = claimService.claim(jobId);
        if (claimed.isEmpty()) {
            // Another dispatcher claimed it, or it was cancelled between selection and claim.
            return;
        }
        AnalysisJob job = claimed.get();

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(jobId, cancelFlag);
        inFlight.incrementAndGet();
        dispatched.increment();

        try {
            workers.execute(() -> runJob(job, cancelFlag));
        } catch (RejectedExecutionException e) {
            // Bounded-queue refusal. Re-queue rather than lose the work.
            inFlight.decrementAndGet();
            cancelFlags.remove(jobId);
            log.warn("worker pool refused job {}; requeueing", jobId);
            claimService.fail(jobId, "WORKER_SATURATED",
                    "No worker slot was available to start this job.", true, Duration.ofSeconds(5));
        }
    }

    private void runJob(AnalysisJob job, AtomicBoolean cancelFlag) {
        UUID jobId = job.getId();
        JobType type = job.getJobType();
        Instant startedAt = clock.instant();
        Duration timeout = Duration.ofSeconds(Math.max(30, properties.jobs().defaultTimeoutSeconds()));
        Instant deadline = startedAt.plus(timeout);

        workerThreads.put(jobId, Thread.currentThread());

        // Last-resort deadline enforcement. Cancelled when the job finishes normally.
        var watchdogTask = watchdog.schedule(() -> {
            Thread worker = workerThreads.get(jobId);
            if (worker != null) {
                log.error("interrupting job {} ({}) after exceeding its deadline of {}s plus a {}s grace period",
                        jobId, type, timeout.toSeconds(), INTERRUPT_GRACE.toSeconds());
                worker.interrupt();
            }
        }, timeout.plus(INTERRUPT_GRACE).toMillis(), TimeUnit.MILLISECONDS);
        watchdogs.put(jobId, watchdogTask);

        try {
            JobHandler handler = registry.require(type);
            JobContext context = new JobContext(jobId, type, job.getWorkspaceId(), job.getRepositoryId(),
                    job.getParameters(), deadline, cancelFlag,
                    (update, force) -> claimService.persistProgress(jobId, update));

            context.progressNow(1, "Starting", "Preparing " + type.name().toLowerCase().replace('_', ' '));
            JobResult result = handler.execute(context);
            claimService.complete(jobId, result == null ? JobResult.empty() : result);
            completed.increment();
        } catch (JobAbortedException e) {
            if (e.isTimeout()) {
                claimService.fail(jobId, "JOB_TIMEOUT",
                        "The job exceeded its deadline of " + timeout.toSeconds()
                                + " seconds. Raise repopilot.jobs.default-timeout-seconds or reduce scope.",
                        false, Duration.ZERO);
                failed.increment();
            } else {
                claimService.cancel(jobId, "cancelled during execution");
                cancelled.increment();
            }
        } catch (RetryableJobException e) {
            claimService.fail(jobId, e.errorCode(), e.getMessage(), true, e.suggestedBackoff());
            failed.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            claimService.cancel(jobId, "worker thread was interrupted");
            cancelled.increment();
        } catch (Exception e) {
            // Terminal in every other case. The message is bounded and safe to surface;
            // the stack trace stays in the log.
            log.error("job {} ({}) threw {}", jobId, type, e.getClass().getSimpleName(), e);
            claimService.fail(jobId, "JOB_FAILED", describe(e), false, Duration.ZERO);
            failed.increment();
        } finally {
            watchdogTask.cancel(false);
            workerThreads.remove(jobId);
            watchdogs.remove(jobId);
            watchdogs.remove(jobId);
            cancelFlags.remove(jobId);
            inFlight.decrementAndGet();
            // Per-type timer so a slow analysis type is visible in the metrics without
            // having to correlate against the job table.
            Timer.builder("repopilot.jobs.execution.by_type")
                    .tag("type", type.name())
                    .register(meterRegistry)
                    .record(Duration.between(startedAt, clock.instant()));
            executionTime.record(Duration.between(startedAt, clock.instant()));
        }
    }

    /** A bounded, non-leaking failure description for the job row. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return type + " (no message)";
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 900 ? single.substring(0, 900) + "…" : single;
    }

    // ------------------------------------------------------------------
    // Cancellation and reconciliation
    // ------------------------------------------------------------------

    /**
     * Copies durable cancellation requests into the in-process flags.
     *
     * <p>This is what makes cancellation work when the request arrives on a different
     * application instance than the one running the job. Only RUNNING jobs are read,
     * so the query is bounded by the worker count.
     */
    private void mirrorCancellationRequests() {
        List<AnalysisJob> running = jobs.findRunning();
        for (AnalysisJob job : running) {
            if (job.isCancelRequested()) {
                AtomicBoolean flag = cancelFlags.get(job.getId());
                if (flag != null) {
                    flag.set(true);
                }
            }
        }
    }

    private void beat() {
        try {
            for (AnalysisJob job : jobs.findRunning()) {
                claimService.touchHeartbeat(job.getId());
            }
        } catch (RuntimeException e) {
            log.debug("heartbeat sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Recovers jobs abandoned by a previous process. Cheap because it only considers
     * rows still marked RUNNING, and it runs every {@value #RECONCILE_EVERY_N_PASSES}
     * passes rather than continuously.
     */
    private void reconcileStale() {
        Instant staleBefore = clock.instant()
                .minusSeconds(Math.max(30, properties.jobs().staleJobThresholdSeconds()));
        List<AnalysisJob> stale = jobs.findStaleRunning(staleBefore);
        if (stale.isEmpty()) {
            return;
        }
        log.warn("{} job(s) have a stale heartbeat and will be reconciled", stale.size());
        for (AnalysisJob job : stale) {
            if (cancelFlags.containsKey(job.getId())) {
                // Actually running in this process; the heartbeat will catch up.
                continue;
            }
            claimService.recoverInterrupted(job.getId());
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        log.info("job dispatcher shutting down; waiting up to 30s for {} in-flight job(s)", inFlight.get());
        heartbeats.shutdownNow();
        watchdog.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("job workers did not finish within 30s; interrupting");
                List<Runnable> dropped = workers.shutdownNow();
                log.warn("{} queued task(s) were not started and will be recovered from the database on next start",
                        dropped.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
