package dev.repopilot.runtime.benchmark;

import java.nio.charset.StandardCharsets;

/**
 * The shapes a file that claims to be a benchmark result can have.
 *
 * <p>JMH writes four of them, and only one carries the numbers the harness actually computed:
 *
 * <ul>
 *   <li>{@code -rf json} writes an array with one object per benchmark, each carrying the stated
 *       score, its error, the confidence interval, the percentiles, the per-iteration values and the
 *       exact configuration. This is {@link #JSON_ARRAY}, and it is the only shape read.</li>
 *   <li>{@code -rf csv} writes the same summary as a quoted comma-separated table. It is readable by
 *       a spreadsheet and lossy for everything not in a column: the percentiles and the
 *       per-iteration values are simply not there.</li>
 *   <li>{@code -rf text}, and the table JMH prints to the console when no result file is asked for,
 *       are formatted for a human: values are rounded for display and the per-iteration values are
 *       absent.</li>
 *   <li>A gzip-compressed file is not a shape of its own; it is one of the above inside a stream
 *       this build will not expand.</li>
 * </ul>
 *
 * <p>So the other shapes are refused by name, with the command that produces the readable one. The
 * difference matters for the same reason it does in the other readers: a benchmark score whose
 * precision was decided by a display formatter is not the score the harness computed, and RepoPilot
 * would be storing a rounded number while presenting it as measured.
 *
 * <p>The classification is a pure function of the file's first bytes, so every real shape is a unit
 * test rather than something only an end-to-end run can check. It is deliberately weaker than the
 * header checks the other readers use, because JSON has no magic number: a file can only be shown to
 * be a JMH result by its structure, and that check happens in {@link BenchmarkReader} where the
 * reason it fails can name the entry that was wrong.
 */
public enum BenchmarkFormat {

    /** An array of result entries, which is what {@code -rf json} writes. Read. */
    JSON_ARRAY,

    /** A single JSON object: a hand-edited extract, or a JSON document of another kind entirely. */
    JSON_OBJECT,

    /** {@code -rf csv}: the summary as a comma-separated table, without the per-iteration values. */
    CSV,

    /** {@code -rf text}, or the console table JMH prints: display-rounded, without per-iteration values. */
    TEXT,

    /** A gzip stream, which this build will not expand. */
    GZIP,

    /** Nothing, or only whitespace. */
    EMPTY,

    /** No benchmark result this build recognises. */
    UNKNOWN;

    /** Enough of the file to classify it. JMH's first entry carries its identifying fields within this. */
    public static final int HEAD_BYTES = 4_096;

    private static final byte GZIP_MAGIC_1 = (byte) 0x1F;
    private static final byte GZIP_MAGIC_2 = (byte) 0x8B;

    /** A byte-order mark, which some writers put in front of a UTF-8 file. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * Classifies a file from its first bytes.
     *
     * <p>Pure over the bytes handed in, so the classification of each real shape is a unit test.
     * The gzip check reads the two-byte magic rather than decoding as text: a compressed stream
     * decoded as UTF-8 is mojibake, and a classification that called it "unknown" would send the
     * operator looking for a problem with their benchmark rather than with their archive.
     *
     * @param head the first bytes of the file, in order
     */
    public static BenchmarkFormat of(byte[] head) {
        if (head.length >= 2 && head[0] == GZIP_MAGIC_1 && head[1] == GZIP_MAGIC_2) {
            return GZIP;
        }
        String text = new String(head, StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length() && (Character.isWhitespace(text.charAt(start))
                || text.charAt(start) == BYTE_ORDER_MARK)) {
            start++;
        }
        if (start == text.length()) {
            return EMPTY;
        }
        char first = text.charAt(start);
        if (first == '[') {
            return JSON_ARRAY;
        }
        if (first == '{') {
            return JSON_OBJECT;
        }
        if (first == '#') {
            // JMH's text output opens with a block of "# JMH version: …" comment lines, and so does
            // the console table, which is the same text.
            return TEXT;
        }
        if (first == '"' || text.startsWith("Benchmark,", start)) {
            // -rf csv writes a quoted header row: "Benchmark","Mode","Threads",…
            return CSV;
        }
        return UNKNOWN;
    }

    /** Whether this shape can be read into measurements. */
    public boolean readable() {
        return this == JSON_ARRAY;
    }

    /**
     * Why this file cannot be read, stated so that the operator can act on it.
     *
     * <p>Names the command that produces a readable result rather than only the one that produced
     * this file, because "your CSV has no per-iteration values in it" is only useful next to "re-run
     * it with {@code -rf json}".
     */
    public static String explain(BenchmarkFormat format) {
        return switch (format) {
            case JSON_ARRAY -> "This is a JMH result file in JSON form, which RepoPilot reads.";
            case JSON_OBJECT ->
                    "This is a JSON object, not the array of results JMH writes: `-rf json` writes one "
                            + "element per benchmark. If this is a single result lifted out of a run, or a JSON "
                            + "document of another kind, re-run the benchmark with `-rf json -rff results.json` "
                            + "and upload that file — RepoPilot will not present part of a result as the whole "
                            + "of one.";
            case CSV ->
                    "This is JMH's CSV output (`-rf csv`). The table has the score in it, but not the "
                            + "per-iteration values, not the percentiles and not the configuration, so a "
                            + "measurement read from it could not be checked against anything. Re-run the "
                            + "benchmark with `-rf json -rff results.json`.";
            case TEXT ->
                    "This is JMH's text output — the table it prints to the console, which `-rf text` also "
                            + "writes. The values in it are rounded by a display formatter and the "
                            + "per-iteration values are not in it at all, so a score read from here is not the "
                            + "score the harness computed. Re-run the benchmark with `-rf json -rff results.json`.";
            case GZIP ->
                    "This file is gzip-compressed, and RepoPilot does not expand an archive it was handed as "
                            + "evidence: decompressing untrusted input inside an analysis job is work the "
                            + "upload size limit cannot bound. Decompress it first (on a Unix-like system "
                            + "`gzip -d results.json.gz`) and upload the JSON.";
            case EMPTY -> "The file is empty, or contains only whitespace.";
            case UNKNOWN ->
                    "This file is not a benchmark result in a form RepoPilot reads. It reads the JSON JMH "
                            + "writes with `-rf json -rff results.json`: an array whose entries carry "
                            + "`jmhVersion`, `benchmark` and `primaryMetric`.";
        };
    }
}
