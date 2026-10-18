package dev.repopilot.runtime.jfr;

import dev.repopilot.jobs.JobAbortedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads a JFR recording that the test itself produces.
 *
 * <p>No binary fixture is checked in, and not only to avoid a blob in the repository: a fixture
 * recorded on one JDK would silently stop describing the events the current JDK emits, and the
 * test would quietly become a test of a historical artifact. Generating the recording here means
 * the reader is exercised against the JDK it is actually compiled and shipped with.
 *
 * <p>The assertions are exact where the recording can make them exact. Events whose thresholds are
 * set to zero <em>must</em> appear, so their presence and their arithmetic are asserted rather than
 * tolerated. Sampled events are asserted to be internally consistent &mdash; a spacing figure can
 * only be derived from consecutive samples &mdash; rather than asserted to a count, because a count
 * of samples is a property of the machine's load as much as of the code.
 */
class JfrReaderTest {

    private static final String RECORDING_NAME = "repopilot-jfr-reader-test";

    /** Keeps the busy loop's result observable, so the compiler cannot elide the work. */
    private static volatile long sink;

    @TempDir
    Path directory;

    @Test
    @DisplayName("reads provenance, window, thresholds and byte counts from a recording it made")
    void readsItsOwnRecording() throws Exception {
        Path file = directory.resolve("reader-test.jfr");
        long sleptMillis = 50;
        writeRecording(file, sleptMillis);

        JfrRecordingReport report = JfrReader.read(file);

        assertThat(report.eventCount()).isPositive();
        assertThat(report.eventTypeCount()).isPositive();
        assertThat(report.unreadableEvents()).isZero();
        assertThat(report.firstEvent()).isNotNull();
        assertThat(report.lastEvent()).isAfterOrEqualTo(report.firstEvent());

        // Provenance comes from the JVM's own events, never from a default or a guess.
        assertThat(report.provenance().jvmName()).isNotBlank();
        assertThat(report.provenance().jvmVersion()).isNotBlank();
        assertThat(report.provenance().recordingName()).isEqualTo(RECORDING_NAME);
        assertThat(report.provenance().processId()).isPositive();
        assertThat(report.provenance().physicalMemoryBytes()).isPositive();
        assertThat(report.provenance().osVersion()).isNotBlank();

        // Thresholds were set to zero for these, so their absence is a reader bug, not luck.
        JfrRecordingReport.DurationStats sleep = report.activity().get(JfrReader.CHANNEL_SLEEP);
        assertThat(sleep).isNotNull();
        assertThat(sleep.count()).isPositive();
        assertThat(sleep.totalNanos()).isGreaterThanOrEqualTo(sleptMillis * 1_000_000L);
        assertThat(sleep.maxNanos()).isGreaterThanOrEqualTo(sleptMillis * 1_000_000L);
        assertThat(sleep.maxMillis()).isLessThanOrEqualTo(sleep.totalMillis() + 1.0);

        JfrRecordingReport.DurationStats read = report.activity().get(JfrReader.CHANNEL_FILE_READ);
        assertThat(read).isNotNull();
        assertThat(read.count()).isPositive();
        // A byte count is a counter the event carries, not a sampled estimate.
        assertThat(report.byteCounters().get(JfrReader.CHANNEL_FILE_READ)).isPositive();
        assertThat(report.byteCounters().get(JfrReader.CHANNEL_FILE_WRITE)).isPositive();

        // System.gc() during the recording. The wall duration and the pause are different
        // quantities and both are read from the same event.
        assertThat(report.activity().get(JfrReader.CHANNEL_GC_WALL)).isNotNull();
        assertThat(report.activity().get(JfrReader.CHANNEL_GC_WALL).count()).isPositive();
        assertThat(report.activity().get(JfrReader.CHANNEL_GC_PAUSE)).isNotNull();

        // Execution samples: consistent arithmetic, whatever the count happens to be.
        assertThat(report.executionSamples()).isPositive();
        assertThat(report.hotFrames()).isNotEmpty();
        assertThat(report.hotFrames().values().stream().mapToLong(Long::longValue).sum())
                .isBetween(1L, report.executionSamples());
        // One interval per consecutive pair, exactly.
        assertThat(report.executionSampleSpacing().count()).isEqualTo(report.executionSamples() - 1);
        assertThat(report.executionSampleSpacing().totalNanos()).isPositive();
        // The innermost frame of a busy loop in this test class is a method in this test class.
        assertThat(report.hotFrames().keySet()).anySatisfy(frame ->
                assertThat(frame).contains(JfrReaderTest.class.getSimpleName()));
    }

    @Test
    @DisplayName("refuses a file that is not a recording instead of reporting an empty one")
    void refusesNonRecording() throws IOException {
        Path notARecording = directory.resolve("notes.jfr");
        Files.writeString(notARecording, "this is not a flight recording\n", StandardCharsets.UTF_8);

        // An empty report would be indistinguishable from a recording of an idle process, which is
        // the one answer a reader must never give for a file it could not parse.
        assertThatThrownBy(() -> JfrReader.read(notARecording)).isInstanceOf(IOException.class);
    }

    /** Produces a recording whose events are enabled with zero thresholds, so they must appear. */
    private static void writeRecording(Path target, long sleptMillis) throws Exception {
        Path readTarget = target.getParent().resolve("payload.bin");
        byte[] payload = new byte[200_000];
        new Random(42).nextBytes(payload);

        try (jdk.jfr.Recording recording = new jdk.jfr.Recording(
                jdk.jfr.Configuration.getConfiguration("profile"))) {
            recording.setName(RECORDING_NAME);
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.ThreadSleep").with("threshold", "0 ns");
            recording.enable("jdk.FileRead").with("threshold", "0 ns");
            recording.enable("jdk.FileWrite").with("threshold", "0 ns");
            recording.enable("jdk.GarbageCollection");
            recording.start();

            Files.write(readTarget, payload);
            Files.readAllBytes(readTarget);
            Thread.sleep(sleptMillis);
            burnCpu(Duration.ofMillis(600));
            System.gc();
            Thread.sleep(20);

            recording.stop();
            recording.dump(target);
        }
        Files.deleteIfExists(readTarget);
    }

    /** Busy Java work, so the execution sampler has something to sample. */
    private static void burnCpu(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long accumulator = 1;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 1_000; i++) {
                accumulator = accumulator * 31 + i;
            }
        }
        sink = accumulator;
    }
}
