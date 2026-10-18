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
 * <p>Only {@link #JFR_RECORDING} has a parser in this increment. The other constants
 * exist because the schema and the job types already name them, and because a refusal
 * that names the missing capability ("heap dumps are not parsed by this build") is more
 * useful than a generic rejection. {@link #jobType()} is what makes that refusal
 * precise, and {@link #supported()} is what makes it honest.
 */
public enum RuntimeArtifactType {

    /** A JDK Flight Recorder file ({@code .jfr}). Parsed in process with {@code jdk.jfr.consumer}. */
    JFR_RECORDING("jfr", JobType.JFR_ANALYSIS, true, false),

    /** A heap dump ({@code .hprof}). Needs a heap analyzer; not implemented. */
    HEAP_DUMP("hprof", JobType.HEAP_ANALYSIS, false, true),

    /** A thread dump ({@code .txt}/{@code .json}). Needs a thread-dump parser; not implemented. */
    THREAD_DUMP("txt", JobType.THREAD_DUMP_ANALYSIS, false, true),

    /** A benchmark result file. Needs the benchmark importer; not implemented. */
    BENCHMARK_RESULT("json", JobType.BENCHMARK_IMPORT, false, true);

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
     */
    public boolean containsProcessMemory() {
        return containsProcessMemory;
    }

    /** Whether this build can parse it, for error messages that name the actual reason. */
    public String unsupportedReason() {
        return switch (this) {
            case JFR_RECORDING -> null;
            case HEAP_DUMP -> "Heap dump analysis is not implemented in this build. "
                    + "The file can be stored, but no analyzer will read it and RepoPilot will not guess.";
            case THREAD_DUMP -> "Thread dump analysis is not implemented in this build.";
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
