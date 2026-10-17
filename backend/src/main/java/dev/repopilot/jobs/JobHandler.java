package dev.repopilot.jobs;

/**
 * Executes one kind of {@link JobType}.
 *
 * <p>Handlers are plain objects with no lifecycle of their own: no {@code start()},
 * no {@code stop()}, no internal queues. One instance is shared and invoked
 * concurrently for different jobs, so implementations must be thread-safe and must
 * keep all per-job state on the stack or inside the supplied {@link JobContext}.
 *
 * <p>Implementation rules:
 * <ol>
 *   <li>Call {@link JobContext#checkpoint()} inside every long loop.</li>
 *   <li>Never open a database transaction that spans a model call, a file scan or a
 *       profiling pass. Persist results in short transactions at the end.</li>
 *   <li>Never mutate {@link AnalysisJob} directly; return a {@link JobResult} and let
 *       the dispatcher transition the job.</li>
 *   <li>Throw {@link RetryableJobException} only when a retry is genuinely safe and
 *       might succeed. Everything else is terminal.</li>
 * </ol>
 */
public interface JobHandler {

    /** The job type this handler serves. Exactly one handler per type is permitted. */
    JobType jobType();

    /**
     * Runs the job.
     *
     * @return a result describing what actually happened
     * @throws JobAbortedException  when the context reported cancellation or timeout
     * @throws RetryableJobException when a transient failure occurred and a retry is safe
     */
    JobResult execute(JobContext context) throws Exception;
}
