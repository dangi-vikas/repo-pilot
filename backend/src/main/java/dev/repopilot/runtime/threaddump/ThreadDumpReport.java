package dev.repopilot.runtime.threaddump;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one plain-text thread dump contained, parsed into plain data.
 *
 * <p>A faithful transcription of the parts of the dump that can be measured, and nothing else. The
 * record deliberately has no severity, no "suspicious" flag and no derived judgement: a dump is one
 * instant of one process, and every figure in it is a count of something the JVM wrote down.
 *
 * <p>Everything here is the dump's own words. Thread names, monitor types and the JVM's build token
 * are kept exactly as printed, including the article in {@code "(a java.lang.Object)"}, because a
 * reader that rewrites what a tool said is a reader whose output cannot be checked against the file.
 *
 * @param provenance  the header: which JVM, how it was running, when the dump was taken
 * @param threads     every thread block in the dump, in the order the JVM printed them
 * @param deadlocks   the cycles in the dump's own deadlock section, empty when it printed none
 * @param statedDeadlockCount the number the dump's own summary line states, or null when the section
 *                    is absent or that line could not be read
 */
public record ThreadDumpReport(Provenance provenance,
                               List<DumpThread> threads,
                               List<DeadlockCycle> deadlocks,
                               Integer statedDeadlockCount) {

    /**
     * The dump's header.
     *
     * @param jvmName  the VM's name as printed, e.g. {@code Java HotSpot(TM) 64-Bit Server VM}
     * @param jvmVersion the build token, e.g. {@code 21.0.5+9-LTS-239}
     * @param jvmMode  the words after the build token, e.g. {@code mixed mode, sharing}
     * @param takenAt  the dump's own timestamp line. Local time with no zone: HotSpot prints local
     *                 time there, so it is kept as text and never converted to an instant
     * @param processId the target process id from {@code jcmd}'s banner line, or 0 when absent
     *                 ({@code jstack} writes no such line)
     * @param javaThreadListLength the length of the JVM's own java thread list as it reported it, or
     *                 null when the line is absent. Recorded as reported and never used as a
     *                 denominator: the JVM snapshots that list before it walks the threads, so the
     *                 two can legitimately disagree by a thread
     */
    public record Provenance(String jvmName,
                             String jvmVersion,
                             String jvmMode,
                             String takenAt,
                             long processId,
                             Integer javaThreadListLength) {
    }

    /**
     * One thread block.
     *
     * <p>The state line is kept as raw text and the enum is derived from it, so a dump that states a
     * thread's state in words this build does not know is recorded as exactly that rather than as a
     * thread with no state at all.
     *
     * @param name         the thread's name, as printed. Attacker-influenced text: it comes from the
     *                     application, so it is data and never an instruction
     * @param stateText    the text after {@code java.lang.Thread.State:} with its detail, e.g.
     *                     {@code WAITING (parking)}; null when the block carries no such line, which
     *                     is how the JVM reports its own VM, GC, compiler and service threads, and
     *                     any native thread attached without a Java state
     * @param stackDepth   how many frames the block printed
     * @param heldMonitors monitors this thread holds ({@code - locked})
     * @param waitingToLock monitors this thread is waiting to enter ({@code - waiting to lock})
     */
    public record DumpThread(String name,
                             String stateText,
                             int stackDepth,
                             List<MonitorRef> heldMonitors,
                             List<MonitorRef> waitingToLock) {

        /** The state as the JDK's own enum, or null when the line states something else. */
        public Thread.State state() {
            return stateOf(stateText);
        }

        /** The parenthesised detail, e.g. {@code parking}, or null when there is none. */
        public String stateDetail() {
            return detailOf(stateText);
        }

        /** Whether the dump gave this thread a Java-level state at all. */
        public boolean hasJavaState() {
            return stateText != null;
        }
    }

    /**
     * One {@code <0x…> (a type)} reference from a lock line.
     *
     * @param id   the monitor's address as printed. Kept because a dump prints the same monitor for
     *             every thread that touches it and the address is what ties them together; it means
     *             nothing outside this dump and is never presented as an object identity
     * @param type the type as printed, e.g. {@code a java.lang.Object}, or null when the line did
     *             not carry one
     */
    public record MonitorRef(String id, String type) {
    }

    /** One cycle from the dump's deadlock section, as the edges the JVM printed. */
    public record DeadlockCycle(List<DeadlockEdge> edges) {
    }

    /**
     * One line of a deadlock cycle: {@code thread} waits to lock a {@code monitorType} that
     * {@code heldBy} holds.
     */
    public record DeadlockEdge(String thread, String monitorType, String heldBy) {
    }

    /**
     * The states a thread present in a dump can be in.
     *
     * <p>Four, not six: {@code NEW} and {@code TERMINATED} are in {@link java.lang.Thread.State} but
     * a thread that has not started, or has finished, is not in the JVM's thread list to be dumped.
     * Reporting them as zero would be reporting a fact about RepoPilot's measurement as though it
     * were a fact about the process.
     */
    public static final List<Thread.State> REPORTABLE_STATES = List.of(
            Thread.State.RUNNABLE,
            Thread.State.BLOCKED,
            Thread.State.WAITING,
            Thread.State.TIMED_WAITING);

    /** Thread blocks whose block carried a state line. */
    public long javaThreadCount() {
        return threads.stream().filter(DumpThread::hasJavaState).count();
    }

    /** Thread blocks with no state line: the JVM's own threads. */
    public long threadsWithoutJavaState() {
        return threads.stream().filter(thread -> !thread.hasJavaState()).count();
    }

    /** State lines whose text is not a state this build knows. Normally zero. */
    public long unknownStateCount() {
        return threads.stream().filter(thread -> thread.hasJavaState() && thread.state() == null).count();
    }

    /** How many threads the dump's deadlock section names, counted once each. */
    public long deadlockedThreadCount() {
        return deadlockedThreadNames().size();
    }

    /** Distinct thread names named by the deadlock section, in first-seen order. */
    public List<String> deadlockedThreadNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (DeadlockCycle cycle : deadlocks) {
            for (DeadlockEdge edge : cycle.edges()) {
                names.putIfAbsent(edge.thread(), Boolean.TRUE);
                names.putIfAbsent(edge.heldBy(), Boolean.TRUE);
            }
        }
        return List.copyOf(names.keySet());
    }

    /** How many threads the dump reports in each of the four states a live thread can be in. */
    public Map<Thread.State, Long> stateCounts() {
        Map<Thread.State, Long> counts = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Thread.State state : REPORTABLE_STATES) {
            counts.put(state, 0L);
        }
        for (DumpThread thread : threads) {
            Thread.State state = thread.state();
            if (state != null) {
                counts.merge(state, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** The parenthesised details seen per state, e.g. {@code WAITING -> {parking: 8}}. */
    public Map<Thread.State, Map<String, Long>> stateDetails() {
        Map<Thread.State, Map<String, Long>> details = new TreeMap<>(Comparator.comparing(Enum::name));
        for (DumpThread thread : threads) {
            Thread.State state = thread.state();
            String detail = thread.stateDetail();
            if (state == null || detail == null) {
                continue;
            }
            details.computeIfAbsent(state, key -> new TreeMap<>()).merge(detail, 1L, Long::sum);
        }
        return details;
    }

    /** The state text as an enum, or null when the text names something else. */
    private static Thread.State stateOf(String stateText) {
        if (stateText == null) {
            return null;
        }
        int paren = stateText.indexOf('(');
        String name = (paren < 0 ? stateText : stateText.substring(0, paren)).trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Thread.State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The parenthesised detail of a state line, or null when there is none. */
    private static String detailOf(String stateText) {
        if (stateText == null) {
            return null;
        }
        int open = stateText.indexOf('(');
        int close = stateText.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        return stateText.substring(open + 1, close).trim();
    }

    /** Every monitor that at least one thread is waiting to enter, keyed by what the dump printed. */
    public Map<String, ContendedMonitor> contendedMonitors() {
        Map<String, List<String>> waiters = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();

        for (DumpThread thread : threads) {
            for (MonitorRef monitor : thread.waitingToLock()) {
                waiters.computeIfAbsent(monitor.id(), key -> new ArrayList<>()).add(thread.name());
                if (monitor.type() != null) {
                    types.putIfAbsent(monitor.id(), monitor.type());
                }
            }
        }
        if (waiters.isEmpty()) {
            return Map.of();
        }
        for (DumpThread thread : threads) {
            for (MonitorRef monitor : thread.heldMonitors()) {
                if (waiters.containsKey(monitor.id())) {
                    owners.putIfAbsent(monitor.id(), thread.name());
                    if (monitor.type() != null) {
                        types.putIfAbsent(monitor.id(), monitor.type());
                    }
                }
            }
        }

        Map<String, ContendedMonitor> contended = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : waiters.entrySet()) {
            contended.put(entry.getKey(), new ContendedMonitor(entry.getKey(),
                    types.get(entry.getKey()), owners.get(entry.getKey()), List.copyOf(entry.getValue())));
        }
        return contended;
    }

    /**
     * One monitor at least one thread is waiting to enter.
     *
     * @param monitorId the address the dump printed
     * @param type      the monitor's type as printed, or null when no line carried one
     * @param owner     the thread the dump shows holding it, or null when none of them does. Null is
     *                  the interesting case and is reported as unknown rather than guessed at: a
     *                  monitor can be waited on with its owner not in the dump, or held by a thread
     *                  the dump did not attribute the lock to
     * @param waiters   the threads waiting to enter it, in the dump's order
     */
    public record ContendedMonitor(String monitorId, String type, String owner, List<String> waiters) {
    }
}
