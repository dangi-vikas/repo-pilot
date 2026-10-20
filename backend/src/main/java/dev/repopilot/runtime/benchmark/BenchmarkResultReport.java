package dev.repopilot.runtime.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What a JMH result file says, as parsed.
 *
 * <p>A benchmark result is a statement by whoever ran the benchmark, not by RepoPilot. Everything in
 * this record was computed outside this process — the score, the error, the confidence interval and
 * the percentiles are the harness's arithmetic, and the per-iteration values are its observations.
 * The reader's whole job is to repeat them without changing them, which is why the mapper stores
 * them with {@code AS_REPORTED} rather than pretending to have reduced anything.
 *
 * <h2>Why the numbers are boxed</h2>
 * JMH writes the JSON string {@code "NaN"} (verified against 1.37: it is a quoted string, not a bare
 * {@code NaN} token, so the file is valid JSON) when it cannot compute a value — most often the
 * error and the confidence interval of a single-fork run, because one fork has nothing to compare
 * against. {@code null} here means exactly that: the file stated no number. A {@code 0} would be a
 * claim the file never made, and "an error of zero" is the strongest possible claim of precision.
 *
 * <h2>What is deliberately not here</h2>
 * The JVM's command line ({@code jvmArgs}), the path to the JVM binary ({@code jvm}) and the raw
 * sample arrays are read for their presence and their size but are not carried into any stored row:
 * see {@code BenchmarkReader} for why.
 */
public record BenchmarkResultReport(FileFacts file, List<Entry> entries) {

    /**
     * What the file as a whole states, for the artefact's provenance block.
     *
     * @param jvmArgCount how many JVM arguments the harness recorded, without recording the arguments
     * @param entries     how many result entries were read
     * @param truncated   whether the reader stopped at its cap, so the counts describe part of the file
     */
    public record FileFacts(String jmhVersion,
                            String vmName,
                            String vmVersion,
                            String jdkVersion,
                            int jvmArgCount,
                            int entries,
                            boolean truncated,
                            List<String> modes,
                            List<String> parameterNames,
                            int entriesWithoutRawData,
                            int entriesWithoutErrorEstimate,
                            List<String> warmupTimes,
                            List<String> measurementTimes) {

        /** Whether any entry carried the per-iteration values a score can be checked against. */
        public boolean anyRawDataPresent() {
            return entries > entriesWithoutRawData;
        }
    }

    /**
     * One result entry: one benchmark method, in one mode, with one set of parameters.
     *
     * <p>{@code mode} is the file's own token ({@code thrpt}, {@code avgt}, {@code sample},
     * {@code ss}) and is kept verbatim, because the mode decides what the number means and the file
     * is the authority on which one ran.
     *
     * @param parameters declared parameters, in file order; they are part of what distinguishes two
     *                   otherwise identical entries, so they travel with the entry's scope
     */
    public record Entry(String benchmark,
                        String mode,
                        Integer threads,
                        Integer forks,
                        Integer warmupIterations,
                        Integer measurementIterations,
                        String warmupTime,
                        String measurementTime,
                        Map<String, String> parameters,
                        Metric primary,
                        List<NamedMetric> secondary) {

        /**
         * What the values of this entry are over: the benchmark method, its mode's parameters, and
         * nothing else.
         *
         * <p>The parameters are in the scope because two entries of the same method differing only
         * by parameters are different measurements; without them the two scores would share a key
         * and a scope and be indistinguishable. The mode is not in the scope because it is in the
         * metric key, where it belongs: it changes the unit.
         */
        public String scope() {
            if (parameters.isEmpty()) {
                return benchmark;
            }
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            parameters.forEach((name, value) -> joiner.add(name + "=" + value));
            return benchmark + " " + joiner;
        }

        /** The number of secondary metrics the entry stated, whether or not they were ingested. */
        public int secondaryCount() {
            return secondary.size();
        }
    }

    /** A named secondary metric, e.g. JMH's sample-mode {@code p99.0}. */
    public record NamedMetric(String name, Metric metric) {
    }

    /**
     * One metric as the file states it.
     *
     * @param scoreUnit      the file's own unit ({@code ops/us}, {@code us/op}, …); a metric with no
     *                       unit is refused by the reader rather than stored as a bare number
     * @param percentiles    the file's own keys ({@code 0.0} … {@code 100.0}) to their values, in file
     *                       order and with the keys verbatim: RepoPilot did not compute these and will
     *                       not relabel them
     * @param rawData        per-iteration values, one inner list per fork, exactly as nested in the
     *                       file; empty when the mode records a histogram instead
     * @param histogramBuckets how many buckets the raw-sample histograms hold in total, 0 when absent
     * @param histogramCount how many histograms the file records (JMH writes one per measurement
     *                       iteration), 0 when absent
     */
    public record Metric(Double score,
                         String scoreUnit,
                         Double scoreError,
                         Double confidenceLower,
                         Double confidenceUpper,
                         Map<String, Double> percentiles,
                         List<List<Double>> rawData,
                         int histogramBuckets,
                         int histogramCount) {

        /** The per-iteration values, flattened across forks. */
        public List<Double> flatRawData() {
            return rawData.stream().flatMap(List::stream).toList();
        }

        /**
         * How many per-iteration values the file records.
         *
         * <p>Not the same as the configured measurement iteration count: the file states both, and
         * the two disagree whenever a fork was dropped or a value was reported as no number.
         */
        public int recordedIterations() {
            return rawData.stream().mapToInt(List::size).sum();
        }

        /**
         * The mean of the values the file records, when it records any.
         *
         * <p>This is the check the score can be held against: in throughput and average-time modes JMH
         * writes its own per-iteration values, and the stated score is their mean. Where the file
         * records no values — sample mode writes a histogram instead — there is nothing to check
         * against, and the absence is itself reported rather than smoothed over.
         */
        public OptionalDouble meanOfRecordedIterations() {
            List<Double> values = flatRawData();
            if (values.isEmpty()) {
                return OptionalDouble.empty();
            }
            double total = 0;
            for (double value : values) {
                total += value;
            }
            return OptionalDouble.of(total / values.size());
        }

        /** Whether the file stated an error estimate for this score. */
        public boolean errorReported() {
            return scoreError != null;
        }

        /** Whether the file stated a confidence interval for this score. */
        public boolean confidenceReported() {
            return confidenceLower != null && confidenceUpper != null;
        }

        public Optional<String> unit() {
            return Optional.ofNullable(scoreUnit).filter(unit -> !unit.isBlank());
        }

        /**
         * Whether the stated score equals the mean of the values the file records.
         *
         * <p>Compared with a relative tolerance rather than for exact equality: the two numbers are
         * separate serialisations of the same arithmetic, and a JSON round trip is allowed to land a
         * few units in the last place away. The tolerance is tight enough that a genuinely different
         * number fails it.
         */
        public Optional<Boolean> scoreMatchesRecordedIterations() {
            if (score == null) {
                return Optional.empty();
            }
            OptionalDouble mean = meanOfRecordedIterations();
            if (mean.isEmpty()) {
                return Optional.empty();
            }
            double difference = Math.abs(mean.getAsDouble() - score);
            double tolerance = 1e-9 * Math.max(1.0, Math.abs(score));
            return Optional.of(difference <= tolerance);
        }
    }

    /** Whether the report carries no measurements at all, which the summary has to say out loud. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The distinct modes in file order, for a report or a test to assert against. */
    public static List<String> distinctModes(List<Entry> entries) {
        return entries.stream().map(Entry::mode).filter(Objects::nonNull).distinct().toList();
    }

    /** The distinct parameter names in file order. */
    public static List<String> distinctParameterNames(List<Entry> entries) {
        return entries.stream()
                .flatMap(entry -> entry.parameters().keySet().stream())
                .distinct()
                .toList();
    }

    /** The distinct non-blank values of a field across entries, in file order. */
    public static List<String> distinctValues(List<Entry> entries, java.util.function.Function<Entry, String> field) {
        return entries.stream()
                .map(field)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    /** A set of counts seen across entries, for prose that reports a range rather than one number. */
    public static Set<Integer> distinctCounts(List<Entry> entries,
                                              java.util.function.Function<Entry, Integer> field) {
        return entries.stream()
                .map(field)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
