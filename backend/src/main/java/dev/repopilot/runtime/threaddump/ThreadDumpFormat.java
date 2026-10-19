package dev.repopilot.runtime.threaddump;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The shapes a file that claims to be a thread dump can have.
 *
 * <p>Only one of them can be read, and the difference is not cosmetic. HotSpot can write a thread
 * dump three ways, and two of them throw away exactly the information a concurrency inspection is
 * made of:
 *
 * <ul>
 *   <li>{@code jcmd <pid> Thread.print}, {@code jstack <pid>} and the {@code kill -3} signal all
 *       write the rich form: each thread's {@code java.lang.Thread.State}, the monitors it holds
 *       ({@code - locked}), the monitors it is waiting to enter ({@code - waiting to lock}), and a
 *       deadlock section when the JVM's own detector finds one. This is
 *       {@link #STACK_TRACE_TEXT}.</li>
 *   <li>{@code jcmd <pid> Thread.dump_to_file -format=plain} writes a process id, a timestamp and
 *       then a stack per thread, with no state and no lock lines at all. Verified against JDK
 *       21.0.5: a dump of a JVM with two deadlocked threads contains the word "deadlock" zero
 *       times.</li>
 *   <li>{@code jcmd <pid> Thread.dump_to_file -format=json} writes the same thin content as JSON —
 *       {@code tid}, {@code name} and an array of pre-formatted frame strings per thread.</li>
 * </ul>
 *
 * <p>So the thin forms are refused by name, with the exact command that produces a readable dump.
 * Reading them anyway would be worse than refusing: thread states, contention and deadlocks would
 * each be silently absent, and a report full of zeros is indistinguishable from a healthy JVM.
 */
public enum ThreadDumpFormat {

    /** The rich plain-text dump from {@code Thread.print}/{@code jstack}/{@code kill -3}. Read. */
    STACK_TRACE_TEXT,

    /** {@code Thread.dump_to_file -format=plain}: names and frames, no states, no locks. */
    DUMP_TO_FILE_PLAIN,

    /** {@code Thread.dump_to_file -format=json}: the same content as JSON. */
    DUMP_TO_FILE_JSON,

    /** No thread dump this build recognises. */
    UNKNOWN;

    private static final String HEADER_PREFIX = "Full thread dump ";

    /**
     * {@code 9568} — the process id {@code Thread.dump_to_file -format=plain} opens with.
     *
     * <p>Bare, with no colon: {@code jcmd}'s own banner line ({@code 9568:}) goes to the console and
     * never reaches the file, which is a difference verified against a real dump rather than assumed.
     */
    private static final Pattern THIN_PID_LINE = Pattern.compile("^\\d+$");

    /** An ISO-8601 instant, which is how the {@code dump_to_file} formats timestamp themselves. */
    private static final Pattern ISO_INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?$");

    /** {@code #77 "parallel-6"} — a thread header in the {@code dump_to_file -format=plain} file. */
    private static final Pattern THIN_THREAD_LINE = Pattern.compile("^#\\d+ \"");

    /**
     * Classifies a file from the first lines of it.
     *
     * <p>Pure over the lines handed in, so the classification of each real shape is a unit test
     * rather than an integration test.
     *
     * @param head the first lines of the file, in order
     */
    public static ThreadDumpFormat of(List<String> head) {
        for (String line : head) {
            if (line.startsWith(HEADER_PREFIX)) {
                return STACK_TRACE_TEXT;
            }
            if (line.trim().startsWith("{")) {
                // The JSON dump's top-level object is named, so a JSON file that is not a thread
                // dump (a JMH result, say) is not classified as one.
                return head.stream().anyMatch(candidate -> candidate.contains("\"threadDump\""))
                        ? DUMP_TO_FILE_JSON
                        : UNKNOWN;
            }
        }
        if (looksLikeThinPlainDump(head)) {
            return DUMP_TO_FILE_PLAIN;
        }
        return UNKNOWN;
    }

    /**
     * {@code dump_to_file -format=plain} opens with a process id, an ISO instant and a build token,
     * in that order, and then goes straight into {@code #tid "name"} blocks.
     *
     * <p>Three signals have to line up — a bare process id, an ISO instant and a numbered thread
     * header — because a file whose first line happens to be a number is not evidence of anything on
     * its own.
     */
    private static boolean looksLikeThinPlainDump(List<String> head) {
        if (head.size() < 4) {
            return false;
        }
        boolean pid = THIN_PID_LINE.matcher(head.get(0).trim()).matches();
        boolean instant = ISO_INSTANT.matcher(head.get(1).trim()).matches();
        boolean thread = head.stream().anyMatch(line -> THIN_THREAD_LINE.matcher(line).find());
        return pid && instant && thread;
    }

    /**
     * Why this file cannot be read, stated so that the operator can act on it.
     *
     * <p>Names the command that produces a readable dump rather than only the command that produced
     * this one: the useful answer to "your dump is missing its thread states" is how to get a dump
     * that has them.
     */
    public static String explain(ThreadDumpFormat format) {
        return switch (format) {
            case STACK_TRACE_TEXT -> "This is a plain-text thread dump, which RepoPilot reads.";
            case DUMP_TO_FILE_PLAIN, DUMP_TO_FILE_JSON ->
                    "This dump was written by `jcmd <pid> Thread.dump_to_file`, which records each thread's "
                            + "name and stack frames but not its state and not the monitors it holds or waits on. "
                            + "Thread states, monitor contention and deadlocks are therefore not in the file at "
                            + "all, and a report of zeros would be indistinguishable from a healthy JVM. "
                            + "Re-dump it with `jcmd <pid> Thread.print > dump.txt`, `jstack <pid> > dump.txt`, or "
                            + "`kill -3 <pid>` and capture the process output.";
            case UNKNOWN ->
                    "This file is not a thread dump in a format RepoPilot reads. It reads the plain-text dump "
                            + "written by `jcmd <pid> Thread.print`, `jstack <pid>`, or `kill -3 <pid>`, and "
                            + "recognises it by the \"Full thread dump\" header line those tools write.";
        };
    }

    /** Whether this format carries thread states and monitor ownership. */
    public boolean readable() {
        return this == STACK_TRACE_TEXT;
    }
}
