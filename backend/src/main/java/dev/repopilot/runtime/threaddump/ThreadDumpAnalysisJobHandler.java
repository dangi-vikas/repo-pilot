package dev.repopilot.runtime.threaddump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.runtime.AbstractRuntimeAnalysisHandler;
import dev.repopilot.runtime.ArtifactStore;
import dev.repopilot.runtime.JvmLabels;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.RuntimeArtifact;
import dev.repopilot.runtime.RuntimeArtifactRepository;
import dev.repopilot.runtime.RuntimeArtifactType;
import dev.repopilot.runtime.RuntimeMeasurementRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code THREAD_DUMP_ANALYSIS} job handler: reads one uploaded thread dump and stores what it says.
 *
 * <p>Measurements, not findings. A dump is one instant of one process: it has no line to cite, and
 * the difference between "three threads waited for this monitor" and "this monitor is a problem" is
 * a policy nobody has stated. So no threshold is applied, no severity is assigned, and the run says
 * both out loud.
 *
 * <p>What is deliberately absent from the provenance: the thread stacks themselves. The provenance
 * block is stored on the artefact and read back by the UI, and a dump's stacks are the application's
 * own structure — class, method and file names — which belongs in the file the operator uploaded,
 * not copied into a database column. The <em>counts</em> derived from those stacks are stored, and
 * no source text is stored anywhere.
 */
@Component
public class ThreadDumpAnalysisJobHandler extends AbstractRuntimeAnalysisHandler {

    /** Hard cap on rows written by one run. The metric set is bounded; this is a backstop. */
    private static final int MAX_MEASUREMENTS = 5_000;

    private final ObjectMapper json;

    public ThreadDumpAnalysisJobHandler(RuntimeArtifactRepository artifacts,
                                        RuntimeMeasurementRepository measurements,
                                        AnalysisRunRepository runs,
                                        ArtifactStore store,
                                        ObjectMapper json) {
        super(artifacts, measurements, runs, store, json);
        this.json = json;
    }

    @Override
    protected RuntimeArtifactType artifactType() {
        return RuntimeArtifactType.THREAD_DUMP;
    }

    @Override
    protected AnalysisRunType runType() {
        return AnalysisRunType.THREAD_DUMP_ANALYSIS;
    }

    @Override
    protected String readerVersion() {
        return "thread-dump-reader=1.0.0";
    }

    @Override
    protected String subject() {
        return "a thread dump";
    }

    @Override
    protected AnalysisOutcome analyse(Path file, RuntimeArtifact artifact, JobContext context)
            throws IOException {
        context.progressNow(10, "Reading dump", "Parsing " + artifact.getOriginalFilename());
        ThreadDumpReport report = ThreadDumpReader.read(file, context::checkpoint);

        context.progressNow(60, "Deriving measurements", report.threads().size() + " thread(s)");
        List<MeasurementDraft> drafts = ThreadDumpMeasurementMapper.toMeasurements(report);
        if (drafts.size() > MAX_MEASUREMENTS) {
            drafts = drafts.subList(0, MAX_MEASUREMENTS);
        }
        return new AnalysisOutcome(drafts, provenanceJson(report, drafts.size()),
                summarise(report, drafts.size()));
    }

    /**
     * What the dump was, stored on the artefact.
     *
     * <p>No stacks, no thread names in bulk and no frame text: the provenance is the header and the
     * counts. The names of the threads in a deadlock cycle are the one exception, and they travel in
     * the measurement's own attributes, next to the cycle they belong to.
     */
    private String provenanceJson(ThreadDumpReport report, int measurementCount) {
        ThreadDumpReport.Provenance provenance = report.provenance();
        ObjectNode node = json.createObjectNode();
        ObjectNode environment = node.putObject("environment");
        putIfPresent(environment, "jvmName", provenance.jvmName());
        putIfPresent(environment, "jvmVersion", provenance.jvmVersion());
        putIfPresent(environment, "jvmMode", provenance.jvmMode());
        if (provenance.processId() > 0) {
            environment.put("processId", provenance.processId());
        }

        ObjectNode dump = node.putObject("dump");
        putIfPresent(dump, "takenAt", provenance.takenAt());
        if (provenance.takenAt() != null) {
            dump.put("takenAtBasis", "the dump's own timestamp line: the JVM writes local time there and the "
                    + "file records no zone, so it is kept as text rather than converted to an instant "
                    + "RepoPilot would have had to guess");
        }
        dump.put("threads", report.threads().size());
        dump.put("javaThreads", report.javaThreadCount());
        dump.put("threadsWithoutJavaState", report.threadsWithoutJavaState());
        if (report.unknownStateCount() > 0) {
            dump.put("threadsWithUnknownState", report.unknownStateCount());
        }
        if (provenance.javaThreadListLength() != null) {
            dump.put("javaThreadListLength", provenance.javaThreadListLength());
        }
        dump.put("deadlocks", report.statedDeadlockCount() != null
                ? report.statedDeadlockCount() : report.deadlocks().size());
        dump.put("contendedMonitors", report.contendedMonitors().size());

        ObjectNode produced = node.putObject("produced");
        produced.put("measurements", measurementCount);
        produced.put("findings", 0);
        produced.put("thresholdsApplied", false);
        node.put("note", "Measurements describe one instant of one process on this machine. A dump is a "
                + "snapshot, not a profile: nothing here is a rate or a trend, and no threshold was applied "
                + "and no severity assigned. RepoPilot states what the dump said and leaves the judgement open.");
        return node.toString();
    }

    private String summarise(ThreadDumpReport report, int measurementCount) {
        ThreadDumpReport.Provenance provenance = report.provenance();
        StringBuilder summary = new StringBuilder();
        summary.append(measurementCount).append(" measurement(s) from a thread dump of ")
                .append(JvmLabels.jvmLabel(provenance.jvmName(), provenance.jvmVersion()))
                .append(": ").append(report.threads().size()).append(" thread(s), ")
                .append(report.javaThreadCount()).append(" with a Java state");

        String states = report.stateCounts().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        summary.append(" (").append(states).append(")");

        int cycles = report.statedDeadlockCount() != null
                ? report.statedDeadlockCount() : report.deadlocks().size();
        if (cycles > 0) {
            summary.append("; ").append(cycles).append(" deadlock cycle(s) involving ")
                    .append(report.deadlockedThreadCount()).append(" thread(s), as the dump's own detector "
                            + "reported them");
        } else {
            summary.append("; the dump's own detector reported no deadlock");
        }

        int contended = report.contendedMonitors().size();
        if (contended > 0) {
            summary.append("; ").append(contended).append(" monitor(s) with a thread waiting to enter them");
        } else {
            summary.append("; no thread was waiting to enter a monitor");
        }

        if (provenance.takenAt() != null) {
            summary.append("; taken ").append(provenance.takenAt())
                    .append(" (the dump's local time, no zone recorded)");
        }
        summary.append(" — one instant, no threshold applied, no severity assigned");
        return summary.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
