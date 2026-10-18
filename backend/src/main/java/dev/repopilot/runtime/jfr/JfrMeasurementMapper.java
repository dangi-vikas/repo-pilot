package dev.repopilot.runtime.jfr;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link JfrRecordingReport} into the measurements RepoPilot stores.
 *
 * <p>A pure function, and the place where every judgement about a recording is recorded.
 * Each measurement is exact over the events the file contained, states its reduction, and
 * carries the caveat that applies to it. Two caveats are load-bearing:
 *
 * <ul>
 *   <li><b>Sampled events count samples, not occurrences.</b> Execution samples and
 *       allocation samples are produced by the JVM's samplers, so "51 execution samples"
 *       is a statement about the recording, not about the program. The metric keys use
 *       {@code samples}, and where a count is used as a denominator for a share, the
 *       denominator is the sample count, never an inferred event count.</li>
 *   <li><b>Blocking and I/O events are threshold-gated.</b> JFR only emits a thread park,
 *       a monitor block, a sleep or a file read once it exceeds a threshold configured on
 *       the recording. Those measurements therefore describe <em>events above the
 *       threshold</em>, and say so in their attributes. The threshold itself is not
 *       reported: the JDK 21 consumer API does not attribute a recording's settings to the
 *       event types they configure, so quoting one would be a guess dressed as metadata.</li>
 * </ul>
 *
 * <p>No thresholds are applied and no severity is assigned. A JFR recording yields
 * measurements; deciding that a number is bad is a judgement about a workload, and RepoPilot
 * makes those only where it can state the policy that produced them.
 */
public final class JfrMeasurementMapper {

    /** How many rows a histogram contributes. The observed count is reported alongside. */
    public static final int TOP_N = 20;

    /** Channels whose events only exist above a recording threshold. */
    private static final Set<String> THRESHOLD_GATED = Set.of(
            JfrReader.CHANNEL_PARK,
            JfrReader.CHANNEL_MONITOR_ENTER,
            JfrReader.CHANNEL_MONITOR_WAIT,
            JfrReader.CHANNEL_SLEEP,
            JfrReader.CHANNEL_FILE_READ,
            JfrReader.CHANNEL_FILE_WRITE,
            JfrReader.CHANNEL_SOCKET_READ,
            JfrReader.CHANNEL_SOCKET_WRITE);

    private JfrMeasurementMapper() {
    }

    public static List<MeasurementDraft> toMeasurements(JfrRecordingReport report) {
        List<MeasurementDraft> out = new ArrayList<>();
        recordingWindow(report, out);
        activity(report, out);
        collections(report, out);
        executionSamples(report, out);
        allocation(report, out);
        exceptions(report, out);
        heap(report, out);
        cpu(report, out);
        return List.copyOf(out);
    }

    private static void recordingWindow(JfrRecordingReport report, List<MeasurementDraft> out) {
        out.add(MeasurementDraft.count("jfr.events.total", report.eventCount(), (int) Math.min(report.eventCount(),
                Integer.MAX_VALUE), null));
        if (report.firstEvent() != null && report.lastEvent() != null) {
            long nanos = Duration.between(report.firstEvent(), report.lastEvent()).toNanos();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("firstEvent", report.firstEvent().toString());
            attributes.put("lastEvent", report.lastEvent().toString());
            attributes.put("basis", "difference between the first and last event timestamps in the file");
            out.add(MeasurementDraft.of("jfr.recording.window", MetricKind.DURATION,
                    MeasurementAggregation.MAX, nanos / 1_000_000.0, "ms", 0, null, attributes));
        }
        if (report.unreadableEvents() > 0) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basis", "events whose declared fields could not be read; excluded from every other figure");
            out.add(MeasurementDraft.of("jfr.events.unreadable", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                    report.unreadableEvents(), "count", (int) report.unreadableEvents(), null, attributes));
        }
        out.add(MeasurementDraft.of("jfr.eventTypes.seen", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                report.eventTypeCount(), "count", report.eventTypeCount(), null,
                Map.of("basis", "event types declared in the file; not all of them occurred")));
    }

    private static void activity(JfrRecordingReport report, List<MeasurementDraft> out) {
        report.activity().forEach((channel, stats) -> {
            if (stats.count() == 0) {
                return;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (THRESHOLD_GATED.contains(channel)) {
                attributes.put("thresholdGated", true);
                attributes.put("note", "JFR emits these events only above the recording's threshold, so this "
                        + "describes events that exceeded it, not every occurrence. The threshold value is not "
                        + "attributable to an event type through the JDK 21 consumer API and is therefore not stated.");
            }
            if (JfrReader.CHANNEL_GC_PAUSE.equals(channel)) {
                attributes.put("note", "Pause time is the JVM's own sumOfPauses per collection: time the "
                        + "application could not run. It is deliberately separate from collection duration, "
                        + "jfr.gc.wall, which includes concurrent work that did not stop the application.");
            }
            out.add(MeasurementDraft.of("jfr." + channel + ".count", MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, stats.count(), "count", (int) stats.count(), null, attributes));
            out.add(MeasurementDraft.of("jfr." + channel + ".total", MetricKind.DURATION,
                    MeasurementAggregation.SUM, stats.totalMillis(), "ms", (int) stats.count(), null, attributes));
            out.add(MeasurementDraft.of("jfr." + channel + ".max", MetricKind.DURATION,
                    MeasurementAggregation.MAX, stats.maxMillis(), "ms", (int) stats.count(), null, attributes));

            Long bytes = report.byteCounters().get(channel);
            if (bytes != null) {
                Map<String, Object> byteAttributes = new LinkedHashMap<>();
                byteAttributes.put("basis", "sum of the byte counts carried by the events, exactly as recorded");
                if (THRESHOLD_GATED.contains(channel)) {
                    byteAttributes.put("thresholdGated", true);
                    byteAttributes.put("note", "Only events above the recording's threshold carry a byte count, so "
                            + "this total covers those events and is not the total the process transferred.");
                }
                out.add(MeasurementDraft.of("jfr." + channel + ".bytes", MetricKind.SIZE,
                        MeasurementAggregation.SUM, bytes, "bytes", (int) stats.count(), null, byteAttributes));
            }
        });
    }

    private static void collections(JfrRecordingReport report, List<MeasurementDraft> out) {
        List<Map.Entry<String, Long>> causes = topN(report.gcCauses(), TOP_N);
        for (Map.Entry<String, Long> cause : causes) {
            out.add(MeasurementDraft.count("jfr.gc.cause", cause.getValue(), cause.getValue().intValue(),
                    cause.getKey()));
        }
        if (!report.gcCauses().isEmpty()) {
            out.add(MeasurementDraft.count("jfr.gc.causes.observed", report.gcCauses().size(),
                    report.gcCauses().size(), null));
        }
    }

    private static void executionSamples(JfrRecordingReport report, List<MeasurementDraft> out) {
        if (report.executionSamples() == 0 && report.nativeSamples() == 0) {
            return;
        }
        Map<String, Object> sampleAttributes = new LinkedHashMap<>();
        sampleAttributes.put("note", "A sample is a snapshot taken by the JVM's sampler. The count is the number of "
                + "samples in the recording, not the number of times the code ran.");
        sampleAttributes.put("sampler", "jdk.ExecutionSample");
        out.add(MeasurementDraft.of("jfr.execution.samples", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                report.executionSamples(), "count", (int) report.executionSamples(), null, sampleAttributes));
        emitSpacing(out, "jfr.execution.sample.interval", report.executionSampleSpacing(), sampleAttributes);
        emitHistogram(out, "jfr.execution.hotFrame", report.hotFrames(), report.executionSamples(),
                "share of execution samples whose innermost frame was this method", null);

        if (report.nativeSamples() > 0) {
            Map<String, Object> nativeAttributes = new LinkedHashMap<>();
            nativeAttributes.put("note", "Samples taken while a thread was in native code, so the innermost frame "
                    + "is not a Java method.");
            nativeAttributes.put("sampler", "jdk.NativeMethodSample");
            out.add(MeasurementDraft.of("jfr.execution.native.samples", MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, report.nativeSamples(), "count",
                    (int) report.nativeSamples(), null, nativeAttributes));
            emitSpacing(out, "jfr.execution.native.interval", report.nativeSampleSpacing(), nativeAttributes);
            emitHistogram(out, "jfr.execution.nativeHotFrame", report.nativeHotFrames(), report.nativeSamples(),
                    "share of native samples whose innermost frame was this method", null);
        }
    }

    private static void emitSpacing(List<MeasurementDraft> out, String key,
                                    JfrRecordingReport.DurationStats spacing, Map<String, Object> base) {
        if (spacing.count() == 0) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(base);
        attributes.put("basis", "mean gap between consecutive samples, measured within this recording");
        out.add(MeasurementDraft.of(key, MetricKind.DURATION, MeasurementAggregation.MEAN,
                spacing.totalNanos() / 1_000_000.0 / spacing.count(), "ms", (int) spacing.count(), null, attributes));
    }

    private static void allocation(JfrRecordingReport report, List<MeasurementDraft> out) {
        JfrRecordingReport.Allocation allocation = report.allocation();
        if (allocation.sampleCount() == 0) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("note", "JFR allocation samples are weighted. The JVM declares the weight as a data "
                + "amount, but it is a sampling weight, not a byte count: in RepoPilot's own measurement, a "
                + "workload that allocated 200 MiB produced samples summing to 76 MiB. RepoPilot therefore "
                + "reports the aggregate as a weight and never presents it as the bytes the process allocated.");
        attributes.put("weightUnit", "relative sample weight");
        out.add(MeasurementDraft.of("jfr.allocation.samples", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                allocation.sampleCount(), "count", (int) allocation.sampleCount(), null, attributes));
        out.add(MeasurementDraft.of("jfr.allocation.weight", MetricKind.GAUGE, MeasurementAggregation.SUM,
                allocation.totalWeight(), "weight", (int) allocation.sampleCount(), null, attributes));

        emitWeightedHistogram(out, allocation.classSamples(), allocation.classWeights(),
                "jfr.allocation.class", "jfr.allocation.class.weight",
                "share of allocation samples whose allocated type was this class", attributes);
        emitWeightedHistogram(out, allocation.siteSamples(), allocation.siteWeights(),
                "jfr.allocation.site", "jfr.allocation.site.weight",
                "share of allocation samples whose allocating frame was this method", attributes);
    }

    private static void exceptions(JfrRecordingReport report, List<MeasurementDraft> out) {
        JfrRecordingReport.Exceptions exceptions = report.exceptions();
        if (exceptions.counterObservations() > 0) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basis", "the JVM's cumulative counter of created Throwables (Exceptions and Errors "
                    + "together), read at its last observation in the recording. The counter runs from JVM "
                    + "start, so this value is a lifetime figure, not a figure for the recording.");
            attributes.put("subject", "counts java.lang.Throwable and everything derived from it");
            out.add(MeasurementDraft.of("jfr.throwables.counter", MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, exceptions.counterLast(), "count",
                    (int) Math.min(exceptions.counterObservations(), Integer.MAX_VALUE), null, attributes));

            if (exceptions.counterObservations() >= 2 && exceptions.counterLast() >= exceptions.counterFirst()) {
                Map<String, Object> deltaAttributes = new LinkedHashMap<>();
                deltaAttributes.put("basis", "difference between the first and last observation of the JVM's "
                        + "cumulative Throwable counter, so this is what was created during the recorded window");
                deltaAttributes.put("observations", exceptions.counterObservations());
                out.add(MeasurementDraft.of("jfr.throwables.created", MetricKind.COUNTER,
                        MeasurementAggregation.COUNT, exceptions.counterLast() - exceptions.counterFirst(),
                        "count", (int) Math.min(exceptions.counterObservations(), Integer.MAX_VALUE), null,
                        deltaAttributes));
            }
        }

        emitThresholdGatedHistogram(out, "jfr.exceptions.thrownClass", exceptions.exceptionsThrown(),
                "thrown java.lang.Exception subclasses that cleared the recording's throw threshold");
        emitThresholdGatedHistogram(out, "jfr.errors.thrownClass", exceptions.errorsThrown(),
                "thrown java.lang.Error subclasses that cleared the recording's throw threshold");
    }

    /**
     * A top-N histogram of threshold-gated events.
     *
     * <p>Separate from {@link #emitHistogram} so that the caveat is impossible to forget: these
     * counts describe throws that exceeded a threshold, not every throw the process performed, and
     * the row says which event it came from.
     */
    private static void emitThresholdGatedHistogram(List<MeasurementDraft> out, String countKey,
                                                    Map<String, Long> histogram, String meaning) {
        if (histogram.isEmpty()) {
            return;
        }
        long total = histogram.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("thresholdGated", true);
        base.put("note", "JFR emits one event per throw only when it clears the recording's threshold, so this "
                + "counts the throws that were recorded, not every throw that happened.");
        out.add(MeasurementDraft.of(countKey + ".observed", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                histogram.size(), "count", histogram.size(), null, base));
        out.add(MeasurementDraft.of(countKey + ".reported", MetricKind.COUNTER, MeasurementAggregation.COUNT,
                Math.min(histogram.size(), TOP_N), "count", Math.min(histogram.size(), TOP_N), null, base));
        for (Map.Entry<String, Long> entry : topN(histogram, TOP_N)) {
            Map<String, Object> attributes = new LinkedHashMap<>(base);
            attributes.put("meaning", meaning);
            if (total > 0) {
                attributes.put("share", round(entry.getValue() / (double) total));
                attributes.put("shareBasis", total + " recorded throws");
            }
            out.add(MeasurementDraft.of(countKey, MetricKind.COUNTER, MeasurementAggregation.COUNT,
                    entry.getValue(), "count", entry.getValue().intValue(), entry.getKey(), attributes));
        }
    }

    private static void heap(JfrRecordingReport report, List<MeasurementDraft> out) {
        emitGaugeMax(out, "jfr.heap.used.beforeGc", report.heap().usedBeforeGc(),
                "heap in use at the JVM's own pre-collection summary points: the observed high-water mark",
                MetricKind.SIZE, "bytes");
        emitGaugeMax(out, "jfr.heap.used.afterGc", report.heap().usedAfterGc(),
                "heap in use immediately after a collection: what the workload retained",
                MetricKind.SIZE, "bytes");
    }

    private static void cpu(JfrRecordingReport report, List<MeasurementDraft> out) {
        emitCpu(out, "jfr.cpu.load.jvmUser", report.cpu().jvmUser(), "CPU used by the JVM's own threads");
        emitCpu(out, "jfr.cpu.load.jvmSystem", report.cpu().jvmSystem(), "CPU used by the JVM in system calls");
        emitCpu(out, "jfr.cpu.load.machineTotal", report.cpu().machineTotal(),
                "CPU used machine-wide, including every other process on the host");
    }

    private static void emitCpu(List<MeasurementDraft> out, String key, JfrRecordingReport.GaugeStats stats,
                                String meaning) {
        if (stats.count() == 0) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("meaning", meaning);
        attributes.put("basis", "jdk.CPULoad ratios scaled to percent; the host figure covers processes "
                + "RepoPilot did not measure");
        out.add(MeasurementDraft.of(key, MetricKind.GAUGE, MeasurementAggregation.MEAN,
                stats.total() / stats.count() * 100.0, "%", (int) stats.count(), null, attributes));
        out.add(MeasurementDraft.of(key + ".max", MetricKind.GAUGE, MeasurementAggregation.MAX,
                stats.max() * 100.0, "%", (int) stats.count(), null, attributes));
    }

    private static void emitGaugeMax(List<MeasurementDraft> out, String key, JfrRecordingReport.GaugeStats stats,
                                     String meaning, MetricKind kind, String unit) {
        if (stats.count() == 0) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("meaning", meaning);
        attributes.put("basis", "maximum of " + stats.count() + " observations the JVM reported");
        out.add(MeasurementDraft.of(key, kind, MeasurementAggregation.MAX, stats.max(), unit,
                (int) stats.count(), null, attributes));
    }

    private static void emitHistogram(List<MeasurementDraft> out, String countKey, Map<String, Long> histogram,
                                      long denominator, String shareMeaning, Map<String, Object> base) {
        if (histogram.isEmpty()) {
            return;
        }
        out.add(MeasurementDraft.count(countKey + ".observed", histogram.size(), histogram.size(), null));
        out.add(MeasurementDraft.count(countKey + ".reported",
                Math.min(histogram.size(), TOP_N), Math.min(histogram.size(), TOP_N), null));
        for (Map.Entry<String, Long> entry : topN(histogram, TOP_N)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (base != null) {
                attributes.putAll(base);
            }
            attributes.put("meaning", shareMeaning);
            if (denominator > 0) {
                attributes.put("share", round(entry.getValue() / (double) denominator));
                attributes.put("shareBasis", denominator + " samples in the recording");
            }
            out.add(MeasurementDraft.of(countKey, MetricKind.COUNTER, MeasurementAggregation.COUNT,
                    entry.getValue(), "count", entry.getValue().intValue(), entry.getKey(), attributes));
        }
    }

    private static void emitWeightedHistogram(List<MeasurementDraft> out, Map<String, Long> counts,
                                              Map<String, Long> weights, String countKey, String weightKey,
                                              String shareMeaning, Map<String, Object> base) {
        if (counts.isEmpty()) {
            return;
        }
        long totalSamples = counts.values().stream().mapToLong(Long::longValue).sum();
        out.add(MeasurementDraft.count(countKey + "s.observed", counts.size(), counts.size(), null));
        out.add(MeasurementDraft.count(countKey + "s.reported", Math.min(counts.size(), TOP_N),
                Math.min(counts.size(), TOP_N), null));
        for (Map.Entry<String, Long> entry : topN(counts, TOP_N)) {
            Map<String, Object> attributes = new LinkedHashMap<>(base);
            attributes.put("meaning", shareMeaning);
            if (totalSamples > 0) {
                attributes.put("share", round(entry.getValue() / (double) totalSamples));
                attributes.put("shareBasis", totalSamples + " allocation samples in the recording");
            }
            out.add(MeasurementDraft.of(countKey, MetricKind.COUNTER, MeasurementAggregation.COUNT,
                    entry.getValue(), "count", entry.getValue().intValue(), entry.getKey(), attributes));
            Long weight = weights.get(entry.getKey());
            if (weight != null) {
                out.add(MeasurementDraft.of(weightKey, MetricKind.GAUGE, MeasurementAggregation.SUM,
                        weight, "weight", entry.getValue().intValue(), entry.getKey(), attributes));
            }
        }
    }

    private static List<Map.Entry<String, Long>> topN(Map<String, Long> histogram, int limit) {
        return histogram.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
