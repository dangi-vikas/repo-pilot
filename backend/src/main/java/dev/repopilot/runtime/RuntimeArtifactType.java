package dev.repopilot.runtime;

import dev.repopilot.jobs.JobType;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of file RepoPilot accepts as runtime evidence.
 *
 * <p>An artifact is a recording of a running process, uploaded by the operator. It is
 * treated as untrusted input: it is stored by content hash, parsed by a reader that
 * never executes anything in it, and never written into a repository working tree.
 *
 * <p>A parser exists for {@link #JFR_RECORDING}, {@link #HEAP_DUMP} and {@link #THREAD_DUMP}. The other
 * constants exist because the schema and the job types already name them, and because a refusal
 * that names the missing capability ("benchmark import is not implemented in this build") is more
 * useful than a generic rejection. {@link #jobType()} is what makes that refusal
 * precise, and {@link #supported()} is what makes it honest.
 */
public enum RuntimeArtifactType {

    /** A JDK Flight Recorder file ({@code .jfr}). Parsed in process with {@code jdk.jfr.consumer}. */
    JFR_RECORDING("jfr", JobType.JFR_ANALYSIS, true, false),

    /**
     * A heap dump ({@code .hprof}) in the HPROF binary format {@code jmap},
     * {@code jcmd GC.heap_dump} and {@code -XX:+HeapDumpOnOutOfMemoryError} write. Parsed in process
     * by a reader that keeps a census, never the objects themselves.
     */
    HEAP_DUMP("hprof", JobType.HEAP_ANALYSIS, true, true),

    /**
     * A thread dump ({@code .txt}) in the plain-text format {@code jstack},
     * {@code jcmd <pid> Thread.print} and {@code kill -3} write. The rich format only: the
     * {@code Thread.dump_to_file} shapes carry no thread states and are refused by name.
     */
    THREAD_DUMP("txt", JobType.THREAD_DUMP_ANALYSIS, true, false),

    /**
     * A benchmark result file. Needs the benchmark importer; not implemented.
     *
     * <p>Kept unread rather than refused, because it is a summary the operator already produced and
     * throwing it away would lose evidence. It is a file of numbers and names about a build, not a
     * dump of a process's memory, so it is not flagged as one.
     */
    BENCHMARK_RESULT("json", JobType.BENCHMARK_IMPORT, false, false);

    private final String extension;
    private final JobType jobType;
    private final boolean supported;
    private final boolean containsProcessMemory;

    RuntimeArtifactType(String extension, JobType jobType, boolean supported, boolean containsProcessMemory) {
        this.extension = extension;
        this.jobType = jobType;
        this.supported = supported;
        this.containsProcessMemory = containsProcessMemory;
    }

    /** Preferred file extension, used when naming the stored copy. Never trusted from the request. */
    public String extension() {
        return extension;
    }

    /** The job type that analyses this kind of artifact. */
    public JobType jobType() {
        return jobType;
    }

    /** Whether this build can actually analyse it. */
    public boolean supported() {
        return supported;
    }

    /**
     * Whether the file is a dump of process memory rather than an event log.
     *
     * <p>This is not bookkeeping: a JFR recording contains event metadata, whereas a heap
     * dump contains the literal bytes of an application's heap, including any credential
     * that was live in the process. RepoPilot reports the difference in the UI and in its
     * documentation rather than treating every upload as equally sensitive.
     *
     * <p>A thread dump is neither. It names the threads and the frames they were in, which
     * reveals an application's structure without containing the contents of its memory, so this is
     * false for it and the UI says what such a file does reveal instead of warning that it contains
     * memory. A stack of frames is still the application's own code, and the file is still treated
     * as untrusted input.
     *
     * <p>A benchmark result is neither. It is a table of numbers an operator's own harness wrote, so
     * flagging it as process memory would be a warning that is simply false — and a warning that is
     * false where it is easy to check stops being read where it is not.
     */
    public boolean containsProcessMemory() {
        return containsProcessMemory;
    }

    /** Whether this build can parse it, for error messages that name the actual reason. */
    public String unsupportedReason() {
        return switch (this) {
            case JFR_RECORDING, THREAD_DUMP, HEAP_DUMP -> null;
            case BENCHMARK_RESULT -> "Benchmark import is not implemented in this build.";
        };
    }

    /**
     * Resolves a client-supplied type name.
     *
     * @throws IllegalArgumentException when the name is not a known type, so the caller can
     *                                  turn it into a 400 rather than guessing a default
     */
    public static RuntimeArtifactType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("artifact type is required");
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (RuntimeArtifactType type : values()) {
            if (type.name().equals(normalised)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown artifact type: " + raw);
    }

    /** Guesses the type from an uploaded file's name, for a friendlier default. */
    public static Optional<RuntimeArtifactType> fromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jfr")) {
            return Optional.of(JFR_RECORDING);
        }
        if (lower.endsWith(".hprof") || lower.endsWith(".heap")) {
            return Optional.of(HEAP_DUMP);
        }
        if (lower.endsWith(".jfr.json")) {
            return Optional.of(JFR_RECORDING);
        }
        if (lower.endsWith(".threads") || lower.endsWith(".threaddump")) {
            return Optional.of(THREAD_DUMP);
        }
        if (lower.endsWith(".json")) {
            return Optional.of(BENCHMARK_RESULT);
        }
        if (lower.endsWith(".txt")) {
            return Optional.of(THREAD_DUMP);
        }
        return Optional.empty();
    }
}
