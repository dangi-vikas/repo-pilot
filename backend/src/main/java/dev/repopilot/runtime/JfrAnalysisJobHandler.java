package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.runtime.jfr.JfrMeasurementMapper;
import dev.repopilot.runtime.jfr.JfrReader;
import dev.repopilot.runtime.jfr.JfrRecordingReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The {@code JFR_ANALYSIS} job handler: reads one uploaded recording and stores what it says.
 *
 * <p>Measurements, not findings, and the run lifecycle belongs to
 * {@link AbstractRuntimeAnalysisHandler}; what is here is the part that knows what a JFR file is.
 * A recording is a fact about one process on one machine over one window, so no threshold is
 * applied and the run says so.
 *
 * <p>The provenance deliberately carries no command-line arguments, no system properties and no
 * environment variables, although the file contains all three: they are exactly where a JVM carries
 * credentials, and a provenance block that ends up in a database is the wrong place for them.
 */
@Component
public class JfrAnalysisJobHandler extends AbstractRuntimeAnalysisHandler {

    /** Hard cap on rows written by one run. The metric set is bounded; this is a backstop. */
    private static final int MAX_MEASUREMENTS = 5_000;

    private final ObjectMapper json;

    public JfrAnalysisJobHandler(RuntimeArtifactRepository artifacts,
                                 RuntimeMeasurementRepository measurements,
                                 AnalysisRunRepository runs,
                                 ArtifactStore store,
                                 ObjectMapper json) {
        super(artifacts, measurements, runs, store, json);
        this.json = json;
    }

    @Override
    protected RuntimeArtifactType artifactType() {
        return RuntimeArtifactType.JFR_RECORDING;
    }

    @Override
    protected AnalysisRunType runType() {
        return AnalysisRunType.JFR_ANALYSIS;
    }

    @Override
    protected String readerVersion() {
        return "jfr-reader=1.0.0";
    }

    @Override
    protected String subject() {
        return "a JFR recording";
    }

    @Override
    protected AnalysisOutcome analyse(Path recording, RuntimeArtifact artifact, JobContext context)
            throws IOException {
        context.progressNow(10, "Reading recording", "Parsing " + artifact.getOriginalFilename());
        JfrRecordingReport report = JfrReader.read(recording, context::checkpoint);

        context.progressNow(60, "Deriving measurements", report.eventCount() + " events");
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(report);
        boolean truncated = drafts.size() > MAX_MEASUREMENTS;
        if (truncated) {
            drafts = drafts.subList(0, MAX_MEASUREMENTS);
        }
        return new AnalysisOutcome(drafts, provenanceJson(report, drafts.size()),
                summarise(report, drafts.size(), truncated));
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
        summary.append(JvmLabels.jvmLabel(report.provenance().jvmName(), report.provenance().jvmVersion()));
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
        return summary.toString();
    }

    private static void appendIfNonZero(StringBuilder summary, long value, String label) {
        if (value > 0) {
            summary.append("; ").append(value).append(' ').append(label);
        }
    }
}
