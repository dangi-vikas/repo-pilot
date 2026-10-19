package dev.repopilot.runtime.threaddump;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link ThreadDumpReport} into the measurements RepoPilot stores.
 *
 * <p>A pure function, and the place where every judgement about a dump is recorded. Three rules
 * shape the whole set:
 *
 * <ul>
 *   <li><b>A dump is one instant.</b> Nothing here is a rate, a duration or a trend. "Three threads
 *       are waiting to enter one monitor" is true of the moment the dump was taken and says nothing
 *       about the second before or after it, so every row says which instant it describes.</li>
 *   <li><b>No thresholds and no severity.</b> A deep stack, a long queue of waiters and a stale
 *       monitor are all facts; whether any of them matters is a judgement about a workload, and
 *       RepoPilot makes those only where it can state the policy behind them. None is stated
 *       here.</li>
 *   <li><b>Deadlocks are reported, not searched for.</b> The count comes from the section the JVM's
 *       own detector printed, and the cycles are grouped from the edges in it. RepoPilot does not
 *       scan for lock-order cycles itself, so the number is exactly what the dump said and the row
 *       names its source. A dump with no such section means the detector printed nothing, which is
 *       not the same claim as "this process has no deadlock of any kind".</li>
 * </ul>
 *
 * <p>Every metric here is a count of something the dump reports, so every kind is
 * {@link MetricKind#COUNTER} and the aggregation says whether the number is a total over the dump or
 * a maximum over the threads it lists.
 */
public final class ThreadDumpMeasurementMapper {

    /**
     * The namespace every key carries.
     *
     * <p>{@code jfr} for a recording and {@code threaddump} for a dump, on the same principle: a
     * metric key states which reader produced it, and the UI groups by the segment after it.
     */
    public static final String NAMESPACE = "threaddump";

    /** How many contended monitors the table lists. The observed count is reported alongside. */
    public static final int TOP_N = 20;

    private ThreadDumpMeasurementMapper() {
    }

    public static List<MeasurementDraft> toMeasurements(ThreadDumpReport report) {
        List<MeasurementDraft> out = new ArrayList<>();
        census(report, out);
        states(report, out);
        deadlocks(report, out);
        contention(report, out);
        stacks(report, out);
        return List.copyOf(out);
    }

    private static void census(ThreadDumpReport report, List<MeasurementDraft> out) {
        int threads = report.threads().size();
        int javaThreads = (int) report.javaThreadCount();
        Map<String, Object> counted = new LinkedHashMap<>();
        counted.put("basis", "one block per thread the dump printed");
        counted.put("meaning", "threads the JVM reported at the instant of the dump. Its own runtime threads "
                + "carry no java.lang.Thread.State line, so they are counted apart from the Java threads "
                + "rather than assumed to be in some state");
        out.add(MeasurementDraft.of(key("threads.count"), MetricKind.COUNTER, MeasurementAggregation.COUNT,
                threads, "count", threads, null, counted));

        Map<String, Object> java = new LinkedHashMap<>();
        java.put("basis", "thread blocks that carry a java.lang.Thread.State line");
        out.add(MeasurementDraft.of(key("threads.java.count"), MetricKind.COUNTER, MeasurementAggregation.COUNT,
                javaThreads, "count", javaThreads, null, java));

        int other = (int) report.threadsWithoutJavaState();
        if (other > 0) {
            Map<String, Object> without = new LinkedHashMap<>();
            without.put("basis", "thread blocks with no java.lang.Thread.State line");
            without.put("meaning", "threads the JVM reported without a Java-level state: its VM, GC, compiler "
                    + "and service threads, and any native thread attached to the VM without one");
            out.add(MeasurementDraft.of(key("threads.nonjava.count"), MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, other, "count", other, null, without));
        }
    }

    private static void states(ThreadDumpReport report, List<MeasurementDraft> out) {
        Map<Thread.State, Long> counts = report.stateCounts();
        Map<Thread.State, Map<String, Long>> details = report.stateDetails();
        for (Thread.State state : ThreadDumpReport.REPORTABLE_STATES) {
            long count = counts.getOrDefault(state, 0L);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basis", "the java.lang.Thread.State line in each thread block");
            attributes.put("meaning", "threads the dump reports in this state at the instant it was taken");
            if (state == Thread.State.RUNNABLE) {
                // A verifiable property of the dump format, and one that is easy to misread: the
                // Reactor IO threads of a quiet server are RUNNABLE while parked in a selector wait.
                attributes.put("caveat", "HotSpot reports a thread blocked in a native call — a socket read, "
                        + "a selector wait — as RUNNABLE, so this is not a count of threads using a CPU");
            }
            Map<String, Long> seen = details.get(state);
            if (seen != null && !seen.isEmpty()) {
                attributes.put("details", seen);
            }
            out.add(MeasurementDraft.of(key("threads.state.count"), MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, count, "count", (int) count, state.name(), attributes));
        }

        if (report.unknownStateCount() > 0) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basis", "thread blocks whose line after java.lang.Thread.State: is not a state this "
                    + "build knows");
            attributes.put("meaning", "threads that were counted as Java threads because the dump stated a state "
                    + "for them, but whose state is not one of the four a live thread can be in. Reported rather "
                    + "than folded into another state");
            out.add(MeasurementDraft.of(key("threads.unknownState.count"), MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, report.unknownStateCount(), "count",
                    (int) report.unknownStateCount(), null, attributes));
        }
    }

    private static void deadlocks(ThreadDumpReport report, List<MeasurementDraft> out) {
        List<ThreadDumpReport.DeadlockCycle> cycles = report.deadlocks();
        Integer stated = report.statedDeadlockCount();
        int count = stated != null ? stated : cycles.size();

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("source", stated != null
                ? "the count printed in the dump's own deadlock section (\"Found N deadlock\")"
                : "cycles RepoPilot grouped from the dump's deadlock section; the dump printed no count line");
        attributes.put("meaning", "deadlock cycles the JVM's own detector reported at the instant of the dump. "
                + "No section means the detector printed none, which is not the same claim as the process "
                + "having no deadlock of any kind, and RepoPilot does not search for cycles itself");
        if (!cycles.isEmpty()) {
            attributes.put("cycles", cycles(cycles));
        }
        out.add(MeasurementDraft.of(key("threads.deadlock.count"), MetricKind.COUNTER,
                MeasurementAggregation.COUNT, count, "count", count, null, attributes));

        long deadlocked = report.deadlockedThreadCount();
        if (deadlocked > 0) {
            Map<String, Object> threads = new LinkedHashMap<>();
            threads.put("basis", "the thread names named by the dump's deadlock section, counted once each");
            threads.put("threads", report.deadlockedThreadNames());
            out.add(MeasurementDraft.of(key("threads.deadlocked.count"), MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, deadlocked, "count", (int) deadlocked, null, threads));
        }
    }

    private static void contention(ThreadDumpReport report, List<MeasurementDraft> out) {
        List<ThreadDumpReport.ContendedMonitor> contended = contended(report);
        Map<String, Object> counted = new LinkedHashMap<>();
        counted.put("basis", "distinct object monitors at least one thread is waiting to enter "
                + "(\"- waiting to lock <0x…>\")");
        counted.put("meaning", "monitors that had at least one thread waiting for them at the instant of the "
                + "dump. A monitor released between two lines of the dump can be missed: this is one instant, "
                + "not a window");
        out.add(MeasurementDraft.of(key("monitors.contended.count"), MetricKind.COUNTER,
                MeasurementAggregation.COUNT, contended.size(), "count", contended.size(), null, counted));
        if (contended.isEmpty()) {
            return;
        }

        int waiters = contended.stream().mapToInt(monitor -> monitor.waiters().size()).sum();
        ThreadDumpReport.ContendedMonitor worst = contended.get(0);
        Map<String, Object> worstAttributes = new LinkedHashMap<>();
        worstAttributes.put("basis", "threads waiting to enter this monitor at the instant of the dump");
        worstAttributes.put("meaning", "the single most-waited-for monitor in the dump");
        worstAttributes.put("ownerThread", worst.owner() == null
                ? "no thread in the dump reports holding this monitor"
                : worst.owner());
        worstAttributes.put("waiters", worst.waiters());
        if (contended.size() > 1 && contended.get(1).waiters().size() == worst.waiters().size()) {
            worstAttributes.put("caveat", "more than one monitor has this many waiters; the first in the "
                    + "dump's order is reported, and the table lists them all");
        }
        out.add(MeasurementDraft.of(key("monitors.waiters.max"), MetricKind.COUNTER,
                MeasurementAggregation.MAX, worst.waiters().size(), "count", contended.size(),
                scopeOf(worst), worstAttributes));

        out.add(MeasurementDraft.count(key("monitors.waiters.observed"), contended.size(), contended.size(), null));
        int reported = Math.min(contended.size(), TOP_N);
        out.add(MeasurementDraft.count(key("monitors.waiters.reported"), reported, reported, null));
        for (ThreadDumpReport.ContendedMonitor monitor : contended.subList(0, reported)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basis", "threads waiting to enter this monitor at the instant of the dump");
            attributes.put("meaning", "a monitor at least one thread was waiting for");
            attributes.put("ownerThread", monitor.owner() == null
                    ? "no thread in the dump reports holding this monitor"
                    : monitor.owner());
            attributes.put("share", round(monitor.waiters().size() / (double) waiters));
            attributes.put("shareBasis", waiters + " thread(s) waiting to enter a monitor in this dump");
            out.add(MeasurementDraft.of(key("monitors.waiters"), MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, monitor.waiters().size(), "count",
                    monitor.waiters().size(), scopeOf(monitor), attributes));
        }
    }

    private static void stacks(ThreadDumpReport report, List<MeasurementDraft> out) {
        List<ThreadDumpReport.DumpThread> javaThreads = report.threads().stream()
                .filter(ThreadDumpReport.DumpThread::hasJavaState)
                .toList();
        if (javaThreads.isEmpty()) {
            return;
        }

        ThreadDumpReport.DumpThread deepest = javaThreads.stream()
                .max(Comparator.comparingInt(ThreadDumpReport.DumpThread::stackDepth)
                        .thenComparing(ThreadDumpReport.DumpThread::name, Comparator.reverseOrder()))
                .orElseThrow();
        Map<String, Object> depthAttributes = new LinkedHashMap<>();
        depthAttributes.put("basis", "frames printed per thread block");
        depthAttributes.put("meaning", "the deepest stack in the dump. Depth is a fact about one instant: "
                + "recursion, framework layering and the JVM's own frames all contribute, and a deep stack is "
                + "not by itself a finding");
        out.add(MeasurementDraft.of(key("threads.stack.depth.max"), MetricKind.COUNTER,
                MeasurementAggregation.MAX, deepest.stackDepth(), "count", javaThreads.size(),
                deepest.name(), depthAttributes));

        ThreadDumpReport.DumpThread mostLocks = javaThreads.stream()
                .max(Comparator.comparingInt((ThreadDumpReport.DumpThread thread) -> thread.heldMonitors().size())
                        .thenComparing(ThreadDumpReport.DumpThread::name, Comparator.reverseOrder()))
                .orElseThrow();
        Map<String, Object> heldAttributes = new LinkedHashMap<>();
        heldAttributes.put("basis", "monitors each thread block reports holding (\"- locked <0x…>\")");
        heldAttributes.put("meaning", "the most monitors one thread holds. Java threads only: the JVM's own "
                + "runtime threads are not attributed monitor ownership in a dump");
        out.add(MeasurementDraft.of(key("monitors.held.max"), MetricKind.COUNTER,
                MeasurementAggregation.MAX, mostLocks.heldMonitors().size(), "count", javaThreads.size(),
                mostLocks.heldMonitors().isEmpty() ? null : mostLocks.name(), heldAttributes));
    }

    /** Contended monitors, most waiters first, in a deterministic order. */
    private static List<ThreadDumpReport.ContendedMonitor> contended(ThreadDumpReport report) {
        return report.contendedMonitors().values().stream()
                .sorted(Comparator.comparingInt((ThreadDumpReport.ContendedMonitor monitor) -> monitor.waiters().size())
                        .reversed()
                        .thenComparing(monitor -> monitor.type() == null ? "" : monitor.type())
                        .thenComparing(monitor -> monitor.owner() == null ? "" : monitor.owner()))
                .toList();
    }

    /**
     * How a monitor is named in a scope: the type as the dump printed it, and the holder when one is
     * reported.
     *
     * <p>The address is deliberately not used. It is meaningful inside one dump and nowhere else, so a
     * scope built from it would look like an identifier while identifying nothing a reader could
     * follow.
     */
    private static String scopeOf(ThreadDumpReport.ContendedMonitor monitor) {
        String type = monitor.type() == null ? "an untyped monitor" : monitor.type();
        return monitor.owner() == null ? type + " (no holder shown)" : type + " held by \"" + monitor.owner() + "\"";
    }

    /** The deadlock section as plain data, so the UI can show the cycle rather than only its size. */
    private static List<Map<String, Object>> cycles(List<ThreadDumpReport.DeadlockCycle> cycles) {
        List<Map<String, Object>> described = new ArrayList<>(cycles.size());
        for (ThreadDumpReport.DeadlockCycle cycle : cycles) {
            List<Map<String, Object>> edges = new ArrayList<>(cycle.edges().size());
            for (ThreadDumpReport.DeadlockEdge edge : cycle.edges()) {
                Map<String, Object> described1 = new LinkedHashMap<>();
                described1.put("thread", edge.thread());
                described1.put("waitsToEnter", edge.monitorType());
                described1.put("heldBy", edge.heldBy());
                edges.add(described1);
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("threads", edges.stream().map(edge -> edge.get("thread")).toList());
            one.put("edges", edges);
            described.add(one);
        }
        return described;
    }

    private static String key(String leaf) {
        return NAMESPACE + "." + leaf;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
