package dev.repopilot.runtime.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code BENCHMARK_IMPORT} job handler: reads one uploaded benchmark result and stores what it
 * states.
 *
 * <p>Measurements, not findings, and for this reader even more so than the others. A JFR recording or
 * a dump is something RepoPilot observed; a benchmark result is something an operator's own harness
 * observed, on hardware RepoPilot has never seen. The handler therefore treats the file as a witness
 * rather than as data to be improved: every number is stored as the file states it, the arithmetic
 * that produced it is attributed to the harness, and the run's summary says out loud what a single
 * file cannot support — that nothing here was compared with anything.
 *
 * <p>What is deliberately absent from the provenance: the JVM's command line and the path to the JVM
 * binary. See {@link BenchmarkReader} for why, and what is kept instead.
 */
@Component
public class BenchmarkImportJobHandler extends AbstractRuntimeAnalysisHandler {

    /**
     * Hard cap on rows written by one run.
     *
     * <p>Higher than the per-dump readers' cap because a result file is a table rather than a profile:
     * one entry per benchmark method per mode per parameter combination is normal, and a suite of a
     * hundred benchmarks legitimately produces a few thousand rows. It is still a cap, because the file
     * is untrusted input and the entry count alone is bounded only by the upload limit.
     */
    private static final int MAX_MEASUREMENTS = 20_000;

    private final ObjectMapper json;

    public BenchmarkImportJobHandler(RuntimeArtifactRepository artifacts,
                                     RuntimeMeasurementRepository measurements,
                                     AnalysisRunRepository runs,
                                     ArtifactStore store,
                                     ObjectMapper json) {
        super(artifacts, measurements, runs, store, json);
        this.json = json;
    }

    @Override
    protected RuntimeArtifactType artifactType() {
        return RuntimeArtifactType.BENCHMARK_RESULT;
    }

    @Override
    protected AnalysisRunType runType() {
        return AnalysisRunType.BENCHMARK_IMPORT;
    }

    @Override
    protected String readerVersion() {
        return "benchmark-reader=1.0.0";
    }

    @Override
    protected String subject() {
        return "a JMH benchmark result file";
    }

    @Override
    protected AnalysisOutcome analyse(Path file, RuntimeArtifact artifact, JobContext context)
            throws IOException {
        context.progressNow(10, "Reading result file", "Parsing " + artifact.getOriginalFilename());

        // Defence in depth rather than the primary check: the upload path refuses a file that is not the
        // JSON array JMH writes, so reaching this with a CSV means the bytes changed under the artefact
        // or the check was bypassed. Either way the refusal is the same, and it names the shape.
        BenchmarkFormat format = BenchmarkReader.detect(file);
        if (!format.readable()) {
            throw new IOException(BenchmarkFormat.explain(format));
        }

        BenchmarkResultReport report = BenchmarkReader.read(file, json, context::checkpoint);
        context.progressNow(60, "Deriving measurements", report.entries().size() + " result entries");

        List<MeasurementDraft> drafts = BenchmarkMeasurementMapper.toMeasurements(report);
        boolean truncated = false;
        if (drafts.size() > MAX_MEASUREMENTS) {
            drafts = drafts.subList(0, MAX_MEASUREMENTS);
            truncated = true;
        }

        context.progressNow(80, "Storing measurements", drafts.size() + " measurement(s)");
        return new AnalysisOutcome(drafts, provenanceJson(report, drafts.size(), truncated),
                summarise(report, drafts.size(), truncated));
    }

    /**
     * What the file was, stored on the artefact.
     *
     * <p>The JVM's name and build string are the file's own ({@code vmName}, {@code vmVersion}) because
     * the file is the only witness to which JVM ran the benchmark; that is also why no host, no
     * processor and no memory figure appears here — a JMH result records none of them, and inventing a
     * row for a machine the file says nothing about would be worse than the gap.
     */
    private String provenanceJson(BenchmarkResultReport report, int measurementCount, boolean truncated) {
        BenchmarkResultReport.FileFacts file = report.file();

        ObjectNode node = json.createObjectNode();
        ObjectNode environment = node.putObject("environment");
        putIfPresent(environment, "jmhVersion", file.jmhVersion());
        putIfPresent(environment, "jvmName", file.vmName());
        putIfPresent(environment, "jvmVersion", file.vmVersion());
        putIfPresent(environment, "jdkVersion", file.jdkVersion());

        ObjectNode benchmark = node.putObject("benchmark");
        benchmark.put("entries", file.entries());
        benchmark.put("truncated", truncated || file.truncated());
        benchmark.put("jvmArgumentsRecorded", file.jvmArgCount());
        benchmark.put("jvmArgumentsBasis", BenchmarkMeasurementMapper.JVM_ARGS_BASIS);
        benchmark.set("modes", array(file.modes()));
        benchmark.set("parameters", array(file.parameterNames()));
        benchmark.put("entriesWithoutIterations", file.entriesWithoutRawData());
        benchmark.put("entriesWithoutErrorEstimate", file.entriesWithoutErrorEstimate());
        if (!file.warmupTimes().isEmpty()) {
            benchmark.set("warmupTimes", array(file.warmupTimes()));
        }
        if (!file.measurementTimes().isEmpty()) {
            benchmark.set("measurementTimes", array(file.measurementTimes()));
        }

        ObjectNode produced = node.putObject("produced");
        produced.put("measurements", measurementCount);
        produced.put("findings", 0);
        produced.put("thresholdsApplied", false);
        produced.put("comparisonsMade", false);
        node.put("note", "A benchmark result is a statement by the harness that produced it, on the machine "
                + "it ran on. RepoPilot repeated those numbers and checked each score against the "
                + "per-iteration values the file itself records where it records them; it applied no "
                + "threshold, assigned no severity, and compared nothing with anything, because one file "
                + "holds no baseline. " + BenchmarkMeasurementMapper.NO_BASELINE);
        return node.toString();
    }

    private String summarise(BenchmarkResultReport report, int measurementCount, boolean truncated) {
        BenchmarkResultReport.FileFacts file = report.file();
        StringBuilder summary = new StringBuilder();
        summary.append(measurementCount).append(" measurement(s) from ").append(file.entries())
                .append(" benchmark result entr").append(file.entries() == 1 ? "y" : "ies").append(" (")
                .append(file.jmhVersion() == null ? "JMH version not stated" : "JMH " + file.jmhVersion())
                .append(", ")
                .append(describeVm(file))
                .append(")");

        if (!file.modes().isEmpty()) {
            summary.append(": ").append(file.modes().size()).append(" mode(s) — ")
                    .append(describeByMode(report));
        }
        if (!file.parameterNames().isEmpty()) {
            summary.append(", parameterised by ").append(String.join(", ", file.parameterNames()));
        }

        if (file.entriesWithoutRawData() > 0) {
            summary.append("; ").append(file.entriesWithoutRawData()).append(" of ").append(file.entries())
                    .append(" record no per-iteration values, so those scores stand as the file's own "
                            + "statement and could not be checked against the numbers they came from");
        } else if (file.anyRawDataPresent()) {
            summary.append("; every score was checked against the per-iteration values the file records and "
                    + "matched them, or no number was stated to check");
        }
        if (file.entriesWithoutErrorEstimate() > 0) {
            summary.append("; ").append(file.entriesWithoutErrorEstimate())
                    .append(" state no error estimate, which is what a single fork gives a harness to work with");
        }
        if (truncated || file.truncated()) {
            summary.append("; reading stopped at RepoPilot's cap, so these counts describe part of the file");
        }

        summary.append(" — no threshold applied, no severity assigned, and nothing compared with anything: "
                + "one file describes one machine at one moment");
        return summary.toString();
    }

    /** How many entries each mode contributed, in the order the modes appear in the file. */
    private String describeByMode(BenchmarkResultReport report) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BenchmarkResultReport.Entry entry : report.entries()) {
            counts.merge(entry.mode(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(mode -> mode.getValue() + " in " + label(mode.getKey()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String label(String mode) {
        return switch (mode) {
            case "thrpt" -> "throughput mode";
            case "avgt" -> "average-time mode";
            case "sample" -> "sample mode";
            case "ss" -> "single-shot mode";
            default -> "mode " + mode;
        };
    }

    private static String describeVm(BenchmarkResultReport.FileFacts file) {
        if (file.vmName() == null) {
            return "the result file names no JVM";
        }
        return file.vmVersion() == null ? file.vmName() : file.vmName() + " " + file.vmVersion();
    }

    private ArrayNode array(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
