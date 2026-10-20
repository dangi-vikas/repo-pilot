package dev.repopilot.runtime.benchmark;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a parsed benchmark result into measurements, without measuring anything itself.
 *
 * <h2>The one thing this mapper does not do</h2>
 * It does not reduce. Every number in a benchmark result was computed by the harness that ran the
 * benchmark, on the machine that ran it, and RepoPilot's part is to repeat it faithfully — which is
 * why every row is {@link MeasurementAggregation#AS_REPORTED} and why the run says so. A score
 * relabelled {@code MEAN} would claim RepoPilot had averaged something it never saw; a quoted
 * percentile relabelled {@code MAX} would be simply false. The reduction a reader normally states is,
 * for this file format, a statement about someone else's arithmetic.
 *
 * <h2>What is checked, and what is only repeated</h2>
 * Where a JMH result records its own per-iteration values, the stated score can be held against them,
 * and it is: in throughput and average-time modes the score is the mean of those values, and each
 * score row says whether that held. Where the file records a histogram instead — sample mode — there
 * is nothing to check against, and the row says that instead. The difference is not cosmetic: one
 * score has been confirmed against the file it came from and the other has only been repeated.
 *
 * <h2>No thresholds, no severity, and no comparisons</h2>
 * A benchmark score is a fact about a run. Whether it is good, whether it regressed, and whether it is
 * fast enough are all answers that need a policy or a baseline, and RepoPilot has neither: it holds
 * one file, from one machine, at one moment. So no row is a trend, nothing here is compared with
 * anything, and every score row carries the machine caveat rather than leaving it to a footnote.
 *
 * <h2>What the keys mean</h2>
 * A key is {@code benchmark.<mode>.<leaf>}, and the mode is in the key rather than in the scope
 * because it decides the unit: the same method measured for throughput and for average time produces
 * {@code ops/us} and {@code us/op}, so a shared key would put two numbers with two meanings under one
 * name. JMH's own tokens are mapped to readable segments ({@code thrpt} to {@code throughput}) and the
 * file's token travels unchanged in the row's {@code mode} attribute, so nothing is lost in the
 * translation.
 */
public final class BenchmarkMeasurementMapper {

    /** Every key this mapper produces starts here, so a reader can filter one namespace. */
    public static final String NAMESPACE = "benchmark.";

    /** What a quoted value is, on every row that carries one. */
    public static final String QUOTED_BASIS = "quoted from the benchmark result file: the harness that ran "
            + "the benchmark computed this number, and RepoPilot's only part is to repeat it accurately. No "
            + "threshold was applied and no severity was assigned.";

    /** Why a score could not be checked against the file it came from. */
    public static final String NOT_CHECKABLE = "not checkable: this mode's result file records a histogram "
            + "rather than per-iteration values, so the stated score is the file's own assertion and nothing "
            + "beside it confirms it.";

    /** Who the figures describe, on every score row. */
    public static final String OTHER_MACHINE = "these figures describe the machine, the JVM and the "
            + "configuration the benchmark ran with, which need not be the machine RepoPilot is running on.";

    /** Why no row is a comparison. */
    public static final String NO_BASELINE = "one run of one benchmark on one machine at one moment: RepoPilot "
            + "holds no baseline for this repository and no previous run to compare against, so nothing here "
            + "says that anything became faster or slower than anything else.";

    /** Why the JVM arguments are counted but not reproduced. */
    public static final String JVM_ARGS_BASIS = "the number of JVM arguments the harness recorded with the "
            + "result. The arguments themselves are not stored: a command line is where credentials end up — "
            + "a system property holding a token, a connection string — and JMH copies its own whether or not "
            + "anyone meant to publish it. This is the same rule the JFR reader applies to a recording's "
            + "command-line events, applied to the second file format that carries one.";

    /** What an iteration count is a count of. */
    public static final String ITERATIONS_BASIS = "the per-iteration values the file records, counted across "
            + "every fork it reports. It is what the file holds rather than what the benchmark was configured "
            + "to run, and the two differ whenever a fork was dropped or a value was reported as no number.";

    private BenchmarkMeasurementMapper() {
    }

    /** Every measurement one result file yields, in a fixed order: the file, then each entry. */
    public static List<MeasurementDraft> toMeasurements(BenchmarkResultReport report) {
        List<MeasurementDraft> drafts = new ArrayList<>();
        file(drafts, report.file());
        for (BenchmarkResultReport.Entry entry : report.entries()) {
            entry(drafts, entry);
        }
        return drafts;
    }

    /**
     * The file-level rows.
     *
     * <p>These describe the document rather than the benchmark, and they are rows rather than
     * provenance because two of them bound what the per-benchmark rows can be trusted to say: how many
     * entries state no error estimate, and how many state no per-iteration values. A reader who sees
     * thirty scores and never learns that half of them could not be checked has been told less than
     * the file knows.
     */
    private static void file(List<MeasurementDraft> drafts, BenchmarkResultReport.FileFacts file) {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("basis", "the number of result entries read from the file: JMH writes one per benchmark "
                + "method, per mode and per parameter combination");
        entries.put("note", NO_BASELINE);
        if (file.truncated()) {
            entries.put("truncated", "reading stopped at RepoPilot's entry cap, so the counts below describe "
                    + "the part of the file that was read and not the whole of it");
        }
        drafts.add(count(NAMESPACE + "file.entries", file.entries(), entries));
        drafts.add(count(NAMESPACE + "file.modes", file.modes().size(), Map.of(
                "basis", "distinct JMH modes in the file: " + (file.modes().isEmpty()
                        ? "none stated" : String.join(", ", file.modes())))));
        drafts.add(count(NAMESPACE + "file.parameters", file.parameterNames().size(), Map.of(
                "basis", file.parameterNames().isEmpty()
                        ? "the file declares no benchmark parameters"
                        : "distinct parameter names the file declares: "
                                + String.join(", ", file.parameterNames()))));
        drafts.add(count(NAMESPACE + "file.jvmArguments", file.jvmArgCount(),
                Map.of("basis", JVM_ARGS_BASIS)));
        if (file.entriesWithoutErrorEstimate() > 0) {
            drafts.add(count(NAMESPACE + "file.entries.withoutErrorEstimate", file.entriesWithoutErrorEstimate(),
                    Map.of("basis", "entries for which the file states no error estimate. JMH writes none when "
                            + "it has nothing to compare against — a single fork cannot produce one — and "
                            + "RepoPilot does not infer a number where the file states none.")));
        }
        if (file.entriesWithoutRawData() > 0) {
            drafts.add(count(NAMESPACE + "file.entries.withoutIterations", file.entriesWithoutRawData(),
                    Map.of("basis", "entries for which the file records no per-iteration values, so their "
                            + "stated score could not be checked against the numbers it came from: " + NOT_CHECKABLE)));
        }
    }

    /** One result entry: what it states, and the checks that could be made on it. */
    private static void entry(List<MeasurementDraft> drafts, BenchmarkResultReport.Entry entry) {
        String mode = modeKey(entry.mode());
        String scope = entry.scope();
        BenchmarkResultReport.Metric primary = entry.primary();

        if (primary.score() != null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("mode", entry.mode());
            attributes.put("parameters", entry.parameters().isEmpty()
                    ? "none declared"
                    : entry.parameters().entrySet().stream()
                            .map(parameter -> parameter.getKey() + "=" + parameter.getValue())
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("none declared"));
            attributes.put("basis", QUOTED_BASIS);
            attributes.put("check", check(primary));
            attributes.put("errorEstimate", primary.errorReported()
                    ? "the file states a standard error for this score, stored beside it"
                    : "the file states no error estimate for this score, and RepoPilot does not infer one");
            attributes.put("machine", OTHER_MACHINE);
            drafts.add(new MeasurementDraft(NAMESPACE + mode + ".score", MetricKind.GAUGE,
                    MeasurementAggregation.AS_REPORTED, primary.score(), primary.scoreUnit(),
                    primary.recordedIterations(), scope, attributes));
        }

        if (primary.scoreError() != null) {
            drafts.add(new MeasurementDraft(NAMESPACE + mode + ".scoreError", MetricKind.GAUGE,
                    MeasurementAggregation.AS_REPORTED, primary.scoreError(), primary.scoreUnit(),
                    primary.recordedIterations(), scope,
                    Map.of("basis", "the standard error the file states for this score. It is the harness's "
                            + "own spread of its iteration scores, not a measure of how much of it applies to "
                            + "any other machine")));
        }

        if (primary.confidenceReported()) {
            Map<String, Object> attributes = Map.of("basis",
                    "the confidence interval the file states for this score, as the harness computed it");
            drafts.add(new MeasurementDraft(NAMESPACE + mode + ".confidence.lower", MetricKind.GAUGE,
                    MeasurementAggregation.AS_REPORTED, primary.confidenceLower(), primary.scoreUnit(),
                    primary.recordedIterations(), scope, attributes));
            drafts.add(new MeasurementDraft(NAMESPACE + mode + ".confidence.upper", MetricKind.GAUGE,
                    MeasurementAggregation.AS_REPORTED, primary.confidenceUpper(), primary.scoreUnit(),
                    primary.recordedIterations(), scope, attributes));
        }

        primary.percentiles().forEach((level, value) -> drafts.add(
                new MeasurementDraft(NAMESPACE + mode + ".percentiles." + percentileKey(level),
                        MetricKind.GAUGE, MeasurementAggregation.AS_REPORTED, value, primary.scoreUnit(),
                        primary.recordedIterations(), scope,
                        Map.of("percentile", level,
                                "statedAs", "JMH's result file key scorePercentiles[\"" + level + "\"]",
                                "basis", "quoted from the result file as the harness computed it. RepoPilot "
                                        + "computed no percentile: it has no samples of its own here, and the "
                                        + "level is the file's own key rather than one RepoPilot chose."))));

        if (primary.recordedIterations() > 0) {
            drafts.add(count(NAMESPACE + mode + ".iterations", primary.recordedIterations(), scope,
                    Map.of("basis", ITERATIONS_BASIS,
                            "forks", String.valueOf(primary.rawData().size()))));
        }
        if (primary.histogramBuckets() > 0) {
            drafts.add(count(NAMESPACE + mode + ".histogram.buckets", primary.histogramBuckets(), scope,
                    Map.of("histograms", String.valueOf(primary.histogramCount()),
                            "basis", "the buckets of the raw-sample histograms the file records — one "
                                    + "histogram per measurement iteration — counted, not ingested. A bucket's "
                                    + "width belongs to the harness's histogram rather than to this reader, so "
                                    + "this says how many there were and not what shape they made")));
        }

        declared(drafts, entry, mode, scope);

        if (entry.secondaryCount() > 0) {
            long duplicates = entry.secondary().stream()
                    .filter(metric -> BenchmarkReader.isPercentileMarker(metric.name()))
                    .count();
            drafts.add(count(NAMESPACE + mode + ".secondaryMetrics", entry.secondaryCount(), scope,
                    Map.of("basis", duplicates == entry.secondaryCount()
                            ? "secondary metrics the file states for this entry. All of them repeat the "
                                    + "percentiles already recorded from its primary metric, so they are "
                                    + "counted here and not ingested twice under a second key"
                            : "secondary metrics the file states for this entry, of which " + duplicates
                                    + " repeat the percentiles already recorded from its primary metric and "
                                    + "are counted rather than ingested twice")));
        }
    }

    /** The counts the harness was configured with, which are part of how a score must be read. */
    private static void declared(List<MeasurementDraft> drafts, BenchmarkResultReport.Entry entry,
                                 String mode, String scope) {
        Map<String, Object> basis = Map.of("basis", "as the result file states the benchmark's configuration");
        if (entry.forks() != null) {
            drafts.add(count(NAMESPACE + mode + ".forks", entry.forks(), scope, basis));
        }
        if (entry.threads() != null) {
            drafts.add(count(NAMESPACE + mode + ".threads", entry.threads(), scope, basis));
        }
        if (entry.warmupIterations() != null) {
            drafts.add(count(NAMESPACE + mode + ".warmup.iterations", entry.warmupIterations(), scope, basis));
        }
        if (entry.measurementIterations() != null) {
            drafts.add(count(NAMESPACE + mode + ".measurement.iterations", entry.measurementIterations(),
                    scope, basis));
        }
    }

    /**
     * Whether the stated score is the mean of the values the file records.
     *
     * <p>This is the only arithmetic in this mapper, and it is a check rather than a reduction: the
     * result is a sentence about the file, and the number stored is still the file's. A mismatch is
     * reported rather than corrected, because a reader that silently replaced a stated score with its
     * own mean would have stopped being a reader.
     */
    private static String check(BenchmarkResultReport.Metric metric) {
        Optional<Boolean> matches = metric.scoreMatchesRecordedIterations();
        if (matches.isEmpty()) {
            return NOT_CHECKABLE;
        }
        int iterations = metric.recordedIterations();
        int forks = metric.rawData().size();
        String values = iterations + " per-iteration value(s) the file records across " + forks + " fork(s)";
        if (matches.get()) {
            return "checked against the file it came from: the stated score is the mean of the " + values
                    + ", which is what a JMH throughput or average-time score is";
        }
        return "the " + values + " average to " + metric.meanOfRecordedIterations().orElse(Double.NaN)
                + ", which is not the stated score. RepoPilot reports the score the file states and does not "
                + "substitute its own arithmetic for it";
    }

    /**
     * JMH's mode token as a readable key segment.
     *
     * <p>The file's own token is kept in the {@code mode} attribute of every row, so this translation
     * loses nothing: a reader who needs to know that JMH called it {@code thrpt} can still see that.
     */
    private static String modeKey(String mode) {
        return switch (mode) {
            case "thrpt" -> "throughput";
            case "avgt" -> "averageTime";
            case "sample" -> "sampleTime";
            case "ss" -> "singleShotTime";
            default -> mode.replaceAll("[^A-Za-z0-9]", "_");
        };
    }

    /** A percentile's key segment. The level keeps its meaning; its decimal point does not. */
    private static String percentileKey(String level) {
        return level.replace('.', '_');
    }

    private static MeasurementDraft count(String key, long value, Map<String, Object> attributes) {
        return count(key, value, null, attributes);
    }

    private static MeasurementDraft count(String key, long value, String scope, Map<String, Object> attributes) {
        return new MeasurementDraft(key, MetricKind.COUNTER, MeasurementAggregation.COUNT, value, "count",
                (int) Math.min(value, Integer.MAX_VALUE), scope, attributes);
    }
}
