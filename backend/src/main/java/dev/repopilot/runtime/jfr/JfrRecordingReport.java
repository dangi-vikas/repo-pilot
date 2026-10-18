package dev.repopilot.runtime.jfr;

import java.time.Instant;
import java.util.Map;

/**
 * What a JFR recording contains, read out of the file and nothing else.
 *
 * <p>This is the boundary between "parsing a recording" and "deciding what to report". The
 * reader fills it in by walking the events once; {@link JfrMeasurementMapper} turns it into
 * measurements. Keeping the two apart means the mapping &mdash; the part that decides what
 * RepoPilot claims about a recording &mdash; is a pure function that can be tested with a
 * hand-built report, and the part that touches the file can be tested against a recording
 * the test itself creates.
 *
 * <p>Every field here is something the file stated. Nothing is inferred from a name, and
 * nothing is assumed to be present: a JFR file is input, and a recording made by another
 * JVM release need not contain the events this increment knows about.
 *
 * @param activity              per-channel duration statistics. Channels are named for what
 *                              they are, not for the event that produced them: {@code gc.pause},
 *                              {@code gc.wall}, {@code safepoint}, {@code park},
 *                              {@code monitor.enter}, {@code monitor.wait}, {@code sleep},
 *                              {@code file.read}, {@code file.write}, {@code socket.read},
 *                              {@code socket.write}
 * @param byteCounters          exact byte totals per channel where the event carries a byte
 *                              count; these are counters, not samples
 * @param gcCauses              collection cause &rarr; how many collections gave that cause
 * @param hotFrames             method &rarr; execution samples whose innermost frame it was
 * @param executionSampleSpacing intervals between consecutive execution samples; a measured
 *                              spacing, which is not the same thing as the sampling period
 * @param unreadableEvents      events whose fields could not be read; counted and reported
 *                              rather than silently dropped
 */
public record JfrRecordingReport(
        Provenance provenance,
        long eventCount,
        int eventTypeCount,
        long unreadableEvents,
        Instant firstEvent,
        Instant lastEvent,
        Map<String, DurationStats> activity,
        Map<String, Long> byteCounters,
        Map<String, Long> gcCauses,
        long executionSamples,
        DurationStats executionSampleSpacing,
        Map<String, Long> hotFrames,
        long nativeSamples,
        DurationStats nativeSampleSpacing,
        Map<String, Long> nativeHotFrames,
        Allocation allocation,
        Exceptions exceptions,
        HeapGauges heap,
        CpuGauges cpu) {

    /**
     * What the recording says about the environment that produced it.
     *
     * <p>Deliberately no command-line arguments or system properties. Those are exactly the
     * place a JVM carries credentials ({@code -Ddb.password=...}), and a provenance block
     * RepoPilot stores in its database is the wrong place for them. What is kept is what
     * identifies the measurement's environment: JVM name and version, when that JVM started,
     * the OS and CPU, and the recording's own name and start time.
     */
    public record Provenance(String jvmName,
                             String jvmVersion,
                             Instant jvmStartTime,
                             long processId,
                             String osVersion,
                             String cpuDescription,
                             int cpuCores,
                             int hardwareThreads,
                             String recordingName,
                             Instant recordingStart,
                             long physicalMemoryBytes,
                             long physicalMemoryUsedBytes) {
    }

    /** Count, total and maximum of a set of durations. All three are exact over the events read. */
    public record DurationStats(long count, long totalNanos, long maxNanos) {

        static final DurationStats EMPTY = new DurationStats(0, 0, 0);

        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }
    }

    /** Count, sum and maximum of a set of sample values (CPU load ratios, sampled weights). */
    public record GaugeStats(long count, double total, double max) {

        static final GaugeStats EMPTY = new GaugeStats(0, 0, 0);

        public static GaugeStats of(long count, double total, double max) {
            return new GaugeStats(count, total, max);
        }
    }

    /**
     * Allocation sampling, kept separate from byte accounting on purpose.
     *
     * <p>JFR's allocation samples carry a <em>relative weight</em>, not a size: the JDK
     * documents the weight as a value whose aggregate over many samples is a statistically
     * accurate representation of allocation pressure. RepoPilot therefore reports weight as
     * weight and never multiplies it into bytes it did not measure.
     */
    public record Allocation(long sampleCount,
                             long totalWeight,
                             Map<String, Long> classSamples,
                             Map<String, Long> classWeights,
                             Map<String, Long> siteSamples,
                             Map<String, Long> siteWeights) {

        static final Allocation EMPTY = new Allocation(0, 0, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Throwable creation during the recording.
     *
     * <p>Two independent views, kept apart because they answer different questions.
     * {@code counterObservations}/{@code counterFirst}/{@code counterLast} come from the JVM's own
     * cumulative Throwable counter, which counts from JVM start and is reported periodically; the
     * difference between its first and last observation is what was created during the recording's
     * window. The two histograms come from the per-throw events, which are threshold-gated and
     * therefore count only the throws that cleared the threshold.
     *
     * <p>Errors and exceptions are separate maps because the JVM emits separate events for them, and
     * because a thrown {@code OutOfMemoryError} is not the same observation as a thrown
     * {@code IllegalStateException}.
     */
    public record Exceptions(long counterObservations,
                             long counterFirst,
                             long counterLast,
                             Map<String, Long> exceptionsThrown,
                             Map<String, Long> errorsThrown) {

        static final Exceptions EMPTY = new Exceptions(0, 0, 0, Map.of(), Map.of());
    }

    /** Heap in use, sampled at the JVM's own GC-heap-summary points. */
    public record HeapGauges(GaugeStats usedBeforeGc, GaugeStats usedAfterGc) {

        static final HeapGauges EMPTY = new HeapGauges(GaugeStats.EMPTY, GaugeStats.EMPTY);
    }

    /** CPU usage as reported by {@code jdk.CPULoad}. Ratios, 0..1, as the event states them. */
    public record CpuGauges(GaugeStats jvmUser, GaugeStats jvmSystem, GaugeStats machineTotal) {

        static final CpuGauges EMPTY = new CpuGauges(GaugeStats.EMPTY, GaugeStats.EMPTY, GaugeStats.EMPTY);
    }
}
