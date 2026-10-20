package dev.repopilot.runtime.retention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.Repository;
import dev.repopilot.repository.RepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Queues the retention pass for every repository, once a day.
 *
 * <p>A scheduler that submits jobs rather than one that does the work. Deleting files inside a
 * {@code @Scheduled} method would put the work outside the job system — no progress, no cancellation, no
 * record in the job list, no retry policy — and retention is exactly the kind of work an operator will
 * want to see a row for. So the sweep is cheap and frequent, and the work is a job: the sweep asks
 * "should this repository be purged today, and is nobody using it?", and the job answers.
 *
 * <h2>Why the idempotency key is a date</h2>
 * The sweep runs hourly but a purge should happen once a day. The key
 * {@code retention:<repository>:<date>} makes the second, third and twenty-third pass of the day
 * return the job the first one created, which is what {@link JobService#submit} already does for
 * double-clicks. No state is kept here to remember that today's pass ran, so a restart mid-day cannot
 * forget that it did or believe it did not.
 *
 * <h2>When it defers</h2>
 * If the repository has anything active, the pass waits for the next sweep: a scheduled tidy-up has no
 * business competing with work somebody is waiting for. It is queued at the lowest priority for the
 * same reason, and the interactive guards do not count it as activity, so even a queued retention job
 * cannot keep an analysis out.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RepositoryRepository repositories;
    private final JobService jobs;
    private final RepoPilotProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public RetentionScheduler(RepositoryRepository repositories,
                              JobService jobs,
                              RepoPilotProperties properties,
                              ObjectMapper json,
                              Clock clock) {
        this.repositories = repositories;
        this.jobs = jobs;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Submits one retention job per repository that is due and idle.
     *
     * <p>Not transactional and not exception-fatal per repository: one repository whose job could not be
     * queued must not stop the sweep for the rest, and the next sweep will try again.
     */
    @Scheduled(fixedDelayString = "${repopilot.storage.retention-sweep-interval-millis:3600000}",
            initialDelayString = "${repopilot.storage.retention-initial-delay-millis:120000}")
    public void enqueueRetention() {
        int days = properties.storage().retentionDays();
        if (days <= 0) {
            log.debug("artefact retention is switched off (retention-days=0); not queueing a pass");
            return;
        }
        List<Repository> all = repositories.findAll();
        if (all.isEmpty()) {
            return;
        }
        String day = LocalDate.now(clock).toString();
        int queued = 0;
        for (Repository repository : all) {
            try {
                if (jobs.countActiveForRepository(repository.getId()) > 0) {
                    continue;
                }
                JobService.Submission submission = jobs.submit(JobType.ARTIFACT_RETENTION,
                        repository.getWorkspaceId(),
                        repository.getId(),
                        parameters(days),
                        "retention:" + repository.getId() + ":" + day);
                if (submission.created()) {
                    queued++;
                }
            } catch (RuntimeException e) {
                log.warn("could not queue a retention pass for repository {}: {}",
                        repository.getId(), e.getMessage());
            }
        }
        if (queued > 0) {
            log.info("queued {} retention pass(es) for a {}-day window", queued, days);
        }
    }

    /**
     * The window the job should apply, stated in the job's own parameters.
     *
     * <p>Written out rather than left to the handler's default so that the job list records which policy
     * ran: when configuration changes tomorrow, yesterday's job still says what it was asked to do.
     */
    private String parameters(int days) {
        ObjectNode parameters = json.createObjectNode();
        parameters.put(ArtifactRetentionJobHandler.OLDER_THAN_DAYS, days);
        try {
            return json.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise retention parameters", e);
        }
    }
}
