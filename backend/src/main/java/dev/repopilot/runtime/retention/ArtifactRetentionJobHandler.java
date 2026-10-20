package dev.repopilot.runtime.retention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import dev.repopilot.runtime.retention.ArtifactRetentionService.PurgeOutcome;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The {@code ARTIFACT_RETENTION} job handler: removes the stored bytes of artefacts past the window.
 *
 * <p>A maintenance job rather than an analysis one, and the difference is visible in three places. It
 * produces no analysis run, because nothing was read: the measurements it keeps were produced by the
 * run that read the file back when it was still there. It is queued at the lowest priority, so a user's
 * analysis always goes first, and it is excluded from the per-repository "one job at a time" guard for
 * the same reason. And its parameter is a policy rather than a subject: the window it applies.
 *
 * <h2>The window</h2>
 * The configured window ({@code repopilot.storage.retention-days}) is the default, and a job may carry
 * {@code olderThanDays} to apply a different one for a single pass — a stricter window when disk is
 * short, say. A configured window of zero means pruning is disabled, and a pass that finds nothing to
 * apply refuses rather than reporting a successful purge of nothing: the message names the setting and
 * the parameter that overrides it. "Disabled" and "there was nothing old enough" are different answers
 * and must not look alike.
 *
 * <h2>What it reports</h2>
 * Files removed, bytes as recorded on the artefacts that were purged, how many files were already gone,
 * and how many measurements survive. The last figure is the point of the whole design: the summary says
 * what was reclaimed and what was kept, in one sentence, so nobody has to guess whether the evidence
 * went with the bytes.
 */
@Component
public class ArtifactRetentionJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionJobHandler.class);

    /** Job parameter: apply this window in days instead of the configured one. */
    public static final String OLDER_THAN_DAYS = "olderThanDays";

    private final ArtifactRetentionService retention;
    private final RepoPilotProperties properties;
    private final ObjectMapper json;

    public ArtifactRetentionJobHandler(ArtifactRetentionService retention,
                                       RepoPilotProperties properties,
                                       ObjectMapper json) {
        this.retention = retention;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public JobType jobType() {
        return JobType.ARTIFACT_RETENTION;
    }

    @Override
    public JobResult execute(JobContext context) {
        UUID repositoryId = context.repositoryId();
        if (repositoryId == null) {
            throw RepoPilotException.validation(
                    "A retention pass runs for one repository, and this job names none.");
        }
        int days = window(parameters(context.parametersJson()));
        if (days <= 0) {
            throw RepoPilotException.validation("Automatic artefact pruning is switched off "
                    + "(repopilot.storage.retention-days is " + properties.storage().retentionDays()
                    + "), so there was no window to apply. Submit the pass with a positive "
                    + "\"" + OLDER_THAN_DAYS + "\" parameter to purge a specific window anyway; this job will "
                    + "not treat \"no window\" as \"everything\".");
        }

        context.progressNow(5, "Planning retention", "Stored artefacts older than " + days + " day(s)");
        PurgeOutcome outcome = retention.purge(repositoryId, days,
                percent -> context.progress(percent, "Removing stored files", null),
                context::checkpoint);
        log.info("retention job {} finished for repository {}: {} file(s), {} bytes",
                context.jobId(), repositoryId, outcome.purged(), outcome.bytesPurged());
        return JobResult.of(summarise(outcome));
    }

    /**
     * The window to apply: the job's own if it named one, otherwise the configured retention window.
     *
     * <p>The parameter exists so that a policy can be applied once without editing configuration, and
     * the configured window exists so that an operator does not have to remember a number every time.
     * Which one applied is stated in the summary rather than left to be inferred: a purge that used the
     * default should not look like one that was asked for in a hurry.
     */
    private int window(JsonNode parameters) {
        JsonNode requested = parameters.path(OLDER_THAN_DAYS);
        return requested.isNumber() ? requested.intValue() : properties.storage().retentionDays();
    }

    /** The job's parameters as an object, or an empty one when it has none. */
    private JsonNode parameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode parsed = json.readTree(parametersJson);
            return parsed.isObject() ? parsed : json.createObjectNode();
        } catch (JsonProcessingException e) {
            throw RepoPilotException.validation(
                    "This retention job's parameters are not valid JSON: " + e.getOriginalMessage());
        }
    }

    private String summarise(PurgeOutcome outcome) {
        if (outcome.purged() == 0 && outcome.alreadyGone() == 0 && outcome.skippedShared() == 0) {
            return "Nothing to purge: no stored file is older than " + outcome.olderThanDays() + " day(s).";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Purged ").append(outcome.purged()).append(" stored file(s) older than ")
                .append(outcome.olderThanDays()).append(" day(s), reclaiming ")
                .append(outcome.bytesPurged()).append(" recorded bytes");
        if (outcome.alreadyGone() > 0) {
            summary.append("; ").append(outcome.alreadyGone())
                    .append(" were already gone from the store and had not been marked");
        }
        if (outcome.skippedShared() > 0) {
            summary.append("; ").append(outcome.skippedShared())
                    .append(" were left in place because another record still points at the same file");
        }
        summary.append(". ").append(outcome.measurementsKept())
                .append(" measurement(s) were kept, with the runs and artefacts that describe them: the bytes "
                        + "were a copy of the evidence, and the evidence is what remains.");
        return summary.toString();
    }
}
