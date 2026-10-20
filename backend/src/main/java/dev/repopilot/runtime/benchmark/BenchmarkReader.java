package dev.repopilot.runtime.benchmark;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JMH result file.
 *
 * <p>Streaming, one entry at a time: a result file for a large benchmark suite is tens of megabytes
 * of nested arrays, and a reader that materialised the whole document would hold all of it at once
 * to produce a few hundred numbers. Each entry is parsed on its own, converted, and dropped.
 *
 * <h2>What is read and what is refused</h2>
 * The file must be the array {@code -rf json} writes. Anything else is refused with the reason and
 * the command that produces the readable form — see {@link BenchmarkFormat}. The structural checks
 * are here rather than at upload because JSON has no header to check: a file can only be shown to be
 * a JMH result by looking inside it, and the refusal has to be able to name the entry that was
 * wrong. An entry with no {@code benchmark}, no {@code primaryMetric} or no {@code scoreUnit} is
 * refused rather than stored with blanks: a number whose subject is unknown is not evidence, and a
 * bare number is not a measurement.
 *
 * <h2>What is read but never stored</h2>
 * Two fields in the file describe the machine and the invocation rather than the benchmark, and
 * neither is carried into anything RepoPilot persists:
 *
 * <ul>
 *   <li><b>{@code jvmArgs}.</b> JMH copies the JVM command line of the run into the result. A command
 *       line is where credentials end up — {@code -Ddb.password=…}, a token in a system property —
 *       and it arrives incidentally, because the harness was configured to record it rather than
 *       because anyone chose to publish it. This is the same rule the JFR reader applies to the
 *       recording's own command-line events, applied to the second format that carries one. The
 *       <em>number</em> of arguments is kept: it tells a reader that the run was configured at all,
 *       without reproducing a value nobody asked to have stored.</li>
 *   <li><b>{@code jvm}.</b> The path to the JVM binary. It is a filesystem path, so it can contain a
 *       user name and a directory layout, and it says nothing the file's {@code vmName},
 *       {@code jvmVersion} and {@code jdkVersion} do not already say better.</li>
 * </ul>
 *
 * <p>What is kept is the identity of the benchmark — its name, its mode, and the parameters its
 * author declared. The line is: a value the harness recorded about its own invocation is not
 * RepoPilot's to publish, whereas a value the author wrote to identify the measurement is part of
 * the measurement. The raw per-iteration arrays are kept only long enough to count them and to check
 * the stated score against them; the stored rows carry the reduced values and their counts.
 *
 * <h2>What this reader does not verify</h2>
 * Nothing here re-runs the benchmark, and nothing here compares two entries. A file is one run on one
 * machine; RepoPilot holds no baseline, so it can say what a file states and cannot say that anything
 * got faster.
 */
public final class BenchmarkReader {

    /**
     * Hard cap on entries read from one file.
     *
     * <p>A structural safety limit rather than a policy an operator tunes, in the same spirit as the
     * heap reader's type cap: the measurement row cap is what bounds what is stored, and this bounds
     * what is held in memory while producing it. Reaching it is reported rather than hidden, because
     * the counts either describe the file or describe what was read.
     */
    public static final int MAX_ENTRIES = 10_000;

    private static final String SCORE_UNIT = "scoreUnit";

    private BenchmarkReader() {
    }

    /** Classifies a file from its first bytes, without parsing it. */
    public static BenchmarkFormat detect(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(BenchmarkFormat.HEAD_BYTES);
            return BenchmarkFormat.of(head);
        }
    }

    /**
     * Parses a result file into the facts it states.
     *
     * @param checkpoint called after each entry, so a long file can be cancelled
     * @throws IOException when the file is not a readable result file, or states something RepoPilot
     *                     will not store without being able to name what it belongs to
     */
    public static BenchmarkResultReport read(Path file, ObjectMapper json, Runnable checkpoint) throws IOException {
        List<BenchmarkResultReport.Entry> entries = new ArrayList<>();
        String jmhVersion = null;
        String vmName = null;
        String vmVersion = null;
        String jdkVersion = null;
        int jvmArgCount = 0;
        boolean sawJvmArgs = false;
        boolean truncated = false;

        try (InputStream in = Files.newInputStream(file);
             JsonParser parser = json.getFactory().createParser(in)) {

            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_ARRAY) {
                // Not an array: name the shape and the command that produces a readable one.
                throw new IOException(BenchmarkFormat.explain(detect(file)));
            }

            int index = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                if (token == null) {
                    throw new IOException("The result file ends in the middle of its array: entry " + index
                            + " never closed. A truncated upload is not a shorter benchmark run, so nothing "
                            + "is stored from it.");
                }
                if (token != JsonToken.START_OBJECT) {
                    throw new IOException("Element " + index + " of the result array is a " + token
                            + " rather than an object. JMH writes one object per benchmark, so this file was "
                            + "not written by `-rf json` as it stands.");
                }
                if (index >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                JsonNode node = parser.readValueAsTree();
                entries.add(entry(node, index));

                if (jmhVersion == null) {
                    jmhVersion = text(node, "jmhVersion");
                }
                if (vmName == null) {
                    vmName = text(node, "vmName");
                }
                if (vmVersion == null) {
                    vmVersion = text(node, "vmVersion");
                }
                if (jdkVersion == null) {
                    jdkVersion = text(node, "jdkVersion");
                }
                if (!sawJvmArgs) {
                    JsonNode args = node.get("jvmArgs");
                    if (args != null && args.isArray()) {
                        jvmArgCount = args.size();
                        sawJvmArgs = true;
                    }
                }
                index++;
                checkpoint.run();
            }
        } catch (JsonProcessingException malformed) {
            // Jackson reports a file that stops mid-document with its own message, which names a column
            // and not a cause. The cause is the one worth stating: an upload that ended early is not a
            // shorter benchmark run, so nothing is stored from it.
            throw new IOException("The result file is not readable as JSON, or it ends in the middle of it: "
                    + malformed.getOriginalMessage() + " A truncated upload is not a shorter benchmark run, "
                    + "so nothing is stored from it.", malformed);
        }

        BenchmarkResultReport.FileFacts facts = new BenchmarkResultReport.FileFacts(
                jmhVersion, vmName, vmVersion, jdkVersion, jvmArgCount, entries.size(), truncated,
                BenchmarkResultReport.distinctModes(entries),
                BenchmarkResultReport.distinctParameterNames(entries),
                (int) entries.stream().filter(entry -> entry.primary().rawData().isEmpty()).count(),
                (int) entries.stream().filter(entry -> !entry.primary().errorReported()).count(),
                BenchmarkResultReport.distinctValues(entries, BenchmarkResultReport.Entry::warmupTime),
                BenchmarkResultReport.distinctValues(entries, BenchmarkResultReport.Entry::measurementTime));
        return new BenchmarkResultReport(facts, List.copyOf(entries));
    }

    /**
     * One entry, refusing what cannot be stored with its subject attached.
     *
     * @param index the entry's position in the file, used in refusals so the operator can find it
     */
    private static BenchmarkResultReport.Entry entry(JsonNode node, int index) throws IOException {
        String benchmark = text(node, "benchmark");
        if (benchmark == null || benchmark.isBlank()) {
            throw new IOException("Result entry " + index + " has no `benchmark` field, so this file is "
                    + "not the array JMH writes: every entry it writes names the benchmark it measured. "
                    + "Re-run the benchmark with `-rf json -rff results.json` and upload that file.");
        }
        JsonNode primary = node.get("primaryMetric");
        if (primary == null || !primary.isObject()) {
            throw new IOException("Result entry " + index + " (" + benchmark + ") has no `primaryMetric` "
                    + "object. That is the object JMH writes for the number the benchmark produced, and "
                    + "without it there is no measurement in this entry to store.");
        }
        return new BenchmarkResultReport.Entry(
                benchmark,
                orDefault(text(node, "mode"), "unknown"),
                integer(node, "threads"),
                integer(node, "forks"),
                integer(node, "warmupIterations"),
                integer(node, "measurementIterations"),
                text(node, "warmupTime"),
                text(node, "measurementTime"),
                parameters(node.get("params")),
                metric(primary, benchmark),
                secondaries(node.get("secondaryMetrics"), benchmark));
    }

    /**
     * The metrics beside the primary one.
     *
     * <p>JMH puts its sample-mode percentiles here as well as in the primary metric's
     * {@code scorePercentiles}, which is why each one is matched against a percentile name and marked
     * rather than ingested twice: the same numbers under two keys would double a count and invite
     * someone to average them. A secondary metric that is not a percentile is a value the file states
     * about the same run, and it is kept with its own name.
     */
    private static List<BenchmarkResultReport.NamedMetric> secondaries(JsonNode node, String benchmark)
            throws IOException {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<BenchmarkResultReport.NamedMetric> metrics = new ArrayList<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (name == null || name.isBlank() || !field.getValue().isObject()) {
                continue;
            }
            boolean percentile = name.matches("^p\\d+(\\.\\d+)?$");
            metrics.add(new BenchmarkResultReport.NamedMetric(
                    percentile ? percentileMarker(name) : name,
                    metric(field.getValue(), benchmark + " secondary metric '" + name + "'")));
        }
        return List.copyOf(metrics);
    }

    /** Marks a sample-mode percentile secondary metric so the mapper knows it repeats the primary. */
    private static String percentileMarker(String name) {
        return "percentile:" + name;
    }

    /** Whether a secondary metric name is a marker for an already-recorded percentile. */
    static boolean isPercentileMarker(String name) {
        return name != null && name.startsWith("percentile:");
    }

    /** The level a percentile marker names, or null when it is not one. */
    static String percentileMarkerLevel(String name) {
        return isPercentileMarker(name) ? name.substring("percentile:".length()) : null;
    }

    /**
     * One metric, refused when it has no unit.
     *
     * <p>Every JMH metric carries {@code scoreUnit}, and RepoPilot will not store a value without
     * one: {@code 12.9} is not evidence of anything until it is {@code 12.9 us/op}. A metric without
     * a unit is a file RepoPilot does not understand, which is a refusal rather than a guess.
     */
    private static BenchmarkResultReport.Metric metric(JsonNode node, String where) throws IOException {
        String unit = text(node, SCORE_UNIT);
        if (unit == null || unit.isBlank()) {
            throw new IOException("The metric for " + where + " states no `" + SCORE_UNIT + "`, so the "
                    + "value has no unit and RepoPilot will not store a bare number.");
        }
        return new BenchmarkResultReport.Metric(
                number(node, "score"),
                unit,
                number(node, "scoreError"),
                confidence(node, 0),
                confidence(node, 1),
                percentiles(node.get("scorePercentiles")),
                forks(node.get("rawData")),
                histogramBuckets(node.get("rawDataHistogram")),
                histogramCount(node.get("rawDataHistogram")));
    }

    /** A confidence bound, which JMH writes as a two-element array and may state as no number. */
    private static Double confidence(JsonNode node, int position) {
        JsonNode bounds = node.get("scoreConfidence");
        if (bounds == null || !bounds.isArray() || bounds.size() <= position) {
            return null;
        }
        return number(bounds.get(position));
    }

    private static Map<String, Double> percentiles(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Double value = number(field.getValue());
            if (value != null) {
                values.put(field.getKey(), value);
            }
        }
        // Insertion order is kept rather than handed to Map.copyOf: these become rows in the order
        // the file states them, so two analyses of one file produce the same document.
        return values.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(values);
    }

    /**
     * The per-iteration values, one inner list per fork.
     *
     * <p>JMH nests them per fork, and the nesting is the difference between "two iterations" and "two
     * forks of one iteration each". A flat array of numbers is accepted as a single fork rather than
     * counted as one fork per value, because that is what a flat array would mean.
     */
    private static List<List<Double>> forks(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        boolean flat = true;
        for (JsonNode item : node) {
            if (!item.isNumber()) {
                flat = false;
                break;
            }
        }
        List<List<Double>> values = new ArrayList<>();
        if (flat) {
            List<Double> single = numbers(node);
            if (!single.isEmpty()) {
                values.add(single);
            }
            return List.copyOf(values);
        }
        for (JsonNode item : node) {
            if (item.isArray()) {
                values.add(numbers(item));
            }
        }
        return List.copyOf(values);
    }

    private static List<Double> numbers(JsonNode array) {
        List<Double> values = new ArrayList<>();
        for (JsonNode item : array) {
            Double value = number(item);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    /** How many buckets the file's sample-mode histograms hold, whatever their nesting. */
    private static int histogramBuckets(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return 0;
        }
        if (isBucketSeries(node)) {
            return node.size();
        }
        int total = 0;
        for (JsonNode child : node) {
            total += histogramBuckets(child);
        }
        return total;
    }

    /**
     * How many histograms the file records.
     *
     * <p>JMH writes one per fork and one per measurement iteration, so a two-iteration single-fork run
     * carries two. The count travels beside the bucket total because "86 buckets" is not a measurement
     * of anything until a reader knows whether it is one distribution or two.
     */
    private static int histogramCount(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return 0;
        }
        if (isBucketSeries(node)) {
            return 1;
        }
        int total = 0;
        for (JsonNode child : node) {
            total += histogramCount(child);
        }
        return total;
    }

    /** A histogram's innermost array: a list of {@code [value, count]} pairs. */
    private static boolean isBucketSeries(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return false;
        }
        for (JsonNode bucket : node) {
            if (!bucket.isArray() || bucket.size() != 2 || !bucket.get(0).isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> parameters(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            parameters.put(field.getKey(), field.getValue() == null ? "" : field.getValue().asText());
        }
        // Order is part of the scope string an entry is identified by, so it must not depend on a
        // hash's iteration order: the same file has to produce the same scope every time.
        return parameters.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(parameters);
    }

    /**
     * A number, or {@code null} when the file states none.
     *
     * <p>JMH writes {@code "NaN"} as a JSON <em>string</em> — verified against 1.37, where
     * {@code "scoreError" : "NaN"} appears for every single-fork run. Reading that as zero would
     * invent an error estimate of exactly zero, which is a stronger claim than any real measurement
     * makes, so it becomes "the file stated no number" and travels as such.
     */
    private static Double number(JsonNode node, String field) {
        return number(node.get(field));
    }

    private static Double number(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.isEmpty() || text.equalsIgnoreCase("NaN")) {
                return null;
            }
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.intValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
