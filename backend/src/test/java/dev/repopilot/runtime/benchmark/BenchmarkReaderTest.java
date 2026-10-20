package dev.repopilot.runtime.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The benchmark reader, checked against the two recorded JMH runs in
 * {@code src/test/resources/benchmarks/}.
 *
 * <p>The files are real — see {@code corpus/benchmarks/README.md} for the command, the JDK and the
 * warning that they are a format fixture rather than a performance claim — so the numbers below are the
 * numbers JMH 1.37 actually wrote. That matters for exactly the cases a hand-written fixture would get
 * wrong: {@code "NaN"} as a string, per-fork nesting of {@code rawData}, and sample mode shipping a
 * histogram instead of values.
 */
class BenchmarkReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    @DisplayName("reads every entry of the single-fork run, with the file's own facts")
    void readsEveryEntry() throws Exception {
        BenchmarkResultReport report = read(fixture("jmh-result.json"));
        BenchmarkResultReport.FileFacts file = report.file();

        assertThat(report.entries()).hasSize(14);
        assertThat(file.entries()).isEqualTo(14);
        assertThat(file.truncated()).isFalse();
        assertThat(file.jmhVersion()).isEqualTo("1.37");
        assertThat(file.vmName()).isEqualTo("Java HotSpot(TM) 64-Bit Server VM");
        assertThat(file.vmVersion()).isEqualTo("21.0.5+9-LTS-239");
        assertThat(file.jdkVersion()).isEqualTo("21.0.5");
        assertThat(file.modes()).containsExactly("thrpt", "avgt", "sample");
        assertThat(file.parameterNames()).containsExactly("size");
        assertThat(file.warmupTimes()).containsExactly("500 ms");
        assertThat(file.measurementTimes()).containsExactly("500 ms");
        // The run passed no JVM arguments, so the count is zero — and the count is all that is kept.
        assertThat(file.jvmArgCount()).isZero();
        assertThat(file.anyRawDataPresent()).isTrue();
    }

    @Test
    @DisplayName("states no number where the file states NaN, and counts those entries")
    void nanBecomesAbsentRatherThanZero() throws Exception {
        BenchmarkResultReport report = read(fixture("jmh-result.json"));

        // Entry 0 is throughput, single fork: JMH writes "scoreError" : "NaN" and
        // "scoreConfidence" : [ "NaN", "NaN" ], as strings.
        BenchmarkResultReport.Metric throughput = report.entries().get(0).primary();
        assertThat(throughput.score()).isEqualTo(9.861384583523789);
        assertThat(throughput.scoreError()).isNull();
        assertThat(throughput.confidenceLower()).isNull();
        assertThat(throughput.confidenceUpper()).isNull();
        assertThat(throughput.errorReported()).isFalse();
        assertThat(throughput.confidenceReported()).isFalse();

        // Eight of the fourteen entries are throughput or average-time in one fork, so eight state no
        // error estimate. The six sample-mode entries state one even from a single fork, because their
        // error comes from the sample distribution rather than from comparing forks.
        assertThat(report.file().entriesWithoutErrorEstimate()).isEqualTo(8);
        assertThat(report.file().entriesWithoutRawData()).isEqualTo(6);
    }

    @Test
    @DisplayName("keeps the error a sample-mode entry does state")
    void sampleModeStatesItsOwnError() throws Exception {
        BenchmarkResultReport.Metric sample = read(fixture("jmh-result.json")).entries().get(8).primary();

        assertThat(sample.score()).isEqualTo(0.5465643766965964);
        assertThat(sample.scoreError()).isEqualTo(0.43112064729219296);
        assertThat(sample.confidenceLower()).isEqualTo(0.11544372940440345);
        assertThat(sample.confidenceUpper()).isEqualTo(0.9776850239887893);
        assertThat(sample.unit()).contains("us/op");
        assertThat(sample.errorReported()).isTrue();
    }

    @Test
    @DisplayName("nests raw data per fork rather than counting the outer array")
    void nestsRawDataPerFork() throws Exception {
        BenchmarkResultReport oneFork = read(fixture("jmh-result.json"));
        assertThat(oneFork.entries().get(0).primary().rawData()).hasSize(1);
        assertThat(oneFork.entries().get(0).primary().rawData().get(0)).hasSize(2);
        assertThat(oneFork.entries().get(0).primary().recordedIterations()).isEqualTo(2);

        BenchmarkResultReport twoForks = read(fixture("jmh-result-2-forks.json"));
        BenchmarkResultReport.Metric metric = twoForks.entries().get(0).primary();
        assertThat(metric.rawData()).hasSize(2);
        assertThat(metric.rawData()).allSatisfy(fork -> assertThat(fork).hasSize(3));
        // Six values, not two: the count is over every value the file records, across every fork.
        assertThat(metric.recordedIterations()).isEqualTo(6);
        assertThat(metric.flatRawData()).containsExactly(
                8.80668809932711, 10.961509434867557, 11.859890750157566,
                9.35679178093115, 11.144202228303735, 10.02728891739227);
    }

    @Test
    @DisplayName("the stated score is the mean of the values the file itself records")
    void theScoreIsTheMeanOfItsOwnIterations() throws Exception {
        for (String fixture : List.of("jmh-result.json", "jmh-result-2-forks.json")) {
            BenchmarkResultReport report = read(fixture(fixture));
            assertThat(report.entries())
                    .allSatisfy(entry -> {
                        if (!entry.primary().rawData().isEmpty()) {
                            assertThat(entry.primary().scoreMatchesRecordedIterations())
                                    .as("%s in %s", entry.benchmark(), fixture)
                                    .contains(true);
                        }
                    });
        }

        BenchmarkResultReport twoForks = read(fixture("jmh-result-2-forks.json"));
        assertThat(twoForks.entries().get(0).primary().meanOfRecordedIterations())
                .hasValueCloseTo(10.359395201829896, within(1e-12));
    }

    @Test
    @DisplayName("sample mode records histograms instead of per-iteration values")
    void sampleModeRecordsHistograms() throws Exception {
        BenchmarkResultReport.Metric sample = read(fixture("jmh-result.json")).entries().get(8).primary();

        assertThat(sample.rawData()).isEmpty();
        assertThat(sample.recordedIterations()).isZero();
        assertThat(sample.meanOfRecordedIterations()).isEmpty();
        // Nothing to check the score against, and the reader says so rather than inventing a value.
        assertThat(sample.scoreMatchesRecordedIterations()).isEmpty();
        // Two measurement iterations, so two histograms — of different widths, so the total is 96
        // buckets and not two times the larger one.
        assertThat(sample.histogramCount()).isEqualTo(2);
        assertThat(sample.histogramBuckets()).isEqualTo(96);
    }

    @Test
    @DisplayName("keeps the percentiles under the file's own keys")
    void keepsTheFilesOwnPercentileKeys() throws Exception {
        BenchmarkResultReport.Metric throughput = read(fixture("jmh-result.json")).entries().get(0).primary();

        assertThat(throughput.percentiles())
                .containsOnlyKeys("0.0", "50.0", "90.0", "95.0", "99.0", "99.9", "99.99", "99.999",
                        "99.9999", "100.0");
        assertThat(throughput.percentiles().get("0.0")).isEqualTo(8.264637375601062);
        // Two iteration values, so the median is their mean, and the mean is the stated score. That is a
        // property of this file, not of the reader: the percentiles are quoted, never recomputed.
        assertThat(throughput.percentiles().get("50.0")).isEqualTo(throughput.score());
        assertThat(throughput.percentiles().get("100.0")).isEqualTo(11.458131791446515);
    }

    @Test
    @DisplayName("marks JMH's sample-mode percentile secondaries rather than duplicating them")
    void marksPercentileSecondaries() throws Exception {
        BenchmarkResultReport.Entry sample = read(fixture("jmh-result.json")).entries().get(8);

        assertThat(sample.secondary()).hasSize(8);
        assertThat(sample.secondary()).allSatisfy(metric ->
                assertThat(BenchmarkReader.isPercentileMarker(metric.name())).isTrue());
        assertThat(BenchmarkReader.percentileMarkerLevel(sample.secondary().get(0).name())).isEqualTo("p0.00");
        assertThat(BenchmarkReader.isPercentileMarker("gc.alloc.rate")).isFalse();
    }

    @Test
    @DisplayName("the parameters that distinguish two entries travel with them")
    void keepsParameters() throws Exception {
        BenchmarkResultReport report = read(fixture("jmh-result.json"));

        BenchmarkResultReport.Entry sixteen = report.entries().get(0);
        BenchmarkResultReport.Entry twoFiftySix = report.entries().get(1);
        assertThat(sixteen.benchmark()).isEqualTo("dev.repopilot.fixture.RepoPilotFixtureBenchmarks.mapLookup");
        assertThat(sixteen.parameters()).containsEntry("size", "16");
        assertThat(twoFiftySix.parameters()).containsEntry("size", "256");
        assertThat(sixteen.scope()).endsWith("[size=16]");
        assertThat(twoFiftySix.scope()).endsWith("[size=256]");
        assertThat(sixteen.scope()).isNotEqualTo(twoFiftySix.scope());
    }

    @Test
    @DisplayName("counts the command line without carrying it")
    void countsTheCommandLineWithoutCarryingIt() throws Exception {
        String json = """
                [{
                  "jmhVersion" : "1.37",
                  "benchmark" : "dev.repopilot.fixture.Work.run",
                  "mode" : "thrpt",
                  "threads" : 1,
                  "forks" : 1,
                  "jvm" : "C:\\\\Users\\\\secret-operator\\\\jdk\\\\bin\\\\java.exe",
                  "jvmArgs" : ["-Ddb.password=hunter2", "-Xmx2g"],
                  "primaryMetric" : {"score" : 1.0, "scoreError" : 0.1, "scoreUnit" : "ops/us",
                                     "scoreConfidence" : [0.9, 1.1], "scorePercentiles" : {"99.0" : 1.2},
                                     "rawData" : [[1.0]]}
                }]
                """;
        BenchmarkResultReport report = readBytes(json);

        assertThat(report.file().jvmArgCount()).isEqualTo(2);
        // The count is kept; the values are not, anywhere in the report. If a later change added the
        // arguments to the record, this assertion fails on the record's own toString.
        assertThat(report.toString()).doesNotContain("hunter2").doesNotContain("secret-operator");
    }

    @Test
    @DisplayName("an empty array is a readable file with no entries")
    void readsAnEmptyArray() throws Exception {
        BenchmarkResultReport report = readBytes("[]");

        assertThat(report.isEmpty()).isTrue();
        assertThat(report.file().entries()).isZero();
        assertThat(report.file().modes()).isEmpty();
    }

    @Test
    @DisplayName("refuses a truncation rather than reporting a shorter run")
    void refusesATruncatedDocument() {
        assertThatThrownBy(() -> readBytes("["))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ends in the middle");
    }

    @Test
    @DisplayName("refuses an array entry that is not a result")
    void refusesAnEntryWithNoBenchmarkName() {
        assertThatThrownBy(() -> readBytes("[{\"hello\" : \"world\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no `benchmark` field");
    }

    @Test
    @DisplayName("refuses an entry with no primary metric")
    void refusesAnEntryWithNoPrimaryMetric() {
        assertThatThrownBy(() -> readBytes("[{\"benchmark\" : \"dev.repopilot.Work.run\"}]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no `primaryMetric`");
    }

    @Test
    @DisplayName("refuses a metric with no unit rather than storing a bare number")
    void refusesAMetricWithNoUnit() {
        assertThatThrownBy(() -> readBytes("""
                [{"benchmark" : "dev.repopilot.Work.run", "primaryMetric" : {"score" : 12.0}}]
                """))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no `scoreUnit`");
    }

    @Test
    @DisplayName("refuses a file whose first token is not an array, naming the shape")
    void refusesANonArrayDocument() {
        assertThatThrownBy(() -> readBytes("\"Benchmark\",\"Mode\"\n\"Work.run\",\"thrpt\"\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("-rf json");
    }

    @ParameterizedTest(name = "reads {0}")
    @ValueSource(strings = {"jmh-result.json", "jmh-result-2-forks.json"})
    @DisplayName("every entry carries a mode, a unit and a name")
    void everyEntryIsSelfDescribing(String fixture) throws Exception {
        BenchmarkResultReport report = read(fixture(fixture));

        assertThat(report.entries()).allSatisfy(entry -> {
            assertThat(entry.benchmark()).isNotBlank();
            assertThat(entry.mode()).isIn("thrpt", "avgt", "sample");
            assertThat(entry.primary().unit()).isPresent();
            assertThat(entry.scope()).isNotBlank();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Path fixture(String name) throws Exception {
        return Path.of(BenchmarkReaderTest.class.getResource("/benchmarks/" + name).toURI());
    }

    private static BenchmarkResultReport read(Path file) throws IOException {
        return BenchmarkReader.read(file, JSON, () -> {
        });
    }

    private BenchmarkResultReport readBytes(String json) throws IOException {
        Path file = temp.resolve("result-" + Math.abs(json.hashCode()) + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return read(file);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
