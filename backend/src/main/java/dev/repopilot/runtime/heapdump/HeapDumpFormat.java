package dev.repopilot.runtime.heapdump;

import java.util.List;

/**
 * The shapes a file that claims to be a heap dump can have.
 *
 * <p>Only one of them can be read, and the others are not close calls: a heap dump is the HPROF
 * binary format {@code jmap}, {@code jcmd GC.heap_dump} and {@code -XX:+HeapDumpOnOutOfMemoryError}
 * write, and everything else is a different kind of evidence with a different reader. The most
 * common mistake is not a corrupt file at all: it is the <em>class histogram</em> an operator
 * collected with {@code jmap -histo} and then labelled a heap dump. A histogram is a summary the
 * JVM printed: counts and estimated byte totals per class, with no object graph behind them. That
 * refusal is spelled out by name here, because a histogram is the one file an operator is most
 * likely to believe is a dump.
 *
 * <p>The version matters too, and it is checked rather than assumed. HPROF 1.0.2 is what HotSpot has
 * written for many releases and 1.0.1 appears in old dumps; 1.0.3 is the Android/ART variant, whose
 * heap records are not the same format at all and which this build does not read.
 */
public enum HeapDumpFormat {

    /** {@code JAVA PROFILE 1.0.2}: what HotSpot's tools and the OOM hook write today. Readable. */
    HPROF_1_0_2(true),

    /** {@code JAVA PROFILE 1.0.1}: an older HPROF still seen in archived dumps. Readable. */
    HPROF_1_0_1(true),

    /** An HPROF header naming a version this build does not read (for example ART's 1.0.3). */
    HPROF_OTHER_VERSION(false),

    /** gzip, which is what {@code jcmd GC.heap_dump -gz=<level>} writes. Not read in process. */
    GZIP(false),

    /** A JDK Flight Recorder recording: {@code FLR}. A timeline of events, not a snapshot of a heap. */
    FLIGHT_RECORDER(false),

    /** The text output of {@code jmap -histo} / {@code jcmd GC.class_histogram}. */
    CLASS_HISTOGRAM(false),

    /** A plain-text thread dump, which is a different artefact type with its own reader. */
    THREAD_DUMP_TEXT(false),

    /** A zero-length file, which is refused rather than treated as an empty heap. */
    EMPTY(false),

    /** Anything else: no HPROF header and no recognisable shape. */
    UNKNOWN(false);

    /** The versions whose record layout this build parses. */
    public static final List<String> READABLE_VERSIONS = List.of("1.0.2", "1.0.1");

    private final boolean readable;

    HeapDumpFormat(boolean readable) {
        this.readable = readable;
    }

    /** Whether this build can parse a file of this shape into a report. */
    public boolean readable() {
        return readable;
    }

    /**
     * Says what the file is and what to do instead.
     *
     * <p>The refusal names the capability that is missing and the exact command that produces a
     * readable file, because "unsupported input" tells an operator nothing they can act on.
     *
     * @param format the detected shape
     * @param found  what the file actually begins with, for the cases where quoting it is the most
     *               useful thing the message can say (a version string, a few hex bytes); may be null
     */
    public static String explain(HeapDumpFormat format, String found) {
        return switch (format) {
            case HPROF_1_0_1, HPROF_1_0_2 -> null;
            case HPROF_OTHER_VERSION -> "This is an HPROF file of version "
                    + (found == null ? "(unreadable)" : found) + ", which this build does not read. "
                    + "RepoPilot reads the " + String.join(" and ", READABLE_VERSIONS)
                    + " layouts that HotSpot writes. A dump from another runtime (Android's ART writes "
                    + "a version of HPROF with different heap records) has to be converted before it "
                    + "can be analysed here.";
            case GZIP -> "This is a gzip-compressed file (it begins with 1f 8b), which is what "
                    + "jcmd <pid> GC.heap_dump -gz=<level> writes. RepoPilot reads the dump as the file "
                    + "it was given and does not decompress inside the analysis job: a small compressed "
                    + "file can expand without bound, and handing a job that much work is not something "
                    + "the upload's size limit can defend against. Decompress it first (gzip -d on the "
                    + "file, or jcmd without -gz) and upload the resulting .hprof.";
            case FLIGHT_RECORDER -> "This is a JDK Flight Recorder recording (its header is 'FLR'), not a "
                    + "heap dump. Upload it as a JFR_RECORDING: a recording is a timeline of events over "
                    + "a window, which is a different kind of evidence from a snapshot of what the heap "
                    + "held at one instant.";
            case CLASS_HISTOGRAM -> "This is the text output of a class histogram, either "
                    + "'jmap -histo' or 'jcmd <pid> GC.class_histogram', not a heap dump. A histogram is "
                    + "a summary the JVM printed: a count and an estimated byte total per class, and "
                    + "nothing else — no individual objects, no references and no GC roots, so there is "
                    + "no object graph behind those numbers and nothing RepoPilot could verify. Collect a "
                    + "real dump instead: 'jcmd <pid> GC.heap_dump heap.hprof' (or 'jmap "
                    + "-dump:format=b,file=heap.hprof <pid>'), then upload that file.";
            case THREAD_DUMP_TEXT -> "This is a thread dump (it contains 'Full thread dump'), not a heap "
                    + "dump. Upload it as a THREAD_DUMP: it records what each thread was doing at one "
                    + "instant, not what the heap held.";
            case EMPTY -> "The uploaded file is empty, so there is nothing to read.";
            case UNKNOWN -> "This file does not begin with the HPROF header ('JAVA PROFILE ...'), so it is "
                    + "not a Java heap dump. RepoPilot reads the HPROF format that jmap, jcmd "
                    + "GC.heap_dump and -XX:+HeapDumpOnOutOfMemoryError write"
                    + (found == null ? "." : "; this file begins with " + found + ".");
        };
    }
}
