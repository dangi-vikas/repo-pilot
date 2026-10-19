package dev.repopilot.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.jobs.JobAbortedException;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lifecycle every runtime reader shares: read one uploaded artefact, store what it measured.
 *
 * <h2>What is here, and what is not</h2>
 * The run lifecycle exists once, because it is the part that fails silently when it is copied. A
 * second handler that forgot to close its run on cancellation, or created the run after reading the
 * file, would look correct in review and leave a run row that says nothing happened. What is
 * <em>not</em> here is anything about a particular file format: a subclass reads its bytes in
 * {@link #analyse} and states what it measured, and this class turns that into rows.
 *
 * <h2>Measurements, not findings</h2>
 * A recording or a dump is a fact about one process on one machine at one moment. It has no line to
 * cite and no severity that does not come from a policy RepoPilot has not defined, so readers
 * produce measurements, apply no threshold, and say so in the provenance. Two readers exist under
 * this contract: JFR recordings and thread dumps.
 *
 * <h2>Why the run is created before the file is read</h2>
 * A run is the record that the analysis happened. If reading throws, the run is closed
 * {@code FAILED} with the reason attached, so "I uploaded a file and nothing appeared" has an
 * answer rather than silence. The one exception is a request that cannot start at all — a heap
 * dump asked of the JFR reader — which fails before the run exists, because a run row claiming a
 * JFR analysis would be a false statement about what happened.
 *
 * <h2>Transactions</h2>
 * None is held across the parse. The file is read with no database connection in hand, measurements
 * are written in one batch, and the run is closed afterwards; a cancelled or failed run therefore
 * leaves whatever it had already committed rather than rolling back a parse that took minutes.
 */
public abstract class AbstractRuntimeAnalysisHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractRuntimeAnalysisHandler.class);

    /** Room for the reader's own prose, capped so one summary cannot become a document. */
    protected static final int MAX_SUMMARY = 4_000;

    private final RuntimeArtifactRepository artifacts;
    private final RuntimeMeasurementRepository measurements;
    private final AnalysisRunRepository runs;
    private final ArtifactStore store;
    private final ObjectMapper json;

    protected AbstractRuntimeAnalysisHandler(RuntimeArtifactRepository artifacts,
                                             RuntimeMeasurementRepository measurements,
                                             AnalysisRunRepository runs,
                                             ArtifactStore store,
                                             ObjectMapper json) {
        this.artifacts = artifacts;
        this.measurements = measurements;
        this.runs = runs;
        this.store = store;
        this.json = json;
    }

    /**
     * The kind of artefact this handler reads.
     *
     * <p>Also decides {@link #jobType()}, so a reader cannot claim one kind of file in its handler
     * and another in the type it refuses.
     */
    protected abstract RuntimeArtifactType artifactType();

    /** The run type recorded on the run row, so measurements can be read back with their pipeline. */
    protected abstract AnalysisRunType runType();

    /** Version of the reader, recorded on the run so a measurement can be traced to a parser. */
    protected abstract String readerVersion();

    /**
     * The artefact as a noun phrase, used in refusals: {@code "a JFR recording"},
     * {@code "a thread dump"}.
     */
    protected abstract String subject();

    /**
     * Reads the file and derives what this reader produces.
     *
     * <p>Called with no transaction open and after the run row exists. The implementation owns its
     * own progress messages; the caller owns everything else.
     */
    protected abstract AnalysisOutcome analyse(Path file, RuntimeArtifact artifact, JobContext context)
            throws IOException;

    /**
     * What one analysis produced.
     *
     * @param measurements  the rows to store, already capped by the reader that produced them
     * @param provenanceJson what was read, stored on the artefact so the numbers keep their origin
     * @param summary       the run's one-paragraph account of itself
     */
    public record AnalysisOutcome(List<MeasurementDraft> measurements, String provenanceJson, String summary) {
    }

    @Override
    public final JobType jobType() {
        return artifactType().jobType();
    }

    @Override
    public final JobResult execute(JobContext context) {
        UUID repositoryId = context.repositoryId();
        if (repositoryId == null) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A " + jobType().name() + " job requires a repository id");
        }
        UUID artifactId = artifactId(context.parametersJson());
        RuntimeArtifact artifact = artifacts.findByIdAndRepositoryId(artifactId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Runtime artefact", artifactId));

        if (artifact.getArtifactType() != artifactType()) {
            throw RepoPilotException.unsupported("This job reads " + subject() + ": "
                    + artifact.getOriginalFilename() + " was uploaded as " + artifact.getArtifactType() + ".");
        }
        String unsupported = artifact.getArtifactType().unsupportedReason();
        if (unsupported != null) {
            throw RepoPilotException.unsupported(unsupported);
        }
        Path file = store.resolve(artifact.getStorageRef());

        AnalysisRun run = runs.save(new AnalysisRun(repositoryId, null, context.jobId(),
                runType(), Instant.now()));
        run.attachArtifact(artifact.getOriginalFilename(), artifact.getContentHash(),
                artifact.getSizeBytes(), artifact.getStorageRef());
        run = runs.save(run);

        artifact.setAnalysisRunId(run.getId());
        artifacts.save(artifact);

        try {
            AnalysisOutcome outcome = analyse(file, artifact, context);
            context.checkpoint();

            List<MeasurementDraft> drafts = outcome.measurements();
            List<RuntimeMeasurement> rows = new ArrayList<>(drafts.size());
            for (MeasurementDraft draft : drafts) {
                rows.add(new RuntimeMeasurement(repositoryId, run.getId(), artifact.getId(), draft,
                        attributesJson(draft)));
            }
            measurements.saveAll(rows);

            artifact.setMetadataJson(outcome.provenanceJson());
            artifacts.save(artifact);

            String summary = truncate(outcome.summary());
            run.complete(AnalysisRunStatus.SUCCEEDED, readerVersion(), summary, 0, rows.size(), Instant.now());
            runs.save(run);
            context.progressNow(100, "Done", rows.size() + " measurements");
            log.info("{} run {} finished: {}", jobType().name(), run.getId(), summary);
            return JobResult.of(summary, run.getId());

        } catch (JobAbortedException aborted) {
            run.complete(AnalysisRunStatus.CANCELLED, readerVersion(),
                    "cancelled while reading " + artifact.getOriginalFilename(), 0, 0, Instant.now());
            runs.save(run);
            throw aborted;
        } catch (IOException e) {
            String reason = "The uploaded file could not be read as " + subject() + ": " + e.getMessage();
            run.complete(AnalysisRunStatus.FAILED, readerVersion(), truncate(reason), 0, 0, Instant.now());
            runs.save(run);
            throw RepoPilotException.unsupported(reason);
        } catch (RuntimeException e) {
            String reason = jobType().name() + " failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            run.complete(AnalysisRunStatus.FAILED, readerVersion(), truncate(reason), 0, 0, Instant.now());
            runs.save(run);
            throw e;
        }
    }

    /** The artefact id a job carries, or a refusal that names the missing parameter. */
    protected final UUID artifactId(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A " + jobType().name() + " job requires the artefact to read, but it was submitted "
                            + "without parameters.");
        }
        try {
            JsonNode node = json.readTree(parametersJson);
            JsonNode value = node.get("artifactId");
            if (value == null || value.isNull()) {
                throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                        "A " + jobType().name() + " job requires an 'artifactId' parameter.");
            }
            return UUID.fromString(value.asText());
        } catch (IOException | IllegalArgumentException e) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A " + jobType().name() + " job requires a valid 'artifactId' parameter.");
        }
    }

    private String attributesJson(MeasurementDraft draft) {
        Map<String, Object> attributes = draft.attributes();
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(new LinkedHashMap<>(attributes));
        } catch (JsonProcessingException e) {
            // Attributes are documentation, not the deliverable: failing a run because a
            // caveat could not be serialised would be worse than storing the measurement
            // without it, and the log says which one.
            log.warn("could not serialise attributes for {}: {}", draft.metricKey(), e.getMessage());
            return null;
        }
    }

    /** Bounds a run summary so one reader's prose cannot become a document. */
    protected static String truncate(String text) {
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 3) + "...";
    }
}
