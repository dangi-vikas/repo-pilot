package dev.repopilot.runtime.threaddump;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a plain-text HotSpot thread dump. The only class that knows the format's words.
 *
 * <h2>Which format</h2>
 * {@link ThreadDumpFormat} decides, from the first lines, whether the file is one this reader can
 * use; the thin {@code dump_to_file} shapes are refused with the command that produces a readable
 * dump instead. The decision is separate from the parse so that a refusal is a statement about the
 * file rather than an odd exception from halfway down a parser.
 *
 * <h2>What the format actually contains</h2>
 * Everything here was established by reading dumps of a real JDK 21.0.5 with two threads
 * deliberately deadlocked, not from documentation:
 *
 * <ul>
 *   <li>A dump opens with {@code <pid>:} (written by {@code jcmd} only), a local timestamp with no
 *       zone, then a line beginning {@code Full thread dump } carrying the VM name and build.</li>
 *   <li>Each thread block starts with {@code "name" #tid [nid] ... status [stack]}. The JVM's own
 *       threads ({@code "VM Thread"}, {@code "GC Thread#0"}) have no {@code #tid} and, more to the
 *       point, no {@code java.lang.Thread.State} line — which is how this reader tells a Java
 *       thread from one of the JVM's own, by evidence rather than by guessing at the header.</li>
 *   <li>Monitors appear as {@code - locked <0x…> (a type)} and
 *       {@code - waiting to lock <0x…> (a type)}. {@code - waiting on} (an
 *       {@code Object.wait}) and {@code - parking to wait for} are different claims and are not
 *       read as contention.</li>
 *   <li>When the JVM's own detector finds a cycle it prints a section headed
 *       {@code Found one Java-level deadlock:} (or {@code Found N Java-level deadlocks:}), the
 *       waiting/held-by pairs, then <em>the same thread stacks again</em> under
 *       {@code Java stack information for the threads listed above:}, and finally
 *       {@code Found N deadlock.}.</li>
 *   <li>That repetition is why the parser stops reading lock lines when the deadlock section
 *       begins. Counting them twice would double the contention figures, and the stacks in that
 *       section are not thread blocks at all.</li>
 * </ul>
 *
 * <p>The reader is streaming: a dump of a JVM with a hundred thousand threads never has to fit in
 * memory, and {@code checkpoint} is called as the lines are consumed so a cancelled job stops
 * promptly.
 *
 * <p>One more thing is a property of the file rather than of the format: the encoding. A dump
 * captured through a Windows shell is UTF-16 with a byte-order mark, and reading it as UTF-8 would
 * turn a perfectly good dump into a refusal. The mark is honoured instead — see {@link #open}.
 */
public final class ThreadDumpReader {

    /** Lines read to classify a file. The header and the first threads are well inside this. */
    private static final int HEAD_LINES = 400;

    private static final int CHECKPOINT_EVERY = 2_000;

    private static final String HEADER_PREFIX = "Full thread dump ";
    private static final String STATE_PREFIX = "java.lang.Thread.State:";
    private static final String LOCKED_PREFIX = "- locked ";
    private static final String WAITING_TO_LOCK_PREFIX = "- waiting to lock ";
    private static final String FRAME_PREFIX = "at ";
    private static final String SMR_LIST_PREFIX = "_java_thread_list=";
    private static final String JNI_REFS_PREFIX = "JNI global refs:";

    private static final Pattern PID_LINE = Pattern.compile("^(\\d+):$");
    private static final Pattern LOCAL_OR_ISO_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?$");
    private static final Pattern SMR_LENGTH = Pattern.compile("length=(\\d+)");
    private static final Pattern DEADLOCK_TOTAL = Pattern.compile("^Found (\\d+) deadlock");
    private static final Pattern DEADLOCK_WAIT =
            Pattern.compile("^waiting to lock monitor \\S+ \\(object \\S+,\\s*(.+?)\\)\\s*,?$");
    private static final Pattern DEADLOCK_HELD_BY = Pattern.compile("^which is held by \"(.*)\"$");

    private ThreadDumpReader() {
    }

    /** Classifies a file by its first lines. */
    public static ThreadDumpFormat detect(Path file) throws IOException {
        try (BufferedReader reader = open(file)) {
            return ThreadDumpFormat.of(headOf(reader));
        }
    }

    /**
     * Parses a dump.
     *
     * @param checkpoint called every few thousand lines so a cancelled job stops promptly
     * @throws IOException when the file is not a readable dump. The message is written to be shown
     *                     to the operator, and the caller turns it into an {@code UNSUPPORTED_INPUT}
     */
    public static ThreadDumpReport read(Path file, Runnable checkpoint) throws IOException {
        try (BufferedReader reader = open(file)) {
            List<String> head = headOf(reader);
            ThreadDumpFormat format = ThreadDumpFormat.of(head);
            if (!format.readable()) {
                throw new IOException(ThreadDumpFormat.explain(format));
            }

            Parser parser = new Parser(checkpoint);
            head.forEach(parser::consume);
            String line;
            while ((line = reader.readLine()) != null) {
                parser.consume(line);
            }

            ThreadDumpReport report = parser.finish();
            if (report.threads().isEmpty()) {
                throw new IOException("the file carries a thread dump header but no thread blocks");
            }
            if (report.javaThreadCount() == 0) {
                throw new IOException("no thread block in it carries a " + STATE_PREFIX + " line, so the "
                        + "threads' states and the monitors they hold are not in the file and a report would be "
                        + "a page of zeros. RepoPilot reads the HotSpot plain-text format, which states both");
            }
            return report;
        }
    }

    /**
     * A reader that never fails on a malformed byte, decoding the charset the file announces.
     *
     * <p>Which charset a dump is in depends on how the operator captured it, and the difference is
     * not cosmetic. On Windows, {@code jcmd <pid> Thread.print > dump.txt} in PowerShell writes
     * UTF-16LE with a byte-order mark, because the shell transcodes the program's output on its way
     * to the file; {@code Thread.dump_to_file} writes the file itself, in UTF-8. Both are the same
     * dump. Decoding a UTF-16 file as UTF-8 does not fail either — it interleaves a NUL byte through
     * every line — so the dump would be refused as "not a thread dump", which is a refusal of
     * evidence because of the operator's terminal rather than because of anything in the file.
     *
     * <p>The mark is read rather than guessed, and a file with none is read as UTF-8, which is what
     * every tool in this area writes by default.
     *
     * <p>Thread names come from the application and can contain anything, and a dump is tool output,
     * not a validated document, so malformed input is replaced rather than refused. A replacement
     * character in one name is a better outcome than refusing a dump that is otherwise complete.
     */
    private static BufferedReader open(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                charsetOf(file).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }

    /**
     * The encoding the file announces with a byte-order mark, or UTF-8 when it announces none.
     *
     * <p>A UTF-16 file without a mark is deliberately not guessed at: an ASCII dump in UTF-16LE is
     * half NUL bytes, and a heuristic that looked for those would be a second format detector
     * disagreeing with the first.
     */
    private static Charset charsetOf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] mark = in.readNBytes(3);
            if (mark.length >= 3 && (mark[0] & 0xFF) == 0xEF && (mark[1] & 0xFF) == 0xBB
                    && (mark[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
            if (mark.length >= 2 && (mark[0] & 0xFF) == 0xFF && (mark[1] & 0xFF) == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (mark.length >= 2 && (mark[0] & 0xFF) == 0xFE && (mark[1] & 0xFF) == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            return StandardCharsets.UTF_8;
        }
    }

    private static List<String> headOf(BufferedReader reader) throws IOException {
        List<String> head = new ArrayList<>(HEAD_LINES);
        String line;
        while (head.size() < HEAD_LINES && (line = reader.readLine()) != null) {
            head.add(line);
        }
        if (!head.isEmpty()) {
            head.set(0, withoutByteOrderMark(head.get(0)));
        }
        return head;
    }

    /**
     * Drops a byte-order mark from the first line.
     *
     * <p>None of the decoders above consumes the mark: each yields it as a {@code U+FEFF} character
     * at the head of the first line, and the first line is exactly where the classification looks.
     * {@code String.trim()} does not remove it — it is not whitespace, and neither is it invisible to
     * a {@code startsWith}.
     */
    private static String withoutByteOrderMark(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }

    /**
     * The line-by-line state machine.
     *
     * <p>Fields rather than locals because the parse spans thousands of lines and the state has to
     * survive between them; the class is per-parse and never shared.
     */
    private static final class Parser {

        private final Runnable checkpoint;
        private final List<ThreadDumpReport.DumpThread> threads = new ArrayList<>();
        private final List<ThreadDumpReport.DeadlockEdge> deadlockEdges = new ArrayList<>();

        private int sinceCheckpoint;
        private String jvmName;
        private String jvmVersion;
        private String jvmMode;
        private String takenAt;
        private long processId;
        private Integer javaThreadListLength;

        private boolean afterThreads;
        private boolean inDeadlockSection;
        private boolean inThread;
        private String currentName;
        private String currentStateText;
        private int currentStackDepth;
        private List<ThreadDumpReport.MonitorRef> currentHeld = new ArrayList<>();
        private List<ThreadDumpReport.MonitorRef> currentWaiting = new ArrayList<>();

        private String pendingDeadlockThread;
        private String pendingDeadlockMonitorType;
        private boolean sawDeadlockWait;
        private Integer statedDeadlockCount;

        Parser(Runnable checkpoint) {
            this.checkpoint = checkpoint;
        }

        void consume(String line) {
            if (++sinceCheckpoint >= CHECKPOINT_EVERY) {
                sinceCheckpoint = 0;
                checkpoint.run();
            }

            if (line.startsWith(HEADER_PREFIX)) {
                header(line);
                return;
            }
            if (inDeadlockSection) {
                deadlock(line);
                return;
            }
            if (line.startsWith(JNI_REFS_PREFIX)) {
                closeThread();
                afterThreads = true;
                return;
            }
            if (line.startsWith("Found ")) {
                // Either the deadlock section's own heading or a stray "Found …" line; the summary
                // count at the end of the section is handled by `deadlock` because by then the
                // section flag is set.
                if (line.contains("Java-level deadlock")) {
                    closeThread();
                    inDeadlockSection = true;
                }
                return;
            }
            if (afterThreads) {
                return;
            }
            if (line.startsWith("\"")) {
                closeThread();
                startThread(line);
                return;
            }
            if (inThread) {
                threadLine(line);
                return;
            }
            if (line.startsWith(SMR_LIST_PREFIX)) {
                Matcher length = SMR_LENGTH.matcher(line);
                if (length.find()) {
                    javaThreadListLength = Integer.valueOf(length.group(1));
                }
                return;
            }
            if (takenAt == null && LOCAL_OR_ISO_INSTANT.matcher(line.trim()).matches()) {
                takenAt = line.trim();
                return;
            }
            if (processId == 0) {
                Matcher pid = PID_LINE.matcher(line.trim());
                if (pid.matches()) {
                    processId = Long.parseLong(pid.group(1));
                }
            }
        }

        ThreadDumpReport finish() {
            closeThread();
            return new ThreadDumpReport(
                    new ThreadDumpReport.Provenance(jvmName, jvmVersion, jvmMode, takenAt, processId,
                            javaThreadListLength),
                    List.copyOf(threads),
                    groupCycles(deadlockEdges),
                    statedDeadlockCount);
        }

        /**
         * {@code Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode,
         * sharing):}
         *
         * <p>The name is everything before the <em>last</em> parenthesised group, not the first: the
         * name itself contains one, {@code (TM)}, and the build token is always the last group.
         */
        private void header(String line) {
            String body = line.substring(HEADER_PREFIX.length()).trim();
            if (body.endsWith(":")) {
                body = body.substring(0, body.length() - 1).trim();
            }
            int open = body.lastIndexOf('(');
            int close = body.lastIndexOf(')');
            if (open < 0 || close <= open) {
                jvmName = body;
                return;
            }
            jvmName = body.substring(0, open).trim();
            String inside = body.substring(open + 1, close).trim();
            int space = inside.indexOf(' ');
            if (space < 0) {
                jvmVersion = inside;
            } else {
                jvmVersion = inside.substring(0, space);
                jvmMode = inside.substring(space + 1).trim();
            }
        }

        private void startThread(String line) {
            // A thread name may itself contain a quote, so the name is everything between the first
            // quote and the last one: `"say "hi"" #5 …` parses to `say "hi"`.
            int lastQuote = line.lastIndexOf('"');
            if (lastQuote <= 0) {
                return;
            }
            currentName = line.substring(1, lastQuote);
            currentStateText = null;
            currentStackDepth = 0;
            currentHeld = new ArrayList<>();
            currentWaiting = new ArrayList<>();
            inThread = true;
        }

        private void closeThread() {
            if (!inThread) {
                return;
            }
            threads.add(new ThreadDumpReport.DumpThread(currentName, currentStateText, currentStackDepth,
                    List.copyOf(currentHeld), List.copyOf(currentWaiting)));
            inThread = false;
            currentName = null;
        }

        private void threadLine(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith(STATE_PREFIX)) {
                currentStateText = trimmed.substring(STATE_PREFIX.length()).trim();
            } else if (trimmed.startsWith(FRAME_PREFIX)) {
                currentStackDepth++;
            } else if (trimmed.startsWith(LOCKED_PREFIX)) {
                monitor(trimmed.substring(LOCKED_PREFIX.length())).ifPresent(currentHeld::add);
            } else if (trimmed.startsWith(WAITING_TO_LOCK_PREFIX)) {
                monitor(trimmed.substring(WAITING_TO_LOCK_PREFIX.length())).ifPresent(currentWaiting::add);
            }
        }

        /** {@code <0x…> (a java.lang.Object)} */
        private static Optional<ThreadDumpReport.MonitorRef> monitor(String body) {
            int open = body.indexOf('<');
            int close = body.indexOf('>', open + 1);
            if (open < 0 || close <= open) {
                return Optional.empty();
            }
            String id = body.substring(open + 1, close);
            int paren = body.lastIndexOf('(');
            int parenClose = body.lastIndexOf(')');
            String type = paren >= 0 && parenClose > paren ? body.substring(paren + 1, parenClose).trim() : null;
            return Optional.of(new ThreadDumpReport.MonitorRef(id, type));
        }

        /**
         * The deadlock section, including the repeated stacks.
         *
         * <p>Entries and the stack listing that follows both open with {@code "name":}, so a name is
         * only an entry once the line after it has been recognised as a wait. Anything else is
         * discarded, which is what keeps the section's second copy of every stack out of the
         * measurements.
         */
        private void deadlock(String line) {
            String trimmed = line.trim();
            Matcher total = DEADLOCK_TOTAL.matcher(trimmed);
            if (total.find()) {
                statedDeadlockCount = Integer.valueOf(total.group(1));
                return;
            }
            if (trimmed.isEmpty()) {
                return;
            }
            if (line.startsWith("\"") && trimmed.endsWith("\":")) {
                pendingDeadlockThread = line.substring(1, line.length() - 2);
                pendingDeadlockMonitorType = null;
                sawDeadlockWait = false;
                return;
            }
            if (pendingDeadlockThread == null) {
                return;
            }
            if (!sawDeadlockWait) {
                Matcher wait = DEADLOCK_WAIT.matcher(trimmed);
                if (wait.matches()) {
                    pendingDeadlockMonitorType = wait.group(1);
                    sawDeadlockWait = true;
                } else {
                    // Not an entry after all: this was the repeated stack listing's thread heading.
                    pendingDeadlockThread = null;
                }
                return;
            }
            Matcher heldBy = DEADLOCK_HELD_BY.matcher(trimmed);
            if (heldBy.matches()) {
                deadlockEdges.add(new ThreadDumpReport.DeadlockEdge(pendingDeadlockThread,
                        pendingDeadlockMonitorType, heldBy.group(1)));
                pendingDeadlockThread = null;
                pendingDeadlockMonitorType = null;
                sawDeadlockWait = false;
            }
        }
    }

    /**
     * Groups edges into cycles by walking the threads as an undirected graph.
     *
     * <p>The dump prints one pair per waiting thread, so a two-thread deadlock is two edges, and
     * every thread in a cycle has exactly one outgoing edge. Grouping by connectivity therefore
     * yields one group per cycle, which is what "Found 1 deadlock." counts — and it does not depend
     * on the walk starting at any particular end of the cycle.
     *
     * <p>An entry whose owner is not itself waiting forms a group of its own rather than being
     * dropped: the dump said those two threads are involved in something, and reporting it as one
     * cycle of one edge is closer to the file than discarding it.
     */
    private static List<ThreadDumpReport.DeadlockCycle> groupCycles(List<ThreadDumpReport.DeadlockEdge> edges) {
        if (edges.isEmpty()) {
            return List.of();
        }
        Map<String, List<ThreadDumpReport.DeadlockEdge>> adjacency = new LinkedHashMap<>();
        for (ThreadDumpReport.DeadlockEdge edge : edges) {
            adjacency.computeIfAbsent(edge.thread(), key -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.heldBy(), key -> new ArrayList<>()).add(edge);
        }

        Set<String> visited = new HashSet<>();
        List<ThreadDumpReport.DeadlockCycle> cycles = new ArrayList<>();
        for (ThreadDumpReport.DeadlockEdge edge : edges) {
            if (visited.contains(edge.thread())) {
                continue;
            }
            Set<ThreadDumpReport.DeadlockEdge> component = new LinkedHashSet<>();
            Deque<String> frontier = new ArrayDeque<>();
            frontier.add(edge.thread());
            visited.add(edge.thread());
            while (!frontier.isEmpty()) {
                String at = frontier.poll();
                for (ThreadDumpReport.DeadlockEdge touching : adjacency.getOrDefault(at, List.of())) {
                    component.add(touching);
                    String other = touching.thread().equals(at) ? touching.heldBy() : touching.thread();
                    if (visited.add(other)) {
                        frontier.add(other);
                    }
                }
            }
            cycles.add(new ThreadDumpReport.DeadlockCycle(List.copyOf(component)));
        }
        return List.copyOf(cycles);
    }
}
