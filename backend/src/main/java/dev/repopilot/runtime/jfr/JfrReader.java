package dev.repopilot.runtime.jfr;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a JDK Flight Recorder file into a {@link JfrRecordingReport}.
 *
 * <p>The only class in RepoPilot that touches {@code jdk.jfr.consumer}. It parses bytes;
 * it does not decide what to report, and it does not interpret. Everything it produces is
 * something the file stated.
 *
 * <h2>Why the field access looks defensive</h2>
 * A JFR file is untrusted input. Event types are declared inside the file, so a recording
 * can contain an event named {@code jdk.GarbageCollection} whose {@code duration} is a
 * string, or no {@code duration} field at all, or a {@code CPULoad} whose {@code jvmUser}
 * is a {@code double} rather than a {@code float}. Asking the typed accessors for the wrong
 * type throws, and one malformed event must not fail a run: every field is read through a
 * helper that returns null when the field is absent or has an unexpected type, and an event
 * that cannot be read at all is counted in
 * {@link JfrRecordingReport#unreadableEvents()} rather than dropped in silence.
 *
 * <h2>What is deliberately not read</h2>
 * Command-line arguments, system properties and environment variables are present in a JFR
 * file, and RepoPilot does not read them. They are the canonical place a JVM carries a
 * credential, and a provenance block that is stored in a database is the last place it
 * should end up. The recording also carries its per-event settings; the JDK 21 consumer API
 * does not say which event type a setting belongs to, so sampling periods and thresholds are
 * not reported as if they had been read.
 */
public final class JfrReader {

    /** Channel names for duration statistics. A channel is what the time was spent on. */
    public static final String CHANNEL_GC_PAUSE = "gc.pause";
    public static final String CHANNEL_GC_WALL = "gc.wall";
    public static final String CHANNEL_SAFEPOINT = "safepoint";
    public static final String CHANNEL_PARK = "park";
    public static final String CHANNEL_MONITOR_ENTER = "monitor.enter";
    public static final String CHANNEL_MONITOR_WAIT = "monitor.wait";
    public static final String CHANNEL_SLEEP = "sleep";
    public static final String CHANNEL_FILE_READ = "file.read";
    public static final String CHANNEL_FILE_WRITE = "file.write";
    public static final String CHANNEL_SOCKET_READ = "socket.read";
    public static final String CHANNEL_SOCKET_WRITE = "socket.write";

    private static final String EV_GC = "jdk.GarbageCollection";
    private static final String EV_SAFEPOINT = "jdk.SafepointBegin";
    private static final String EV_PARK = "jdk.ThreadPark";
    private static final String EV_MONITOR_ENTER = "jdk.JavaMonitorEnter";
    private static final String EV_MONITOR_WAIT = "jdk.JavaMonitorWait";
    private static final String EV_SLEEP = "jdk.ThreadSleep";
    private static final String EV_FILE_READ = "jdk.FileRead";
    private static final String EV_FILE_WRITE = "jdk.FileWrite";
    private static final String EV_SOCKET_READ = "jdk.SocketRead";
    private static final String EV_SOCKET_WRITE = "jdk.SocketWrite";
    private static final String EV_EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final String EV_NATIVE_SAMPLE = "jdk.NativeMethodSample";
    private static final String EV_ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";
    private static final String EV_EXCEPTION_STATISTICS = "jdk.ExceptionStatistics";
    private static final String EV_EXCEPTION_THROW = "jdk.JavaExceptionThrow";
    private static final String EV_ERROR_THROW = "jdk.JavaErrorThrow";
    private static final String EV_HEAP_SUMMARY = "jdk.GCHeapSummary";
    private static final String EV_CPU_LOAD = "jdk.CPULoad";
    private static final String EV_JVM_INFORMATION = "jdk.JVMInformation";
    private static final String EV_OS_INFORMATION = "jdk.OSInformation";
    private static final String EV_CPU_INFORMATION = "jdk.CPUInformation";
    private static final String EV_PHYSICAL_MEMORY = "jdk.PhysicalMemory";
    private static final String EV_ACTIVE_RECORDING = "jdk.ActiveRecording";

    /** Distinct keys kept per histogram. Beyond this, new keys are counted, not stored. */
    private static final int DISTINCT_KEY_LIMIT = 20_000;
    /** How often the caller's cancellation check runs while walking a large recording. */
    private static final int CHECKPOINT_INTERVAL = 100_000;

    /** Every JDK Flight Recorder file starts with these four bytes. */
    private static final byte[] RECORDING_HEADER = {'F', 'L', 'R', 0};

    /** The gzip magic, so a compressed recording gets an answer that helps. */
    private static final byte[] GZIP_HEADER = {0x1f, (byte) 0x8b};

    private JfrReader() {
    }

    /** What a file's first bytes say it is. */
    public enum FileKind {
        /** A raw recording this reader can parse. */
        RECORDING,
        /** A gzip stream, which is what the JDK writes when a dump is requested with gzip on. */
        GZIP,
        /** Something else: neither a recording nor compressed data. */
        UNKNOWN
    }

    /**
     * Reads a file's first bytes to say what it is.
     *
     * <p>Deliberately called before {@code RecordingFile} is constructed. That constructor opens the
     * file and, for a file that is not a recording, throws without closing the channel it opened:
     * the upload is rejected and the file stays locked, undeletable and holding a descriptor, which
     * on Windows also blocks the temporary directory from being cleaned up. Reading four bytes
     * through a stream this class closes removes that failure mode and lets the refusal name what
     * was found instead of "not a recording".
     */
    public static FileKind detectKind(Path file) throws IOException {
        byte[] header = new byte[RECORDING_HEADER.length];
        int read;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            read = in.readNBytes(header, 0, header.length);
        }
        if (matches(header, read, RECORDING_HEADER)) {
            return FileKind.RECORDING;
        }
        if (matches(header, read, GZIP_HEADER)) {
            return FileKind.GZIP;
        }
        return FileKind.UNKNOWN;
    }

    private static boolean matches(byte[] bytes, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static JfrRecordingReport read(Path file) throws IOException {
        return read(file, () -> {
        });
    }

    /**
     * @param checkpoint invoked periodically so a runaway parse can be cancelled; must not
     *                   be null
     */
    public static JfrRecordingReport read(Path file, Runnable checkpoint) throws IOException {
        FileKind kind = detectKind(file);
        if (kind != FileKind.RECORDING) {
            // Refused before RecordingFile is constructed; see detectKind for why that matters.
            throw new IOException(kind == FileKind.GZIP
                    ? "the file is gzip-compressed, so it is not a raw JDK Flight Recorder recording"
                    : "the file does not begin with the JDK Flight Recorder header ('FLR')");
        }
        Accumulator acc = new Accumulator();
        int eventTypeCount;
        long sinceCheckpoint = 0;

        try (RecordingFile recording = new RecordingFile(file)) {
            eventTypeCount = recording.readEventTypes().size();
            while (recording.hasMoreEvents()) {
                if (++sinceCheckpoint >= CHECKPOINT_INTERVAL) {
                    sinceCheckpoint = 0;
                    checkpoint.run();
                }
                RecordedEvent event = recording.readEvent();
                acc.eventCount++;
                acc.observeWindow(event.getStartTime());
                try {
                    dispatch(event, acc);
                } catch (RuntimeException malformed) {
                    // A field the recording declared one way and stored another. Counted, so
                    // the report can say how much of the file was read rather than implying
                    // it was all of it.
                    acc.unreadableEvents++;
                }
            }
        }
        return acc.toReport(eventTypeCount);
    }

    private static void dispatch(RecordedEvent event, Accumulator acc) {
        String name = event.getEventType().getName();
        switch (name) {
            case EV_GC -> {
                acc.duration(CHANNEL_GC_WALL, durationNanos(event));
                acc.duration(CHANNEL_GC_PAUSE, timespanNanos(event, "sumOfPauses"));
                acc.increment(acc.gcCauses, stringValue(event, "cause"));
            }
            case EV_SAFEPOINT -> acc.duration(CHANNEL_SAFEPOINT, durationNanos(event));
            case EV_PARK -> acc.duration(CHANNEL_PARK, durationNanos(event));
            case EV_MONITOR_ENTER -> acc.duration(CHANNEL_MONITOR_ENTER, durationNanos(event));
            case EV_MONITOR_WAIT -> acc.duration(CHANNEL_MONITOR_WAIT, durationNanos(event));
            case EV_SLEEP -> acc.duration(CHANNEL_SLEEP, durationNanos(event));
            case EV_FILE_READ -> {
                acc.duration(CHANNEL_FILE_READ, durationNanos(event));
                acc.bytes(CHANNEL_FILE_READ, longValue(event, "bytesRead"));
            }
            case EV_FILE_WRITE -> {
                acc.duration(CHANNEL_FILE_WRITE, durationNanos(event));
                acc.bytes(CHANNEL_FILE_WRITE, longValue(event, "bytesWritten"));
            }
            case EV_SOCKET_READ -> {
                acc.duration(CHANNEL_SOCKET_READ, durationNanos(event));
                acc.bytes(CHANNEL_SOCKET_READ, longValue(event, "bytesRead"));
            }
            case EV_SOCKET_WRITE -> {
                acc.duration(CHANNEL_SOCKET_WRITE, durationNanos(event));
                acc.bytes(CHANNEL_SOCKET_WRITE, longValue(event, "bytesWritten"));
            }
            case EV_EXECUTION_SAMPLE -> {
                acc.executionSamples++;
                String frame = topFrame(event);
                if (frame != null) {
                    acc.increment(acc.hotFrames, frame);
                }
                acc.sampleSpacing(event.getStartTime(), false);
            }
            case EV_NATIVE_SAMPLE -> {
                acc.nativeSamples++;
                String frame = topFrame(event);
                if (frame != null) {
                    acc.increment(acc.nativeHotFrames, frame);
                }
                acc.sampleSpacing(event.getStartTime(), true);
            }
            case EV_ALLOCATION_SAMPLE -> acc.allocation(event);
            case EV_EXCEPTION_STATISTICS -> acc.exceptionStatistics(event);
            // Two different events, two different claims: the JVM reports thrown Errors separately
            // from thrown Exceptions, and merging them would misstate both.
            case EV_EXCEPTION_THROW -> acc.increment(acc.thrownExceptions, className(event, "thrownClass"));
            case EV_ERROR_THROW -> acc.increment(acc.thrownErrors, className(event, "thrownClass"));
            case EV_HEAP_SUMMARY -> acc.heapSummary(event);
            case EV_CPU_LOAD -> acc.cpuLoad(event);
            case EV_JVM_INFORMATION -> acc.jvmInformation(event);
            case EV_OS_INFORMATION -> acc.osVersion = stringValue(event, "osVersion");
            case EV_CPU_INFORMATION -> acc.cpuInformation(event);
            case EV_PHYSICAL_MEMORY -> acc.physicalMemory(event);
            case EV_ACTIVE_RECORDING -> acc.activeRecording(event);
            default -> {
                // Every other event type is read for the total count and nothing else. The
                // report does not claim to describe events this build does not interpret.
            }
        }
    }

    // ------------------------------------------------------------------ field access

    /**
     * The event's own duration, in nanoseconds.
     *
     * <p>Not read as a field, and this is the one detail in the reader that is easy to get wrong
     * and impossible to notice. JFR stores event timestamps in the recording's tick unit, which is a
     * property of the machine that wrote the file; a raw {@code duration} field read through the
     * generic accessor is therefore a tick count, not a time. Measured on this machine, a 50 ms
     * sleep reports a raw duration of 511,264 while its real duration is 0.051 s &mdash; a
     * discrepancy of a factor of about a hundred that would silently corrupt every duration
     * RepoPilot reported. {@link RecordedEvent#getDuration()} converts through the file's own tick
     * frequency and is correct for any machine.
     */
    private static long durationNanos(RecordedEvent event) {
        Duration duration = event.getDuration();
        return duration == null ? 0L : duration.toNanos();
    }

    /**
     * A named duration-valued field, converted the same way.
     *
     * <p>For a field the JVM declares with the same unit as its timestamps, the typed accessor is
     * the only one that can return a time rather than a count of ticks.
     */
    private static long timespanNanos(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return 0L;
        }
        try {
            Duration duration = event.getDuration(field);
            return duration == null ? 0L : duration.toNanos();
        } catch (RuntimeException unexpectedType) {
            return 0L;
        }
    }

    /**
     * Reads a duration or count field, whatever numeric type the file used for it.
     *
     * <p>JFR stores a byte count as a {@code long}, but a file is free to declare the same field
     * name as a different numeric type, and the typed accessors throw when the two disagree.
     * Reading through the generic accessor and accepting any {@link Number} costs one boxing
     * operation and removes a whole class of "the recording failed the run" failure.
     *
     * <p>Never use this for a duration or a timestamp: those are unit-bearing, and see
     * {@link #durationNanos} for what happens when a tick count is treated as a time.
     */
    private static long longValue(RecordedEvent event, String field) {
        Object value = valueOrNull(event, field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stringValue(RecordedEvent event, String field) {
        Object value = valueOrNull(event, field);
        return value == null ? null : value.toString();
    }

    private static String className(RecordedEvent event, String field) {
        Object value = valueOrNull(event, field);
        if (value instanceof RecordedClass recordedClass) {
            return normaliseClassName(recordedClass.getName());
        }
        return value == null ? null : normaliseClassName(value.toString());
    }

    private static Instant instantValue(RecordedEvent event, String field) {
        Object value = valueOrNull(event, field);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            // A JFR timestamp that is stored as a raw long is milliseconds since the epoch.
            return Instant.ofEpochMilli(number.longValue());
        }
        return null;
    }

    private static Object valueOrNull(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return null;
        }
        try {
            return event.getValue(field);
        } catch (RuntimeException unexpectedType) {
            return null;
        }
    }

    /**
     * The innermost Java frame of an event's stack trace, as {@code type.method}.
     *
     * <p>Native frames are skipped rather than reported as {@code (native)}: a method
     * RepoPilot cannot name is not a method it can attribute work to.
     */
    private static String topFrame(RecordedEvent event) {
        try {
            RecordedStackTrace trace = event.getStackTrace();
            if (trace == null) {
                return null;
            }
            for (RecordedFrame frame : trace.getFrames()) {
                if (!frame.isJavaFrame()) {
                    continue;
                }
                RecordedMethod method = frame.getMethod();
                if (method == null || method.getName() == null || method.getType() == null) {
                    continue;
                }
                String type = normaliseClassName(method.getType().getName());
                if (type != null) {
                    return type + "." + method.getName();
                }
            }
        } catch (RuntimeException unreadableStackTrace) {
            return null;
        }
        return null;
    }

    private static String normaliseClassName(String name) {
        return name == null ? null : name.replace('/', '.');
    }

    // ------------------------------------------------------------------ accumulator

    /** Single-pass aggregation state. Kept on the stack of {@link #read}; never shared. */
    private static final class Accumulator {

        final Map<String, long[]> activity = new LinkedHashMap<>();
        final Map<String, Long> bytes = new LinkedHashMap<>();
        final Map<String, Long> gcCauses = new LinkedHashMap<>();
        final Map<String, Long> hotFrames = new LinkedHashMap<>();
        final Map<String, Long> nativeHotFrames = new LinkedHashMap<>();
        final Map<String, Long> thrownExceptions = new LinkedHashMap<>();
        final Map<String, Long> thrownErrors = new LinkedHashMap<>();
        final Map<String, Long> allocationClassSamples = new LinkedHashMap<>();
        final Map<String, Long> allocationClassWeights = new LinkedHashMap<>();
        final Map<String, Long> allocationSiteSamples = new LinkedHashMap<>();
        final Map<String, Long> allocationSiteWeights = new LinkedHashMap<>();

        long eventCount;
        long unreadableEvents;
        long executionSamples;
        long nativeSamples;
        long allocationSamples;
        long allocationWeight;
        long exceptionCounterObservations;
        long exceptionCounterFirst;
        long exceptionCounterLast;
        long heapBeforeGcCount;
        double heapBeforeGcTotal;
        double heapBeforeGcMax;
        long heapAfterGcCount;
        double heapAfterGcTotal;
        double heapAfterGcMax;
        long cpuCount;
        double cpuUserTotal;
        double cpuUserMax;
        long cpuUserCount;
        double cpuSystemTotal;
        double cpuSystemMax;
        long cpuSystemCount;
        double cpuMachineTotal;
        double cpuMachineMax;
        long cpuMachineCount;
        long physicalMemoryCount;
        long physicalMemoryBytes;
        long physicalMemoryUsedBytes;

        Instant firstEvent;
        Instant lastEvent;
        Instant lastExecutionSample;
        Instant lastNativeSample;

        long executionSpacingCount;
        long executionSpacingTotalNanos;
        long executionSpacingMaxNanos;
        long nativeSpacingCount;
        long nativeSpacingTotalNanos;
        long nativeSpacingMaxNanos;

        String jvmName;
        String jvmVersion;
        Instant jvmStartTime;
        long processId;
        String osVersion;
        String cpuDescription;
        int cpuCores;
        int hardwareThreads;
        String recordingName;
        Instant recordingStart;

        void observeWindow(Instant startTime) {
            if (startTime == null) {
                return;
            }
            if (firstEvent == null || startTime.isBefore(firstEvent)) {
                firstEvent = startTime;
            }
            if (lastEvent == null || startTime.isAfter(lastEvent)) {
                lastEvent = startTime;
            }
        }

        void duration(String channel, long nanos) {
            long[] slot = activity.computeIfAbsent(channel, key -> new long[3]);
            slot[0]++;
            slot[1] += nanos;
            slot[2] = Math.max(slot[2], nanos);
        }

        void bytes(String channel, long count) {
            bytes.merge(channel, count, Long::sum);
        }

        void increment(Map<String, Long> target, String key) {
            if (key == null || key.isBlank()) {
                return;
            }
            if (!target.containsKey(key) && target.size() >= DISTINCT_KEY_LIMIT) {
                // Bounded deliberately: a hostile or pathological recording must not be able
                // to exhaust memory through the number of distinct method names it contains.
                return;
            }
            target.merge(key, 1L, Long::sum);
        }

        void sampleSpacing(Instant sampleTime, boolean nativeSampler) {
            Instant previous = nativeSampler ? lastNativeSample : lastExecutionSample;
            if (previous != null && sampleTime != null && !sampleTime.isBefore(previous)) {
                long totalNanos = Duration.between(previous, sampleTime).toNanos();
                if (nativeSampler) {
                    nativeSpacingCount++;
                    nativeSpacingTotalNanos += totalNanos;
                    nativeSpacingMaxNanos = Math.max(nativeSpacingMaxNanos, totalNanos);
                } else {
                    executionSpacingCount++;
                    executionSpacingTotalNanos += totalNanos;
                    executionSpacingMaxNanos = Math.max(executionSpacingMaxNanos, totalNanos);
                }
            }
            if (nativeSampler) {
                lastNativeSample = sampleTime;
            } else {
                lastExecutionSample = sampleTime;
            }
        }

        void allocation(RecordedEvent event) {
            allocationSamples++;
            allocationWeight += longValue(event, "weight");
            String type = className(event, "objectClass");
            if (type != null) {
                long weight = longValue(event, "weight");
                classSample(type);
                allocationClassWeights.merge(type, weight, Long::sum);
            }
            String site = topFrame(event);
            if (site != null) {
                siteSample(site);
                allocationSiteWeights.merge(site, longValue(event, "weight"), Long::sum);
            }
        }

        private void classSample(String type) {
            if (!allocationClassSamples.containsKey(type) && allocationClassSamples.size() >= DISTINCT_KEY_LIMIT) {
                return;
            }
            allocationClassSamples.merge(type, 1L, Long::sum);
        }

        private void siteSample(String site) {
            if (!allocationSiteSamples.containsKey(site) && allocationSiteSamples.size() >= DISTINCT_KEY_LIMIT) {
                return;
            }
            allocationSiteSamples.merge(site, 1L, Long::sum);
        }

        /**
         * The JVM's cumulative Throwable counter, sampled periodically.
         *
         * <p>Keeps the first and last observation rather than a total: the counter counts from JVM
         * start, so the only figure that describes <em>this recording</em> is the difference between
         * its value at the start and at the end of the recorded window. A single observation yields
         * no difference, and the mapper says so instead of reporting zero.
         */
        void exceptionStatistics(RecordedEvent event) {
            long value = longValue(event, "throwables");
            if (exceptionCounterObservations == 0) {
                exceptionCounterFirst = value;
            }
            exceptionCounterObservations++;
            exceptionCounterLast = value;
        }

        void heapSummary(RecordedEvent event) {
            String when = stringValue(event, "when");
            long used = longValue(event, "heapUsed");
            if ("Before GC".equalsIgnoreCase(when)) {
                heapBeforeGcCount++;
                heapBeforeGcTotal += used;
                heapBeforeGcMax = Math.max(heapBeforeGcMax, used);
            } else if ("After GC".equalsIgnoreCase(when)) {
                heapAfterGcCount++;
                heapAfterGcTotal += used;
                heapAfterGcMax = Math.max(heapAfterGcMax, used);
            }
        }

        void cpuLoad(RecordedEvent event) {
            Double user = doubleValue(event, "jvmUser");
            Double system = doubleValue(event, "jvmSystem");
            Double machine = doubleValue(event, "machineTotal");
            if (user == null && system == null && machine == null) {
                return;
            }
            if (user != null) {
                cpuUserCount++;
                cpuUserTotal += user;
                cpuUserMax = Math.max(cpuUserMax, user);
            }
            if (system != null) {
                cpuSystemCount++;
                cpuSystemTotal += system;
                cpuSystemMax = Math.max(cpuSystemMax, system);
            }
            if (machine != null) {
                cpuMachineCount++;
                cpuMachineTotal += machine;
                cpuMachineMax = Math.max(cpuMachineMax, machine);
            }
        }

        void jvmInformation(RecordedEvent event) {
            if (jvmName == null) {
                jvmName = stringValue(event, "jvmName");
                jvmVersion = stringValue(event, "jvmVersion");
                jvmStartTime = instantValue(event, "jvmStartTime");
            }
            long pid = longValue(event, "pid");
            if (pid > 0) {
                processId = pid;
            }
        }

        void cpuInformation(RecordedEvent event) {
            if (cpuDescription != null) {
                return;
            }
            cpuDescription = stringValue(event, "description");
            cpuCores = (int) longValue(event, "cores");
            hardwareThreads = (int) longValue(event, "hwThreads");
        }

        void physicalMemory(RecordedEvent event) {
            long total = longValue(event, "totalSize");
            long used = longValue(event, "usedSize");
            if (total > 0) {
                physicalMemoryCount++;
                physicalMemoryBytes = total;
                physicalMemoryUsedBytes = used;
            }
        }

        void activeRecording(RecordedEvent event) {
            if (recordingName == null) {
                recordingName = stringValue(event, "name");
            }
            Instant start = instantValue(event, "recordingStart");
            if (start != null && (recordingStart == null || start.isBefore(recordingStart))) {
                recordingStart = start;
            }
        }

        JfrRecordingReport toReport(int eventTypeCount) {
            Map<String, JfrRecordingReport.DurationStats> durations = new LinkedHashMap<>();
            activity.forEach((channel, slot) ->
                    durations.put(channel, new JfrRecordingReport.DurationStats(slot[0], slot[1], slot[2])));
            return new JfrRecordingReport(
                    new JfrRecordingReport.Provenance(jvmName, jvmVersion, jvmStartTime, processId, osVersion,
                            cpuDescription, cpuCores, hardwareThreads, recordingName, recordingStart,
                            physicalMemoryBytes, physicalMemoryUsedBytes),
                    eventCount,
                    eventTypeCount,
                    unreadableEvents,
                    firstEvent,
                    lastEvent,
                    durations,
                    Map.copyOf(bytes),
                    Map.copyOf(gcCauses),
                    executionSamples,
                    new JfrRecordingReport.DurationStats(executionSpacingCount, executionSpacingTotalNanos,
                            executionSpacingMaxNanos),
                    Map.copyOf(hotFrames),
                    nativeSamples,
                    new JfrRecordingReport.DurationStats(nativeSpacingCount, nativeSpacingTotalNanos,
                            nativeSpacingMaxNanos),
                    Map.copyOf(nativeHotFrames),
                    new JfrRecordingReport.Allocation(allocationSamples, allocationWeight,
                            Map.copyOf(allocationClassSamples), Map.copyOf(allocationClassWeights),
                            Map.copyOf(allocationSiteSamples), Map.copyOf(allocationSiteWeights)),
                    new JfrRecordingReport.Exceptions(exceptionCounterObservations, exceptionCounterFirst,
                            exceptionCounterLast, Map.copyOf(thrownExceptions), Map.copyOf(thrownErrors)),
                    new JfrRecordingReport.HeapGauges(
                            JfrRecordingReport.GaugeStats.of(heapBeforeGcCount, heapBeforeGcTotal, heapBeforeGcMax),
                            JfrRecordingReport.GaugeStats.of(heapAfterGcCount, heapAfterGcTotal, heapAfterGcMax)),
                    new JfrRecordingReport.CpuGauges(
                            JfrRecordingReport.GaugeStats.of(cpuUserCount, cpuUserTotal, cpuUserMax),
                            JfrRecordingReport.GaugeStats.of(cpuSystemCount, cpuSystemTotal, cpuSystemMax),
                            JfrRecordingReport.GaugeStats.of(cpuMachineCount, cpuMachineTotal, cpuMachineMax)));
        }

        private static Double doubleValue(RecordedEvent event, String field) {
            Object value = valueOrNull(event, field);
            return value instanceof Number number ? number.doubleValue() : null;
        }
    }
}
