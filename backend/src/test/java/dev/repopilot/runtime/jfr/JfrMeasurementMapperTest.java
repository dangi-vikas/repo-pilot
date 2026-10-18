package dev.repopilot.runtime.jfr;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from a recording to the numbers RepoPilot claims about it.
 *
 * <p>Tested against reports built by hand rather than against a real recording, because these are
 * the claims, not the parsing: which reductions exist, which caveats travel with which number, what
 * happens when a histogram is too long, and what the mapper refuses to say. A recording cannot
 * produce the edge cases on demand (a thousand distinct hot frames, a GC event with no cause), and a
 * claim that is only tested when the JVM happens to produce the input is not tested.
 */
class JfrMeasurementMapperTest {

    @Test
    @DisplayName("states count, total and maximum separately for every channel")
    void reportsEveryReductionOfEveryChannel() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .activity(JfrReader.CHANNEL_GC_PAUSE, 2, millis(100), millis(60))
                // Wall duration is a different quantity from pause time. Both come from the same
                // events, so a mapper that conflated them would be indistinguishable from one that
                // did not unless the numbers differ.
                .activity(JfrReader.CHANNEL_GC_WALL, 2, millis(300), millis(200))
                .build());

        MeasurementDraft count = find(drafts, "jfr.gc.pause.count");
        assertThat(count.kind()).isEqualTo(MetricKind.COUNTER);
        assertThat(count.aggregation()).isEqualTo(MeasurementAggregation.COUNT);
        assertThat(count.value()).isEqualTo(2.0);
        assertThat(count.unit()).isEqualTo("count");
        assertThat(count.sampleCount()).isEqualTo(2);

        MeasurementDraft total = find(drafts, "jfr.gc.pause.total");
        assertThat(total.kind()).isEqualTo(MetricKind.DURATION);
        assertThat(total.aggregation()).isEqualTo(MeasurementAggregation.SUM);
        assertThat(total.value()).isEqualTo(100.0);
        assertThat(total.unit()).isEqualTo("ms");

        MeasurementDraft max = find(drafts, "jfr.gc.pause.max");
        assertThat(max.aggregation()).isEqualTo(MeasurementAggregation.MAX);
        assertThat(max.value()).isEqualTo(60.0);

        assertThat(find(drafts, "jfr.gc.wall.total").value()).isEqualTo(300.0);
        assertThat(find(drafts, "jfr.gc.wall.max").value()).isEqualTo(200.0);
        // The pause figures are explained as pause figures, not left to look like wall clock.
        assertThat(find(drafts, "jfr.gc.pause.max").attributes().get("note").toString())
                .contains("Pause time");
    }

    @Test
    @DisplayName("says when a number counts only the events that passed a threshold")
    void marksThresholdGatedChannels() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .activity(JfrReader.CHANNEL_PARK, 4, millis(80), millis(30))
                .activity(JfrReader.CHANNEL_FILE_READ, 3, millis(12), millis(6))
                .byteCounter(JfrReader.CHANNEL_FILE_READ, 4096)
                .build());

        for (String key : List.of("jfr.park.count", "jfr.park.total", "jfr.park.max",
                "jfr.file.read.count", "jfr.file.read.total", "jfr.file.read.max",
                "jfr.file.read.bytes")) {
            assertThat(find(drafts, key).attributes()).as(key).containsEntry("thresholdGated", true);
        }

        // The byte total is exact over the events that exist, and says so, because the events that
        // did not clear the threshold carried bytes nobody counted.
        MeasurementDraft bytes = find(drafts, "jfr.file.read.bytes");
        assertThat(bytes.kind()).isEqualTo(MetricKind.SIZE);
        assertThat(bytes.aggregation()).isEqualTo(MeasurementAggregation.SUM);
        assertThat(bytes.unit()).isEqualTo("bytes");
        assertThat(bytes.value()).isEqualTo(4096.0);
        assertThat(bytes.attributes().get("note").toString()).contains("not the total the process transfer");

        // Channels that are not threshold-gated must not carry the caveat, or the caveat stops
        // meaning anything.
        assertThat(JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .activity(JfrReader.CHANNEL_GC_PAUSE, 1, millis(5), millis(5))
                .build()).stream()
                .filter(draft -> draft.metricKey().startsWith("jfr.gc.pause"))
                .noneMatch(draft -> draft.attributes().containsKey("thresholdGated"))).isTrue();
    }

    @Test
    @DisplayName("reports allocation weight as weight, never as bytes")
    void neverConvertsSampledWeightIntoBytes() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .allocation(10, 1_500,
                        Map.of("byte[]", 6L, "java.lang.String", 4L),
                        Map.of("byte[]", 900L, "java.lang.String", 600L),
                        Map.of("com.example.Loader.read", 10L),
                        Map.of("com.example.Loader.read", 1_500L))
                .build());

        MeasurementDraft samples = find(drafts, "jfr.allocation.samples");
        assertThat(samples.aggregation()).isEqualTo(MeasurementAggregation.COUNT);
        assertThat(samples.value()).isEqualTo(10.0);

        MeasurementDraft weight = find(drafts, "jfr.allocation.weight");
        assertThat(weight.kind()).isEqualTo(MetricKind.GAUGE);
        assertThat(weight.aggregation()).isEqualTo(MeasurementAggregation.SUM);
        assertThat(weight.unit()).isEqualTo("weight");
        assertThat(weight.value()).isEqualTo(1_500.0);
        assertThat(weight.attributes()).containsEntry("weightUnit", "relative sample weight");
        assertThat(weight.attributes().get("note").toString()).contains("not a byte count");

        MeasurementDraft classRow = find(drafts, "jfr.allocation.class", "byte[]");
        assertThat(classRow.value()).isEqualTo(6.0);
        assertThat(classRow.attributes()).containsEntry("share", 0.6);
        assertThat(classRow.attributes().get("shareBasis").toString()).contains("10 allocation samples");

        // Nothing derived from allocation sampling may claim bytes: the recording never measured
        // them, and a client that saw a byte figure would repeat it.
        assertThat(drafts).filteredOn(draft -> draft.metricKey().startsWith("jfr.allocation"))
                .noneMatch(draft -> "bytes".equals(draft.unit()));
    }

    @Test
    @DisplayName("bounds a long histogram and reports how much it left out")
    void truncatesHistogramsButSaysSo() {
        ReportBuilder builder = new ReportBuilder()
                .executionSamples(100, spacing(99, millis(500), millis(30)));
        for (int i = 0; i < 25; i++) {
            builder.hotFrame(String.format("com.example.Clazz.method%02d", i), 100 - i);
        }

        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(builder.build());

        List<MeasurementDraft> frames = all(drafts, "jfr.execution.hotFrame");
        assertThat(frames).hasSize(JfrMeasurementMapper.TOP_N);
        // The truncation is visible in the output, not only implied by a missing row: a reader who
        // sees 20 frames must be able to tell whether there were 20.
        assertThat(find(drafts, "jfr.execution.hotFrame.observed").value()).isEqualTo(25.0);
        assertThat(find(drafts, "jfr.execution.hotFrame.reported").value())
                .isEqualTo((double) JfrMeasurementMapper.TOP_N);
        // Ranked by frequency, so the most expensive frame is not the one that got dropped.
        assertThat(frames.get(0).scope()).isEqualTo("com.example.Clazz.method00");
        assertThat(frames).extracting(MeasurementDraft::scope).doesNotContain("com.example.Clazz.method24");
    }

    @Test
    @DisplayName("orders equal frequencies by name so the report is stable")
    void ordersTiesDeterministically() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .executionSamples(6, spacing(5, millis(50), millis(20)))
                .hotFrame("com.example.b.Bravo", 3)
                .hotFrame("com.example.a.Alpha", 3)
                .build());

        assertThat(all(drafts, "jfr.execution.hotFrame")).extracting(MeasurementDraft::scope)
                .containsExactly("com.example.a.Alpha", "com.example.b.Bravo");
    }

    @Test
    @DisplayName("scales the JVM's CPU ratios to percent and keeps the host figure separate")
    void scalesCpuRatios() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .cpu(new JfrRecordingReport.CpuGauges(
                        new JfrRecordingReport.GaugeStats(4, 1.0, 0.4),
                        new JfrRecordingReport.GaugeStats(4, 0.2, 0.1),
                        new JfrRecordingReport.GaugeStats(4, 2.4, 0.9)))
                .build());

        MeasurementDraft user = find(drafts, "jfr.cpu.load.jvmUser");
        assertThat(user.unit()).isEqualTo("%");
        assertThat(user.aggregation()).isEqualTo(MeasurementAggregation.MEAN);
        assertThat(user.value()).isEqualTo(25.0);
        assertThat(user.sampleCount()).isEqualTo(4);
        assertThat(find(drafts, "jfr.cpu.load.jvmUser.max").value()).isEqualTo(40.0);
        assertThat(find(drafts, "jfr.cpu.load.machineTotal.max").value()).isEqualTo(90.0);
        // The machine-wide figure includes processes RepoPilot did not measure, and says so.
        assertThat(find(drafts, "jfr.cpu.load.machineTotal").attributes().get("basis").toString())
                .contains("did not measure");
    }

    @Test
    @DisplayName("reports only what an almost empty recording contains")
    void inventsNothingForAnEmptyRecording() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder().build());

        // Two rows, both of them facts about an empty file. Everything else is absent rather than
        // zero, because "we could not observe it" and "we observed zero" are different statements.
        assertThat(drafts).extracting(MeasurementDraft::metricKey)
                .containsExactlyInAnyOrder("jfr.events.total", "jfr.eventTypes.seen");
        assertThat(find(drafts, "jfr.events.total").value()).isZero();
    }

    @Test
    @DisplayName("assigns no severity and applies no threshold anywhere")
    void assignsNoSeverity() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .activity(JfrReader.CHANNEL_GC_PAUSE, 50, millis(6_000), millis(700))
                .executionSamples(200, spacing(199, millis(2_000), millis(40)))
                .hotFrame("com.example.Slow.hot", 90)
                .allocation(20, 3_000, Map.of("byte[]", 20L), Map.of("byte[]", 3_000L),
                        Map.of("com.example.Slow.allocate", 20L), Map.of("com.example.Slow.allocate", 3_000L))
                .exceptions(2, 1_000, 1_005,
                        Map.of("java.lang.IllegalStateException", 5L),
                        Map.of("java.lang.OutOfMemoryError", 1L))
                .heap(new JfrRecordingReport.HeapGauges(
                        new JfrRecordingReport.GaugeStats(3, 300, 150),
                        new JfrRecordingReport.GaugeStats(3, 150, 90)))
                .cpu(new JfrRecordingReport.CpuGauges(
                        new JfrRecordingReport.GaugeStats(4, 1.0, 0.4),
                        new JfrRecordingReport.GaugeStats(4, 0.2, 0.1),
                        new JfrRecordingReport.GaugeStats(4, 2.4, 0.9)))
                .build());

        // A 700 ms pause and a 6 s total are dramatic numbers. They are still not findings: nothing
        // in a recording is bad without a policy saying so, and RepoPilot has not defined one.
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.metricKey()).doesNotContain("severity").doesNotContain("finding");
            assertThat(draft.unit()).isNotBlank();
            assertThat(draft.attributes()).doesNotContainKey("severity");
        });
        assertThat(find(drafts, "jfr.gc.pause.max").value()).isEqualTo(700.0);

        // Every claim that came from observed events carries an explanation of where it came from.
        for (String key : List.of("jfr.gc.pause.max", "jfr.execution.sample.interval", "jfr.execution.hotFrame",
                "jfr.allocation.samples", "jfr.allocation.weight", "jfr.throwables.created",
                "jfr.heap.used.afterGc", "jfr.cpu.load.machineTotal")) {
            assertThat(find(drafts, key).attributes()).as(key).isNotEmpty();
        }
    }

    @Test
    @DisplayName("separates a lifetime counter from what was created during the recording")
    void distinguishesLifetimeFromWindow() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .exceptions(3, 4_000, 4_042, Map.of("java.lang.IllegalStateException", 5L),
                        Map.of())
                .build());

        // The counter counts from JVM start; the file's 42 is the delta over the recorded window.
        MeasurementDraft counter = find(drafts, "jfr.throwables.counter");
        assertThat(counter.value()).isEqualTo(4_042.0);
        assertThat(counter.attributes().get("basis").toString()).contains("lifetime figure");
        MeasurementDraft created = find(drafts, "jfr.throwables.created");
        assertThat(created.value()).isEqualTo(42.0);
        assertThat(created.attributes().get("basis").toString()).contains("recorded window");

        // A single observation cannot yield a difference, so no difference is claimed.
        List<MeasurementDraft> single = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .exceptions(1, 4_000, 4_000, Map.of(), Map.of())
                .build());
        assertThat(single).extracting(MeasurementDraft::metricKey).contains("jfr.throwables.counter");
        assertThat(single).extracting(MeasurementDraft::metricKey).doesNotContain("jfr.throwables.created");

        // A lazy recording tells no story about throws at all.
        assertThat(JfrMeasurementMapper.toMeasurements(new ReportBuilder().build()))
                .extracting(MeasurementDraft::metricKey)
                .doesNotContain("jfr.throwables.counter", "jfr.throwables.created");
    }

    @Test
    @DisplayName("reports thrown errors and thrown exceptions separately")
    void separatesErrorsFromExceptions() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .exceptions(2, 0, 6, Map.of("java.lang.IllegalStateException", 5L, "java.io.IOException", 1L),
                        Map.of("java.lang.OutOfMemoryError", 1L))
                .build());

        assertThat(all(drafts, "jfr.exceptions.thrownClass")).extracting(MeasurementDraft::scope)
                .containsExactly("java.lang.IllegalStateException", "java.io.IOException");
        assertThat(all(drafts, "jfr.errors.thrownClass")).extracting(MeasurementDraft::scope)
                .containsExactly("java.lang.OutOfMemoryError");
        // Both come from threshold-gated events, and both say so.
        assertThat(find(drafts, "jfr.exceptions.thrownClass").attributes()).containsEntry("thresholdGated", true);
        assertThat(find(drafts, "jfr.errors.thrownClass").attributes()).containsEntry("thresholdGated", true);
    }

    @Test
    @DisplayName("describes the recording window and the exact size of the sample set")
    void describesTheWindow() {
        Instant first = Instant.parse("2026-01-01T10:00:00Z");
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .window(first, first.plusMillis(3_500), 1_200, 40)
                .build());

        MeasurementDraft window = find(drafts, "jfr.recording.window");
        assertThat(window.kind()).isEqualTo(MetricKind.DURATION);
        assertThat(window.value()).isEqualTo(3_500.0);
        assertThat(window.attributes().get("basis").toString()).contains("first and last event");
        assertThat(find(drafts, "jfr.events.total").value()).isEqualTo(1_200.0);
        assertThat(find(drafts, "jfr.events.total").sampleCount()).isEqualTo(1_200);
        assertThat(find(drafts, "jfr.eventTypes.seen").value()).isEqualTo(40.0);
        assertThat(find(drafts, "jfr.eventTypes.seen").attributes().get("basis").toString())
                .contains("not all of them occurred");
    }

    @Test
    @DisplayName("counts unreadable events and says they are excluded from the rest")
    void reportsUnreadableEvents() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .unreadable(7)
                .build());

        MeasurementDraft unreadable = find(drafts, "jfr.events.unreadable");
        assertThat(unreadable.value()).isEqualTo(7.0);
        assertThat(unreadable.attributes().get("basis").toString()).contains("excluded from every other figure");
        // Absent rather than zero when nothing was unreadable: a zero row would suggest the reader
        // checks a condition it does not check.
        assertThat(JfrMeasurementMapper.toMeasurements(new ReportBuilder().build()))
                .extracting(MeasurementDraft::metricKey).doesNotContain("jfr.events.unreadable");
    }

    @Test
    @DisplayName("derives the sample interval from the samples, not from a configured period")
    void derivesSampleIntervalFromMeasurement() {
        List<MeasurementDraft> drafts = JfrMeasurementMapper.toMeasurements(new ReportBuilder()
                .executionSamples(51, spacing(50, millis(1_000), millis(45)))
                .build());

        MeasurementDraft interval = find(drafts, "jfr.execution.sample.interval");
        assertThat(interval.kind()).isEqualTo(MetricKind.DURATION);
        assertThat(interval.aggregation()).isEqualTo(MeasurementAggregation.MEAN);
        assertThat(interval.value()).isEqualTo(20.0);
        assertThat(interval.sampleCount()).isEqualTo(50);
        assertThat(interval.attributes().get("basis").toString()).contains("measured within this recording");
        assertThat(interval.attributes().get("note").toString()).contains("not the number of times the code ran");
    }

    // ------------------------------------------------------------------ helpers

    private static long millis(long value) {
        return value * 1_000_000L;
    }

    private static JfrRecordingReport.DurationStats spacing(long count, long total, long max) {
        return new JfrRecordingReport.DurationStats(count, total, max);
    }

    private static MeasurementDraft find(List<MeasurementDraft> drafts, String metricKey) {
        return drafts.stream()
                .filter(draft -> draft.metricKey().equals(metricKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement for " + metricKey));
    }

    private static MeasurementDraft find(List<MeasurementDraft> drafts, String metricKey, String scope) {
        return drafts.stream()
                .filter(draft -> draft.metricKey().equals(metricKey) && scope.equals(draft.scope()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement for " + metricKey + " scope " + scope));
    }

    private static List<MeasurementDraft> all(List<MeasurementDraft> drafts, String metricKey) {
        List<MeasurementDraft> matching = new ArrayList<>(drafts.stream()
                .filter(draft -> draft.metricKey().equals(metricKey))
                .toList());
        return matching;
    }

    /** Builds a report with only the fields a test cares about; everything else is absent. */
    private static final class ReportBuilder {

        private final Map<String, JfrRecordingReport.DurationStats> activity = new LinkedHashMap<>();
        private final Map<String, Long> byteCounters = new LinkedHashMap<>();
        private final Map<String, Long> gcCauses = new LinkedHashMap<>();
        private final Map<String, Long> hotFrames = new LinkedHashMap<>();
        private final Map<String, Long> nativeHotFrames = new LinkedHashMap<>();
        private final Map<String, Long> thrownClasses = new LinkedHashMap<>();
        private final Map<String, Long> allocationClassSamples = new LinkedHashMap<>();
        private final Map<String, Long> allocationClassWeights = new LinkedHashMap<>();
        private final Map<String, Long> allocationSiteSamples = new LinkedHashMap<>();
        private final Map<String, Long> allocationSiteWeights = new LinkedHashMap<>();

        private long eventCount;
        private int eventTypeCount;
        private long unreadableEvents;
        private Instant firstEvent;
        private Instant lastEvent;
        private long executionSamples;
        private JfrRecordingReport.DurationStats executionSpacing = JfrRecordingReport.DurationStats.EMPTY;
        private long nativeSamples;
        private JfrRecordingReport.Allocation allocation = JfrRecordingReport.Allocation.EMPTY;
        private JfrRecordingReport.Exceptions exceptions = JfrRecordingReport.Exceptions.EMPTY;
        private JfrRecordingReport.HeapGauges heap = JfrRecordingReport.HeapGauges.EMPTY;
        private JfrRecordingReport.CpuGauges cpu = JfrRecordingReport.CpuGauges.EMPTY;

        ReportBuilder activity(String channel, long count, long totalNanos, long maxNanos) {
            activity.put(channel, new JfrRecordingReport.DurationStats(count, totalNanos, maxNanos));
            return this;
        }

        ReportBuilder byteCounter(String channel, long bytes) {
            byteCounters.put(channel, bytes);
            return this;
        }

        ReportBuilder window(Instant first, Instant last, long events, int eventTypes) {
            this.firstEvent = first;
            this.lastEvent = last;
            this.eventCount = events;
            this.eventTypeCount = eventTypes;
            return this;
        }

        ReportBuilder unreadable(long count) {
            this.unreadableEvents = count;
            return this;
        }

        ReportBuilder executionSamples(long count, JfrRecordingReport.DurationStats spacing) {
            this.executionSamples = count;
            this.executionSpacing = spacing;
            return this;
        }

        ReportBuilder hotFrame(String method, long count) {
            hotFrames.put(method, count);
            return this;
        }

        ReportBuilder allocation(long samples, long weight, Map<String, Long> classSamples,
                                 Map<String, Long> classWeights, Map<String, Long> siteSamples,
                                 Map<String, Long> siteWeights) {
            this.allocation = new JfrRecordingReport.Allocation(samples, weight, classSamples, classWeights,
                    siteSamples, siteWeights);
            return this;
        }

        ReportBuilder exceptions(long observations, long first, long last, Map<String, Long> thrown,
                                 Map<String, Long> errors) {
            this.exceptions = new JfrRecordingReport.Exceptions(observations, first, last, thrown, errors);
            return this;
        }

        ReportBuilder heap(JfrRecordingReport.HeapGauges gauges) {
            this.heap = gauges;
            return this;
        }

        ReportBuilder cpu(JfrRecordingReport.CpuGauges gauges) {
            this.cpu = gauges;
            return this;
        }

        JfrRecordingReport build() {
            return new JfrRecordingReport(
                    new JfrRecordingReport.Provenance("OpenJDK 64-Bit Server VM", "21.0.5",
                            Instant.parse("2026-01-01T09:59:00Z"), 4242,
                            "Test OS", "Test CPU", 8, 16, "test-recording",
                            Instant.parse("2026-01-01T10:00:00Z"), 1L << 33, 1L << 30),
                    eventCount,
                    eventTypeCount,
                    unreadableEvents,
                    firstEvent,
                    lastEvent,
                    Map.copyOf(activity),
                    Map.copyOf(byteCounters),
                    gcCauses,
                    executionSamples,
                    executionSpacing,
                    Map.copyOf(hotFrames),
                    nativeSamples,
                    JfrRecordingReport.DurationStats.EMPTY,
                    nativeHotFrames,
                    allocation,
                    exceptions,
                    heap,
                    cpu);
        }
    }
}
