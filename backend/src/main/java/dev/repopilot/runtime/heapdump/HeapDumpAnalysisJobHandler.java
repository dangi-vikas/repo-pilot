package dev.repopilot.runtime.heapdump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.runtime.AbstractRuntimeAnalysisHandler;
import dev.repopilot.runtime.ArtifactStore;
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
 * The {@code HEAP_ANALYSIS} job handler: reads one uploaded heap dump and stores what it recorded.
 *
 * <p>Measurements, not findings. A heap dump has no line to cite and no severity that does not come
 * from a policy RepoPilot has not been told, so nothing here applies a threshold. What it does have
 * is a much stronger claim to make than a thread dump: an object census is exact. Every count and
 * every byte figure below is read out of the file, not estimated, and the numbers in this phase were
 * checked against {@code jmap -histo} for the same JVM.
 *
 * <p>Because a heap dump is mostly the application's own data, the provenance is deliberately narrow.
 * It records the header, the file's size and the census; it does not record the type names in bulk
 * (those travel with the type rows they belong to), and it records nothing at all about field values
 * or object identities. RepoPilot reads a dump for its shape, and the bytes of an application's heap
 * stay in the file the operator uploaded.
 */
@Component
public class HeapDumpAnalysisJobHandler extends AbstractRuntimeAnalysisHandler {

    /** Hard cap on rows written by one run. The metric set is bounded; this is a backstop. */
    private static final int MAX_MEASUREMENTS = 5_000;

    private final ObjectMapper json;

    public HeapDumpAnalysisJobHandler(RuntimeArtifactRepository artifacts,
                                      RuntimeMeasurementRepository measurements,
                                      AnalysisRunRepository runs,
                                      ArtifactStore store,
                                      ObjectMapper json) {
        super(artifacts, measurements, runs, store, json);
        this.json = json;
    }

    @Override
    protected RuntimeArtifactType artifactType() {
        return RuntimeArtifactType.HEAP_DUMP;
    }

    @Override
    protected AnalysisRunType runType() {
        return AnalysisRunType.HEAP_ANALYSIS;
    }

    @Override
    protected String readerVersion() {
        return HeapDumpReader.READER_VERSION;
    }

    @Override
    protected String subject() {
        return "a heap dump";
    }

    @Override
    protected AnalysisOutcome analyse(Path file, RuntimeArtifact artifact, JobContext context)
            throws IOException {
        HeapDumpReport report = HeapDumpReader.read(file, new JobProgress(context));

        context.progressNow(72, "Deriving measurements",
                report.census().namedTypes() + " type(s)");
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(report);
        if (drafts.size() > MAX_MEASUREMENTS) {
            drafts = drafts.subList(0, MAX_MEASUREMENTS);
        }
        return new AnalysisOutcome(drafts, provenanceJson(report, drafts.size()),
                summarise(report, drafts.size()));
    }

    /** Adapts the job's checkpoint and progress to the reader, which knows about neither. */
    private record JobProgress(JobContext context) implements HeapDumpReader.Progress {

        @Override
        public void checkpoint() {
            context.checkpoint();
        }

        @Override
        public void report(double fraction, String detail) {
            context.progressNow((int) Math.round(5 + 60 * Math.clamp(fraction, 0.0, 1.0)),
                    "Reading heap dump", detail);
        }
    }

    /**
     * What the dump was, stored on the artefact.
     *
     * <p>The header, the sizes of the parts of the file that are not the heap, and the census. Note
     * what is absent: no type names in bulk, no field values, no object identifiers. A heap dump's
     * contents are the application's own data, and the one place RepoPilot repeats any of it is a
     * type row's own name, next to the number derived from it.
     */
    private String provenanceJson(HeapDumpReport report, int measurementCount) {
        HeapDumpReport.Provenance provenance = report.provenance();
        HeapDumpReport.Census census = report.census();

        ObjectNode node = json.createObjectNode();
        ObjectNode environment = node.putObject("environment");
        putIfPresent(environment, "formatVersion", provenance.formatVersion());
        environment.put("identifierSize", provenance.identifierSize());
        environment.put("identifierSizeBasis", "the width in bytes of an object identifier in this file, "
                + "from the HPROF header. It is not the width of a reference inside an object: a "
                + "64-bit dump with compressed references still writes 8-byte identifiers.");

        ObjectNode dump = node.putObject("dump");
        if (provenance.takenAt() != null) {
            dump.put("takenAt", provenance.takenAt().toString());
        }
        putIfPresent(dump, "takenAtBasis", provenance.takenAtBasis());
        dump.put("fileBytes", provenance.fileBytes());
        putIfPresent(dump, "fileBytesBasis", provenance.fileBytesBasis());
        dump.put("strings", provenance.strings());
        dump.put("jvmName", "not recorded: a heap dump's header carries the format version, the "
                + "identifier width and a timestamp, and nothing about the JVM that wrote it");

        ObjectNode heap = node.putObject("heap");
        heap.put("objects", report.objects());
        heap.put("objectBytes", report.objectBytes());
        heap.put("instances", census.instances());
        heap.put("instanceBytes", census.instanceBytes());
        heap.put("arrays", report.arrays());
        heap.put("arrayBytes", census.objectArrayBytes() + census.primitiveArrayBytes());
        heap.put("objectArrays", census.objectArrays());
        heap.put("primitiveArrays", census.primitiveArrays());
        heap.put("types", census.namedTypes());
        heap.put("classesLoaded", provenance.classesLoaded());
        heap.put("classesDumped", provenance.classesDumped());
        heap.put("roots", census.roots());
        if (census.unnamedObjects() > 0) {
            heap.put("objectsWithoutAType", census.unnamedObjects());
        }

        ObjectNode produced = node.putObject("produced");
        produced.put("measurements", measurementCount);
        produced.put("findings", 0);
        produced.put("thresholdsApplied", false);
        produced.put("retainedSizesComputed", false);

        node.put("note", HeapDumpMeasurementMapper.SNAPSHOT + " " + HeapDumpMeasurementMapper.BYTES_BASIS
                + " " + HeapDumpMeasurementMapper.NOT_RETAINED);
        return node.toString();
    }

    private String summarise(HeapDumpReport report, int measurementCount) {
        HeapDumpReport.Provenance provenance = report.provenance();
        HeapDumpReport.Census census = report.census();

        StringBuilder summary = new StringBuilder();
        summary.append(measurementCount).append(" measurement(s) from a heap dump: ")
                .append(report.objects()).append(" object(s) holding ")
                .append(report.objectBytes()).append(" recorded byte(s) of object data (")
                .append(census.instances()).append(" instance(s), ").append(report.arrays())
                .append(" array(s)) across ").append(census.namedTypes()).append(" type(s)")
                .append("; the dump holds only the objects that were still reachable, because taking a ")
                .append("dump collects first");

        HeapDumpReport.TypeCensus largest = report.largestType();
        if (largest != null) {
            summary.append("; the largest type is ").append(largest.name()).append(" with ")
                    .append(largest.bytes()).append(" byte(s) over ").append(largest.objects())
                    .append(" object(s)");
        }
        HeapDumpReport.TypeCensus largestObject = report.largestObjectHoldingType();
        if (largestObject != null) {
            summary.append("; the largest single object is ").append(largestObject.largestBytes())
                    .append(" byte(s) of ").append(largestObject.name());
        }
        if (census.unnamedObjects() > 0) {
            summary.append("; ").append(census.unnamedObjects())
                    .append(" object(s) could not be attributed to a type");
        }

        if (provenance.takenAt() != null) {
            summary.append("; the header records it was taken at ").append(provenance.takenAt());
        } else {
            summary.append("; the header's timestamp is not a usable instant");
        }
        summary.append(" — one instant, recorded bytes only (headers and class metadata excluded), "
                + "no retained size computed, no threshold applied and no severity assigned");
        return summary.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
