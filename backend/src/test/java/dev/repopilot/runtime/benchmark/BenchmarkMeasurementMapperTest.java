package dev.repopilot.runtime.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from a parsed result file to measurements, checked against the two recorded JMH runs.
 *
 * <p>The two things this suite is really about are the words in a row's attributes and the absence of
 * rows. A benchmark score is quoted, so the row must say who computed it and what was not applied; a
 * score the file states no error for must produce no error row rather than one holding zero; and no row
 * may imply a comparison, because the repository holds one run and no baseline.
 */
class BenchmarkMeasurementMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("every value row is quoted and every count row is counted")
    void everyRowStatesHowItWasObtained() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        assertThat(drafts).hasSize(254);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.metricKey()).startsWith(BenchmarkMeasurementMapper.NAMESPACE);
            assertThat(draft.unit()).isNotBlank();
            assertThat(draft.attributes()).isNotNull();
            if (isQuoted(draft.metricKey())) {
                assertThat(draft.kind()).isEqualTo(MetricKind.GAUGE);
                assertThat(draft.aggregation()).isEqualTo(MeasurementAggregation.AS_REPORTED);
            } else {
                assertThat(draft.kind()).isEqualTo(MetricKind.COUNTER);
                assertThat(draft.aggregation()).isEqualTo(MeasurementAggregation.COUNT);
            }
        });
    }

    @Test
    @DisplayName("the file-level rows count what the file holds")
    void fileLevelRowsCountTheFile() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        assertThat(value(drafts, "benchmark.file.entries")).isEqualTo(14);
        assertThat(value(drafts, "benchmark.file.modes")).isEqualTo(3);
        assertThat(value(drafts, "benchmark.file.parameters")).isEqualTo(1);
        assertThat(value(drafts, "benchmark.file.jvmArguments")).isZero();
        assertThat(value(drafts, "benchmark.file.entries.withoutErrorEstimate")).isEqualTo(8);
        assertThat(value(drafts, "benchmark.file.entries.withoutIterations")).isEqualTo(6);
        // A file-level row describes the document, not a benchmark, so it has no scope.
        assertThat(row(drafts, "benchmark.file.entries")).satisfies(draft -> assertThat(draft.scope()).isNull());
    }

    @Test
    @DisplayName("the command-line count says why the arguments themselves are not stored")
    void theCommandLineCountExplainsItself() throws Exception {
        MeasurementDraft row = row(drafts("jmh-result.json"), "benchmark.file.jvmArguments");

        assertThat(row.attributes()).containsEntry("basis", BenchmarkMeasurementMapper.JVM_ARGS_BASIS);
        assertThat(String.valueOf(row.attributes().get("basis")))
                .contains("credentials")
                .contains("JFR reader");
    }

    @Test
    @DisplayName("a throughput score is quoted in the file's own unit, with the machine caveat")
    void quotesAThroughputScore() throws Exception {
        MeasurementDraft score = scoped(drafts("jmh-result.json"), "benchmark.throughput.score", "size=16");

        assertThat(score.value()).isEqualTo(9.861384583523789);
        assertThat(score.unit()).isEqualTo("ops/us");
        assertThat(score.scope()).contains("mapLookup").contains("[size=16]");
        assertThat(score.attributes()).containsEntry("mode", "thrpt");
        assertThat(score.attributes()).containsEntry("basis", BenchmarkMeasurementMapper.QUOTED_BASIS);
        assertThat(score.attributes()).containsEntry("machine", BenchmarkMeasurementMapper.OTHER_MACHINE);
        assertThat(String.valueOf(score.attributes().get("check")))
                .contains("checked against the file it came from")
                .contains("2 per-iteration value(s)")
                .contains("1 fork(s)");
    }

    @Test
    @DisplayName("an average-time score is a duration per operation, not a rate")
    void quotesAnAverageTimeScore() throws Exception {
        MeasurementDraft score = scoped(drafts("jmh-result.json"), "benchmark.averageTime.score", "size=16");

        assertThat(score.value()).isEqualTo(0.10610511644934341);
        assertThat(score.unit()).isEqualTo("us/op");
        assertThat(score.attributes()).containsEntry("mode", "avgt");
    }

    @Test
    @DisplayName("the mode is in the key, so two modes of one method cannot collide")
    void theModeIsInTheKey() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        assertThat(drafts).noneSatisfy(draft -> assertThat(draft.metricKey()).isEqualTo("benchmark.score"));
        assertThat(drafts.stream().filter(draft -> draft.metricKey().endsWith(".score"))
                .map(MeasurementDraft::metricKey).distinct())
                .containsExactlyInAnyOrder("benchmark.throughput.score", "benchmark.averageTime.score",
                        "benchmark.sampleTime.score");
    }

    @Test
    @DisplayName("a score confirmed across forks says how many values confirmed it")
    void statesTheCheckAcrossForks() throws Exception {
        MeasurementDraft score = scoped(drafts("jmh-result-2-forks.json"), "benchmark.throughput.score", "size=16");

        assertThat(score.value()).isEqualTo(10.359395201829896);
        assertThat(String.valueOf(score.attributes().get("check")))
                .contains("6 per-iteration value(s)")
                .contains("2 fork(s)");
    }

    @Test
    @DisplayName("a score with nothing to check it against says exactly that")
    void saysWhenAScoreCannotBeChecked() throws Exception {
        MeasurementDraft score = scoped(drafts("jmh-result.json"), "benchmark.sampleTime.score", "size=16");

        assertThat(score.attributes()).containsEntry("check", BenchmarkMeasurementMapper.NOT_CHECKABLE);
        assertThat(score.value()).isEqualTo(0.5465643766965964);
    }

    @Test
    @DisplayName("no error or confidence row is written where the file states none")
    void writesNoRowsForNumbersTheFileDidNotState() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        assertThat(drafts.stream()
                .filter(draft -> draft.scope() != null && draft.scope().contains("mapLookup") && draft.scope().contains("size=16"))
                .map(MeasurementDraft::metricKey))
                .noneMatch(key -> key.equals("benchmark.throughput.scoreError")
                        || key.startsWith("benchmark.throughput.confidence."));

        MeasurementDraft score = scoped(drafts, "benchmark.throughput.score", "size=16");
        assertThat(String.valueOf(score.attributes().get("errorEstimate")))
                .contains("states no error estimate")
                .contains("does not infer one");
    }

    @Test
    @DisplayName("an error the file does state is written beside the score, not folded into it")
    void writesTheErrorTheFileStates() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        MeasurementDraft error = scoped(drafts, "benchmark.sampleTime.scoreError", "size=16");
        assertThat(error.value()).isEqualTo(0.43112064729219296);
        assertThat(error.unit()).isEqualTo("us/op");
        assertThat(error.aggregation()).isEqualTo(MeasurementAggregation.AS_REPORTED);

        assertThat(scoped(drafts, "benchmark.sampleTime.confidence.lower", "size=16").value())
                .isEqualTo(0.11544372940440345);
        assertThat(scoped(drafts, "benchmark.sampleTime.confidence.upper", "size=16").value())
                .isEqualTo(0.9776850239887893);
    }

    @Test
    @DisplayName("a percentile keeps its level and loses only the decimal point in the key")
    void keepsPercentileLevels() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        MeasurementDraft median = scoped(drafts, "benchmark.throughput.percentiles.50_0", "size=16");
        assertThat(median.value()).isEqualTo(9.861384583523789);
        assertThat(median.attributes()).containsEntry("percentile", "50.0");
        assertThat(String.valueOf(median.attributes().get("basis")))
                .contains("computed no percentile");

        assertThat(scoped(drafts, "benchmark.sampleTime.percentiles.99_0", "size=16").value()).isEqualTo(0.5);
        // The file's own key, including the four-decimal levels, survives the encoding.
        assertThat(drafts.stream().map(MeasurementDraft::metricKey))
                .contains("benchmark.sampleTime.percentiles.99_9999");
    }

    @Test
    @DisplayName("the recorded value count is told apart from the configured one")
    void separatesRecordedFromDeclaredIterations() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result-2-forks.json");

        MeasurementDraft recorded = scoped(drafts, "benchmark.throughput.iterations", "size=16");
        assertThat(recorded.value()).isEqualTo(6);
        assertThat(recorded.attributes()).containsEntry("forks", "2");
        assertThat(String.valueOf(recorded.attributes().get("basis")))
                .contains("rather than what the benchmark was configured to run");

        assertThat(scoped(drafts, "benchmark.throughput.measurement.iterations", "size=16").value()).isEqualTo(3);
        assertThat(scoped(drafts, "benchmark.throughput.warmup.iterations", "size=16").value()).isEqualTo(1);
        assertThat(scoped(drafts, "benchmark.throughput.forks", "size=16").value()).isEqualTo(2);
        assertThat(scoped(drafts, "benchmark.throughput.threads", "size=16").value()).isEqualTo(1);
    }

    @Test
    @DisplayName("histogram buckets are counted, and the histogram count travels with them")
    void countsHistogramBuckets() throws Exception {
        MeasurementDraft buckets = scoped(drafts("jmh-result.json"), "benchmark.sampleTime.histogram.buckets",
                "size=16");

        assertThat(buckets.value()).isEqualTo(96);
        assertThat(buckets.attributes()).containsEntry("histograms", "2");
        assertThat(String.valueOf(buckets.attributes().get("basis")))
                .contains("counted, not ingested");
    }

    @Test
    @DisplayName("sample-mode percentiles stated twice are counted once and named as duplicates")
    void countsDuplicatePercentileSecondaries() throws Exception {
        MeasurementDraft secondaries = scoped(drafts("jmh-result.json"), "benchmark.sampleTime.secondaryMetrics",
                "size=16");

        assertThat(secondaries.value()).isEqualTo(8);
        assertThat(String.valueOf(secondaries.attributes().get("basis")))
                .contains("repeat the percentiles already recorded")
                .contains("not ingested twice");
    }

    @Test
    @DisplayName("nothing mapped here is a comparison, a threshold or a verdict")
    void nothingIsAComparison() throws Exception {
        List<MeasurementDraft> drafts = drafts("jmh-result.json");

        assertThat(drafts).noneSatisfy(draft -> assertThat(draft.metricKey().toLowerCase())
                .contains("baseline").contains("delta").contains("regression").contains("threshold"));
        assertThat(String.valueOf(row(drafts, "benchmark.file.entries").attributes().get("note")))
                .isEqualTo(BenchmarkMeasurementMapper.NO_BASELINE);
        assertThat(drafts.stream().filter(draft -> draft.metricKey().endsWith(".score")))
                .allSatisfy(draft -> assertThat(draft.attributes()).containsKey("machine"));
    }

    @Test
    @DisplayName("two parameter values of one method stay two distinguishable rows")
    void separatesParameterValues() throws Exception {
        List<MeasurementDraft> scores = drafts("jmh-result.json").stream()
                .filter(draft -> draft.metricKey().equals("benchmark.throughput.score"))
                .filter(draft -> draft.scope().contains("mapLookup"))
                .toList();

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0).scope()).isNotEqualTo(scores.get(1).scope());
        assertThat(scores).allSatisfy(draft -> assertThat(draft.attributes()).containsKey("parameters"));
        assertThat(String.valueOf(scores.get(0).attributes().get("parameters"))).isEqualTo("size=16");
        assertThat(String.valueOf(scores.get(1).attributes().get("parameters"))).isEqualTo("size=256");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isQuoted(String key) {
        return key.endsWith(".score")
                || key.endsWith(".scoreError")
                || key.contains(".confidence.")
                || key.contains(".percentiles.");
    }

    private static MeasurementDraft row(List<MeasurementDraft> drafts, String key) {
        return drafts.stream().filter(draft -> draft.metricKey().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + key));
    }

    private static double value(List<MeasurementDraft> drafts, String key) {
        return row(drafts, key).value();
    }

    private static MeasurementDraft scoped(List<MeasurementDraft> drafts, String key, String scope) {
        Optional<MeasurementDraft> match = drafts.stream()
                .filter(draft -> draft.metricKey().equals(key))
                .filter(draft -> draft.scope() != null && draft.scope().contains(scope))
                .findFirst();
        return match.orElseThrow(() -> new AssertionError("no row for " + key + " in scope containing " + scope));
    }

    private static List<MeasurementDraft> drafts(String fixture) throws Exception {
        Path file = Path.of(BenchmarkMeasurementMapperTest.class.getResource("/benchmarks/" + fixture).toURI());
        return BenchmarkMeasurementMapper.toMeasurements(BenchmarkReader.read(file, JSON, () -> {
        }));
    }
}
