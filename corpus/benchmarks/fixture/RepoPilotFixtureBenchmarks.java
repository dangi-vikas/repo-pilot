package dev.repopilot.fixture;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The benchmarks whose recorded result RepoPilot's benchmark reader is checked against.
 *
 * <p>Three methods, in three JMH modes, because the three modes put different things in the
 * result file: {@code Throughput} reports a rate per second, {@code AverageTime} reports a
 * duration per operation, and {@code SampleTime} additionally reports percentiles and a raw
 * sample array. A reader that only ever saw one mode would look right and be wrong for the others.
 *
 * <p>The work is deliberately trivial and the annotations are deliberately few: this is a
 * <em>format</em> fixture with a real provenance. The run that produced the checked-in JSON was
 * short on purpose (see that file's header), so none of these numbers are a performance claim
 * about anything.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class RepoPilotFixtureBenchmarks {

    /** Shared state, so the map is built once rather than inside the measured method. */
    @State(Scope.Benchmark)
    public static class Corpus {
        @Param({"16", "256"})
        public int size;

        public Map<String, Integer> map;
        public List<String> keys;

        @Setup
        public void setUp() {
            map = new HashMap<>();
            keys = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String key = "key-" + i;
                keys.add(key);
                map.put(key, i);
            }
        }
    }

    /** A rate: JMH reports operations per microsecond. */
    @Benchmark
    public int mapLookup(Corpus corpus) {
        int total = 0;
        for (String key : corpus.keys) {
            total += corpus.map.get(key);
        }
        return total;
    }

    /** A duration: JMH reports microseconds per operation. */
    @Benchmark
    public int stringConcat(Corpus corpus) {
        StringBuilder builder = new StringBuilder();
        for (String key : corpus.keys) {
            builder.append(key).append(':').append(corpus.map.get(key)).append(';');
        }
        return builder.length();
    }

    /**
     * A sampled mode: JMH reports a score, percentiles and the raw sample array for this one,
     * which is what makes it distinct in the file.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public int linearScan(Corpus corpus) {
        int found = -1;
        for (int i = 0; i < corpus.keys.size(); i++) {
            if (corpus.keys.get(i).endsWith("7")) {
                found = i;
            }
        }
        return found;
    }
}
