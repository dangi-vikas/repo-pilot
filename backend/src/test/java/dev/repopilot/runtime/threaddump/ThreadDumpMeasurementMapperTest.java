package dev.repopilot.runtime.threaddump;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The measurement set a dump produces.
 *
 * <p>Built from a report constructed by hand rather than parsed, so each assertion is about the
 * judgement being made — what is counted, what is reduced, what the caveat says — and not about the
 * parser. The parser has its own tests, against a real dump.
 *
 * <p>The report below is one instant of an eight-thread JVM: five Java threads with states, one of
 * them stating a state this build does not know, two of the JVM's own threads, three threads waiting
 * to enter two monitors, and the JDK's own detector reporting one deadlock cycle.
 */
class ThreadDumpMeasurementMapperTest {

    private static final String OBJECT = "a java.lang.Object";

    @Test
    @DisplayName("counts the threads the dump reports, and says which count is which")
    void countsThreads() {
        Map<String, MeasurementDraft> byKey = measurements();

        assertThat(byKey.get("threaddump.threads.count").value()).isEqualTo(8);
        assertThat(byKey.get("threaddump.threads.count").sampleCount()).isEqualTo(8);
        assertThat(byKey.get("threaddump.threads.java.count").value()).isEqualTo(6);
        assertThat(byKey.get("threaddump.threads.nonjava.count").value()).isEqualTo(2);

        // Every count is a count, states its reduction, and carries a unit: a bare number is not a
        // measurement.
        assertThat(byKey.values()).allSatisfy(draft -> {
            assertThat(draft.metricKey()).startsWith("threaddump.");
            assertThat(draft.metricKey()).doesNotStartWith("jfr.");
            assertThat(draft.kind()).isEqualTo(MetricKind.COUNTER);
            assertThat(draft.unit()).isEqualTo("count");
            assertThat(draft.sampleCount()).isNotNegative();
        });
    }

    @Test
    @DisplayName("reports all four states a live thread can be in, including the empty ones")
    void reportsAllFourStates() {
        Map<String, MeasurementDraft> byKey = measurements();

        // Four rows always, so "0 blocked" is a stated fact rather than an absent one. A missing row
        // would be indistinguishable from a state this reader does not know about.
        assertThat(byKey).containsKeys(
                "threaddump.threads.state.count");
        List<MeasurementDraft> states = stateRows();
        assertThat(states).hasSize(4);
        assertThat(states).extracting(MeasurementDraft::scope)
                .containsExactlyInAnyOrder("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING");
        assertThat(valueOf(states, "RUNNABLE")).isEqualTo(1);
        assertThat(valueOf(states, "BLOCKED")).isEqualTo(3);
        assertThat(valueOf(states, "WAITING")).isEqualTo(1);
        // Nothing was in this state, and the row exists to say so.
        assertThat(valueOf(states, "TIMED_WAITING")).isEqualTo(0);
        assertThat(sampleCountOf(states, "TIMED_WAITING")).isZero();

        // The RUNNABLE row carries the caveat that makes it readable: a thread blocked in a native
        // call is RUNNABLE in HotSpot, so this count is not a count of threads on a CPU.
        assertThat(rowOf(states, "RUNNABLE").attributes().get("caveat").toString())
                .contains("RUNNABLE")
                .contains("not a count of threads using a CPU");
    }

    @Test
    @DisplayName("reports a state it does not know separately rather than folding it into one it does")
    void reportsUnknownStatesSeparately() {
        Map<String, MeasurementDraft> byKey = measurements();

        // The report has six Java threads and five of them are in a known state. The sixth is stated
        // and reported, because quietly dropping it would make the state tally add up to a number
        // that is not the number of Java threads.
        assertThat(byKey.get("threaddump.threads.unknownState.count").value()).isEqualTo(1);
        long stated = stateRows().stream().mapToLong(draft -> (long) draft.value()).sum();
        assertThat(stated + (long) byKey.get("threaddump.threads.unknownState.count").value())
                .isEqualTo((long) byKey.get("threaddump.threads.java.count").value());
    }

    @Test
    @DisplayName("reports the deadlock the JVM's own detector found, as the JVM stated it")
    void reportsDeadlocks() {
        Map<String, MeasurementDraft> byKey = measurements();

        MeasurementDraft deadlocks = byKey.get("threaddump.threads.deadlock.count");
        assertThat(deadlocks.value()).isEqualTo(1);
        // The source is named, because "found 1 deadlock" means something different when RepoPilot
        // searched for it than when the JVM printed it, and only one of those happened here.
        assertThat(deadlocks.attributes().get("source").toString()).contains("Found N deadlock");
        assertThat(deadlocks.attributes().get("meaning").toString()).contains("does not search for cycles");
        // The cycle itself travels with the count, so a UI can show which threads and which monitors
        // rather than only how many.
        assertThat(deadlocks.attributes().get("cycles").toString())
                .contains("deadlock-first-then-second")
                .contains("deadlock-second-then-first");

        assertThat(byKey.get("threaddump.threads.deadlocked.count").value()).isEqualTo(2);
        assertThat(byKey.get("threaddump.threads.deadlocked.count").attributes().get("threads").toString())
                .contains("deadlock-second-then-first");
    }

    @Test
    @DisplayName("reports contention by monitor, with the holder when the dump names one")
    void reportsContention() {
        Map<String, MeasurementDraft> byKey = measurements();

        assertThat(byKey.get("threaddump.monitors.contended.count").value()).isEqualTo(2);

        // The headline: the monitor with the most waiters, and who holds it. Here the dump shows no
        // thread holding it, which is reported as exactly that rather than as an absent attribute.
        MeasurementDraft most = byKey.get("threaddump.monitors.waiters.max");
        assertThat(most.value()).isEqualTo(2);
        assertThat(most.aggregation()).isEqualTo(MeasurementAggregation.MAX);
        assertThat(most.sampleCount()).isEqualTo(2);
        assertThat(most.scope()).isEqualTo(OBJECT + " (no holder shown)");
        assertThat(most.attributes().get("ownerThread").toString())
                .isEqualTo("no thread in the dump reports holding this monitor");

        // The table: one row per contended monitor, capped, with the observed and reported counts so
        // a truncated list cannot be mistaken for the whole picture.
        assertThat(byKey.get("threaddump.monitors.waiters.observed").value()).isEqualTo(2);
        assertThat(byKey.get("threaddump.monitors.waiters.reported").value()).isEqualTo(2);
        List<MeasurementDraft> waiters = rows("threaddump.monitors.waiters");
        assertThat(waiters).hasSize(2);
        assertThat(waiters.get(0).value()).isEqualTo(2);
        assertThat(waiters.get(1).value()).isEqualTo(1);
        assertThat(waiters.get(1).scope()).isEqualTo(OBJECT + " held by \"worker-a\"");
        // Shares are rounded for storage, so they are compared as a value with a tolerance rather
        // than as the exact fraction they were derived from.
        assertThat((Double) waiters.get(0).attributes().get("share"))
                .isCloseTo(2 / 3.0, within(0.0001));
        assertThat(waiters.get(1).attributes().get("shareBasis").toString())
                .isEqualTo("3 thread(s) waiting to enter a monitor in this dump");
    }

    @Test
    @DisplayName("reports the deepest stack and the most-held monitors as maxima over the Java threads")
    void reportsExtremes() {
        Map<String, MeasurementDraft> byKey = measurements();

        MeasurementDraft deepest = byKey.get("threaddump.threads.stack.depth.max");
        assertThat(deepest.value()).isEqualTo(12);
        assertThat(deepest.aggregation()).isEqualTo(MeasurementAggregation.MAX);
        assertThat(deepest.scope()).isEqualTo("main");
        // The sample is the Java threads the dump listed, and says so, because a maximum is only
        // meaningful next to what it was taken over.
        assertThat(deepest.sampleCount()).isEqualTo(6);
        assertThat(deepest.attributes().get("meaning").toString()).contains("not by itself a finding");

        MeasurementDraft held = byKey.get("threaddump.monitors.held.max");
        assertThat(held.value()).isEqualTo(1);
        assertThat(held.scope()).isEqualTo("worker-a");
        assertThat(held.sampleCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("says 'no contention' rather than staying silent when nothing is waiting")
    void reportsAnIdleJvm() {
        ThreadDumpReport quiet = new ThreadDumpReport(
                new ThreadDumpReport.Provenance("Java HotSpot(TM) 64-Bit Server VM", "21.0.5+9-LTS-239",
                        "mixed mode, sharing", "2026-10-19 10:04:53", 1L, 1),
                List.of(new ThreadDumpReport.DumpThread("main", "RUNNABLE", 3, List.of(), List.of())),
                List.of(),
                null);

        Map<String, MeasurementDraft> byKey = draftsByKey(quiet);

        // A dump with no deadlock section and no waiting threads still has to say both things: an
        // absent measurement would be a question left open by the report.
        assertThat(byKey.get("threaddump.threads.deadlock.count").value()).isZero();
        assertThat(byKey.get("threaddump.threads.deadlock.count").attributes().get("source").toString())
                .contains("no count line");
        assertThat(byKey.get("threaddump.monitors.contended.count").value()).isZero();
        // Nothing to name, so the detail rows do not exist — but the counts do.
        assertThat(byKey).doesNotContainKeys("threaddump.threads.deadlocked.count",
                "threaddump.monitors.waiters.max",
                "threaddump.monitors.waiters",
                "threaddump.threads.nonjava.count");
        assertThat(byKey.get("threaddump.threads.state.count").value()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, MeasurementDraft> measurements() {
        return draftsByKey(report());
    }

    private static Map<String, MeasurementDraft> draftsByKey(ThreadDumpReport report) {
        return ThreadDumpMeasurementMapper.toMeasurements(report).stream()
                .collect(Collectors.toMap(MeasurementDraft::metricKey, Function.identity(),
                        (first, second) -> first, java.util.LinkedHashMap::new));
    }

    private static List<MeasurementDraft> rows(String metricKey) {
        return ThreadDumpMeasurementMapper.toMeasurements(report()).stream()
                .filter(draft -> draft.metricKey().equals(metricKey))
                .toList();
    }

    private static List<MeasurementDraft> stateRows() {
        return rows("threaddump.threads.state.count");
    }

    private static MeasurementDraft rowOf(List<MeasurementDraft> rows, String scope) {
        return rows.stream().filter(draft -> scope.equals(draft.scope())).findFirst().orElseThrow();
    }

    private static long valueOf(List<MeasurementDraft> rows, String scope) {
        return (long) rowOf(rows, scope).value();
    }

    private static int sampleCountOf(List<MeasurementDraft> rows, String scope) {
        return rowOf(rows, scope).sampleCount();
    }

    /**
     * The eight-thread instant described in the class comment.
     *
     * <p>One thread's state is deliberately nonsense: it stands for a JVM that states a thread's
     * state in words this build does not know, which is the case the report has to keep separate from
     * "this thread was given no state at all".
     */
    private static ThreadDumpReport report() {
        ThreadDumpReport.MonitorRef first = new ThreadDumpReport.MonitorRef("0x000000071472a090", OBJECT);
        ThreadDumpReport.MonitorRef second = new ThreadDumpReport.MonitorRef("0x000000071472a080", OBJECT);
        ThreadDumpReport.MonitorRef other = new ThreadDumpReport.MonitorRef("0x000000071472a0f0", OBJECT);

        return new ThreadDumpReport(
                new ThreadDumpReport.Provenance("Java HotSpot(TM) 64-Bit Server VM", "21.0.5+9-LTS-239",
                        "mixed mode, sharing", "2026-10-19 10:04:53", 23720L, 13),
                List.of(
                        new ThreadDumpReport.DumpThread("main", "RUNNABLE", 12, List.of(), List.of()),
                        new ThreadDumpReport.DumpThread("pool-1-thread-1", "WAITING (parking)", 5,
                                List.of(), List.of()),
                        new ThreadDumpReport.DumpThread("worker-a", "BLOCKED (on object monitor)", 4,
                                List.of(second), List.of(first)),
                        new ThreadDumpReport.DumpThread("worker-b", "BLOCKED (on object monitor)", 4,
                                List.of(other), List.of(first)),
                        new ThreadDumpReport.DumpThread("worker-c", "BLOCKED (on object monitor)", 2,
                                List.of(), List.of(second)),
                        new ThreadDumpReport.DumpThread("odd", "FAILING", 1, List.of(), List.of()),
                        new ThreadDumpReport.DumpThread("VM Thread", null, 2, List.of(), List.of()),
                        new ThreadDumpReport.DumpThread("GC Thread#0", null, 0, List.of(), List.of())),
                List.of(new ThreadDumpReport.DeadlockCycle(List.of(
                        new ThreadDumpReport.DeadlockEdge("deadlock-first-then-second", OBJECT,
                                "deadlock-second-then-first"),
                        new ThreadDumpReport.DeadlockEdge("deadlock-second-then-first", OBJECT,
                                "deadlock-first-then-second")))),
                1);
    }
}
