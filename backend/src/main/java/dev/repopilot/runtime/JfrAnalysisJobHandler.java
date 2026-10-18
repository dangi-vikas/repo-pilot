package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.jobs.JobAbortedException;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import dev.repopilot.runtime.jfr.JfrMeasurementMapper;
import dev.repopilot.runtime.jfr.JfrReader;
import dev.repopilot.runtime.jfr.JfrRecordingReport;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code JFR_ANALYSIS} job handler: reads one uploaded recording and stores what it says.
 *
 * <h2>What this produces, and what it refuses to produce</h2>
 * Measurements, not findings. A recording is a fact about one process on one machine over one
 * window; it has no line to cite and no severity that does not come from a policy RepoPilot
 * has not defined. Adding a "paused longer than 200 ms is a problem" rule here would be
 * inventing a threshold and presenting it as a measurement, so no thresholds are applied and
 * the run says so.
 *
 * <h2>Why the run is created before the file is read</h2>
 * A run is the record that the analysis happened. If reading throws, the run is closed
 * {@code FAILED} with the reason attached, so "I uploaded a recording and nothing appeared"
 * has an answer rather than silence. The one exception is a request that cannot start at all
 * &mdash; a heap dump asked of the JFR reader &mdash; which fails before the run exists,
 * because a run row claiming a JFR analysis would be a false statement about what happened.
 *
 * <h2>Transactions</h2>
 * None is held across the parse. The recording is read with no database connection in hand,
 * measurements are written in one batch, and the run is closed afterwards; a cancelled or
 * failed run therefore leaves whatever it had already committed rather than rolling back a
 * parse that took minutes.
 */
@Component
public class JfrAnalysisJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(JfrAnalysisJobHandler.class);

    /** Version of the reader, recorded on the run so measurements can be traced to a parser. */
    private static final String READER_VERSION = "jfr-reader=1.0.0";

    /** Hard cap on rows written by one run. The metric set is bounded; this is a backstop. */
    private static final int MAX_MEASUREMENTS = 5_000;

    private static final int MAX_SUMMARY = 4_000;

    private final RuntimeArtifactRepository artifacts;
    private final RuntimeMeasurementRepository measurements;
    private final AnalysisRunRepository runs;
    private final ArtifactStore store;
    private final ObjectMapper json;

    public JfrAnalysisJobHandler(RuntimeArtifactRepository artifacts,
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

    @Override
    public JobType jobType() {
        return JobType.JFR_ANALYSIS;
    }

    @Override
    public JobResult execute(JobContext context) {
        UUID repositoryId = context.repositoryId();
        if (repositoryId == null) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A JFR_ANALYSIS job requires a repository id");
        }
        UUID artifactId = artifactId(context.parametersJson());
        RuntimeArtifact artifact = artifacts.findByIdAndRepositoryId(artifactId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Runtime artefact", artifactId));

        if (artifact.getArtifactType() != RuntimeArtifactType.JFR_RECORDING) {
            throw RepoPilotException.unsupported("This job reads JDK Flight Recorder files. "
                    + artifact.getOriginalFilename() + " was uploaded as " + artifact.getArtifactType() + ".");
        }
        String unsupported = artifact.getArtifactType().unsupportedReason();
        if (unsupported != null) {
            throw RepoPilotException.unsupported(unsupported);
        }
        Path recording = store.resolve(artifact.getStorageRef());

        Instant startedAt = Instant.now();
        AnalysisRun run = runs.save(new AnalysisRun(repositoryId, null, context.jobId(),
                AnalysisRunType.JFR_ANALYSIS, startedAt));
        run.attachArtifact(artifact.getOriginalFilename(), artifact.getContentHash(),
                artifact.getSizeBytes(), artifact.getStorageRef());
        run = runs.save(run);

        artifact.setAnalysisRunId(run.getId());
        artifacts.save(artifact);

        try {
            context.progressNow(10, "Reading recording", "Parsing " + artifact.getOriginalFilename());
            JfrRecordingReport report = JfrReader.read(recording, context::checkpoint);
            context.checkpoint();

            context.progressNow(60, "Deriving measurements", report.eventCount() + " events");
            List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(report);
            boolean truncated = drafts.size() > MAX_MEASUREMENTS;
            if (truncated) {
                drafts = drafts.subList(0, MAX_MEASUREMENTS);
            }
            List<RuntimeMeasurement> rows = new ArrayList<>(drafts.size());
            for (MeasurementDraft draft : drafts) {
                rows.add(new RuntimeMeasurement(repositoryId, run.getId(), artifact.getId(), draft,
                        attributesJson(draft)));
            }
            measurements.saveAll(rows);

            artifact.setMetadataJson(provenanceJson(report, rows.size()));
            artifacts.save(artifact);

            String summary = summarise(report, rows.size(), truncated);
            run.complete(AnalysisRunStatus.SUCCEEDED, READER_VERSION, summary, 0, rows.size(), Instant.now());
            runs.save(run);
            context.progressNow(100, "Done", rows.size() + " measurements");
            log.info("JFR run {} finished: {}", run.getId(), summary);
            return JobResult.of(summary, run.getId());

        } catch (JobAbortedException aborted) {
            run.complete(AnalysisRunStatus.CANCELLED, READER_VERSION,
                    "cancelled while reading " + artifact.getOriginalFilename(), 0, 0, Instant.now());
            runs.save(run);
            throw aborted;
        } catch (IOException e) {
            String reason = "The uploaded file could not be read as a JFR recording: " + e.getMessage();
            run.complete(AnalysisRunStatus.FAILED, READER_VERSION, truncate(reason), 0, 0, Instant.now());
            runs.save(run);
            throw RepoPilotException.unsupported(reason);
        } catch (RuntimeException e) {
            String reason = "JFR analysis failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            run.complete(AnalysisRunStatus.FAILED, READER_VERSION, truncate(reason), 0, 0, Instant.now());
            runs.save(run);
            throw e;
        }
    }

    /**
     * The recording's provenance, stored on the artefact and echoed into the run summary.
     *
     * <p>No command-line arguments, no system properties and no environment variables. They
     * are present in the file and are exactly where a JVM carries credentials; a provenance
     * block that ends up in a database is the wrong place for them.
     */
    private String provenanceJson(JfrRecordingReport report, int measurementCount) {
        JfrRecordingReport.Provenance provenance = report.provenance();
        ObjectNode node = json.createObjectNode();
        ObjectNode environment = node.putObject("environment");
        putIfPresent(environment, "jvmName", provenance.jvmName());
        putIfPresent(environment, "jvmVersion", provenance.jvmVersion());
        putIfPresent(environment, "jvmStartTime", provenance.jvmStartTime() == null
                ? null : provenance.jvmStartTime().toString());
        if (provenance.processId() > 0) {
            environment.put("processId", provenance.processId());
        }
        putIfPresent(environment, "osVersion", provenance.osVersion());
        putIfPresent(environment, "cpu", provenance.cpuDescription());
        if (provenance.cpuCores() > 0) {
            environment.put("cpuCores", provenance.cpuCores());
        }
        if (provenance.hardwareThreads() > 0) {
            environment.put("hardwareThreads", provenance.hardwareThreads());
        }
        if (provenance.physicalMemoryBytes() > 0) {
            environment.put("physicalMemoryBytes", provenance.physicalMemoryBytes());
            environment.put("physicalMemoryUsedBytes", provenance.physicalMemoryUsedBytes());
        }

        ObjectNode recording = node.putObject("recording");
        putIfPresent(recording, "name", provenance.recordingName());
        putIfPresent(recording, "startedAt", provenance.recordingStart() == null
                ? null : provenance.recordingStart().toString());
        putIfPresent(recording, "firstEventAt", report.firstEvent() == null
                ? null : report.firstEvent().toString());
        putIfPresent(recording, "lastEventAt", report.lastEvent() == null
                ? null : report.lastEvent().toString());
        recording.put("events", report.eventCount());
        recording.put("eventTypes", report.eventTypeCount());
        if (report.unreadableEvents() > 0) {
            recording.put("unreadableEvents", report.unreadableEvents());
        }

        ObjectNode produced = node.putObject("produced");
        produced.put("measurements", measurementCount);
        produced.put("findings", 0);
        produced.put("thresholdsApplied", false);
        node.put("note", "Measurements describe this recording of one process, on this machine, over its own "
                + "window. No threshold was applied and no severity was assigned: RepoPilot states what was "
                + "measured, and says so here when it has not decided what the number means.");
        return node.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private String summarise(JfrRecordingReport report, int measurementCount, boolean truncated) {
        double windowMillis = report.firstEvent() != null && report.lastEvent() != null
                ? Duration.between(report.firstEvent(), report.lastEvent()).toNanos() / 1_000_000.0
                : 0;
        StringBuilder summary = new StringBuilder();
        summary.append(measurementCount).append(" measurement(s) from a ")
                .append(String.format("%.1f", windowMillis / 1000.0)).append(" s recording of ");
        summary.append(jvmLabel(report.provenance().jvmName(), report.provenance().jvmVersion()));
        summary.append(": ").append(report.eventCount()).append(" events across ")
                .append(report.eventTypeCount()).append(" event types");

        appendIfNonZero(summary, report.executionSamples(), "execution sample(s)");
        appendIfNonZero(summary, report.nativeSamples(), "native sample(s)");
        appendIfNonZero(summary, report.allocation().sampleCount(), "allocation sample(s)");
        JfrRecordingReport.DurationStats pauses = report.activity().get(JfrReader.CHANNEL_GC_PAUSE);
        if (pauses != null && pauses.count() > 0) {
            summary.append("; ").append(pauses.count()).append(" GC pause(s) totalling ")
                    .append(String.format("%.0f", pauses.totalMillis())).append(" ms");
        }
        if (report.unreadableEvents() > 0) {
            summary.append("; ").append(report.unreadableEvents())
                    .append(" event(s) could not be read and were excluded");
        }
        if (truncated) {
            summary.append("; only the first ").append(MAX_MEASUREMENTS).append(" measurements were stored");
        }
        summary.append(" — no threshold applied, no severity assigned");
        return truncate(summary.toString());
    }

    private static void appendIfNonZero(StringBuilder summary, long value, String label) {
        if (value > 0) {
            summary.append("; ").append(value).append(' ').append(label);
        }
    }

    /**
     * The JVM as one readable phrase, without saying its name twice.
     *
     * <p>{@code jdk.JVMInformation.jvmVersion} is the JVM's full build string, which already
     * begins with the VM name: {@code "Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239) for
     * windows-amd64 JRE (21.0.5+9-LTS-239), built on 2024-09-30T05:17:38Z by …"}. Appending
     * both fields, as the first draft of this summary did, produces the name twice; printing the
     * version whole buries the sentence. So the version becomes its build token and the name is
     * kept once — and both raw values live on in the stored provenance, which is what a reader
     * who wants the file's own words gets.
     *
     * <p>The token is taken from the first parenthesised group that looks like a version — the
     * one containing a digit — because the name itself contains parentheses ({@code "(TM)"}) and
     * the build string contains several groups.
     */
    static String jvmLabel(String jvmName, String jvmVersion) {
        boolean hasName = jvmName != null && !jvmName.isBlank();
        boolean hasVersion = jvmVersion != null && !jvmVersion.isBlank();
        if (!hasName && !hasVersion) {
            return "an unnamed JVM";
        }
        if (!hasVersion) {
            return jvmName.trim();
        }
        String version = jvmVersion.trim();
        String remainder = hasName && version.startsWith(jvmName.trim())
                ? version.substring(jvmName.trim().length())
                : " " + version;
        String token = versionToken(remainder);
        String detail = token != null ? token : version;
        return hasName ? jvmName.trim() + " " + detail : detail;
    }

    /** The first parenthesised group containing a digit, or null when there is no such group. */
    private static String versionToken(String text) {
        int index = 0;
        while (true) {
            int open = text.indexOf('(', index);
            if (open < 0) {
                return null;
            }
            int close = text.indexOf(')', open + 1);
            if (close < 0) {
                return null;
            }
            String candidate = text.substring(open + 1, close).trim();
            if (candidate.chars().anyMatch(Character::isDigit)) {
                return candidate;
            }
            index = close + 1;
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 3) + "...";
    }

    private String attributesJson(MeasurementDraft draft) {
        Map<String, Object> attributes = draft.attributes();
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(new LinkedHashMap<>(attributes));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Attributes are documentation, not the deliverable: failing a run because a
            // caveat could not be serialised would be worse than storing the measurement
            // without it, and the log says which one.
            log.warn("could not serialise attributes for {}: {}", draft.metricKey(), e.getMessage());
            return null;
        }
    }

    private UUID artifactId(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A JFR_ANALYSIS job requires the artefact to read, but it was submitted without parameters.");
        }
        try {
            JsonNode node = json.readTree(parametersJson);
            JsonNode value = node.get("artifactId");
            if (value == null || value.isNull()) {
                throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                        "A JFR_ANALYSIS job requires an 'artifactId' parameter.");
            }
            return UUID.fromString(value.asText());
        } catch (IOException | IllegalArgumentException e) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A JFR_ANALYSIS job requires a valid 'artifactId' parameter.");
        }
    }
}
