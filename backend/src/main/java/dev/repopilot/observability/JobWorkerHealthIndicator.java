package dev.repopilot.observability;

import dev.repopilot.jobs.JobDispatcher;
import dev.repopilot.jobs.JobService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor for the background job system.
 *
 * <p>Reports whether the worker pool can accept work. Workers being fully saturated is
 * deliberately reported as {@code UP} with a detail: a busy system is not a broken one,
 * and marking it unready would take a working instance out of service exactly when it is
 * doing the most useful work.
 */
@Component("repoPilotWorkers")
public class JobWorkerHealthIndicator implements HealthIndicator {

    private final JobDispatcher dispatcher;
    private final JobService jobService;

    public JobWorkerHealthIndicator(JobDispatcher dispatcher, JobService jobService) {
        this.dispatcher = dispatcher;
        this.jobService = jobService;
    }

    @Override
    public Health health() {
        int workers = dispatcher.workerThreads();
        int inFlight = dispatcher.inFlight();
        try {
            JobService.JobCounters counters = jobService.counters();
            return Health.up()
                    .withDetail("workerThreads", workers)
                    .withDetail("inFlight", inFlight)
                    .withDetail("freeSlots", dispatcher.freeSlots())
                    .withDetail("queuedJobs", counters.queued())
                    .withDetail("runningJobs", counters.running())
                    .withDetail("saturated", inFlight >= workers)
                    .build();
        } catch (RuntimeException e) {
            // Job counters require the database. If the database is down, the datasource
            // health indicator reports that separately; this one should not duplicate it.
            return Health.up()
                    .withDetail("workerThreads", workers)
                    .withDetail("inFlight", inFlight)
                    .withDetail("freeSlots", dispatcher.freeSlots())
                    .withDetail("detailUnavailable", "job counters could not be read: "
                            + e.getClass().getSimpleName())
                    .build();
        }
    }
}
