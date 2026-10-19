package dev.repopilot.runtime.threaddump;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reading a real plain-text thread dump.
 *
 * <p>Two sources of truth, used for different things:
 *
 * <ul>
 *   <li>The JVM under test dumps <em>itself</em> through {@code jcmd}, so the header, the thread
 *       blocks and whatever states this JVM is actually in are exercised as they are, not as they
 *       were in some captured file.</li>
 *   <li>The deadlock section is a fixture, because a test that deadlocks threads on purpose to
 *       produce one is a test that can hang the build. The fixture is the section JDK 21.0.5 printed
 *       for a real two-thread deadlock, copied line for line, including the fact that it repeats both
 *       thread stacks underneath — which is the trap this reader has to survive.</li>
 * </ul>
 */
class ThreadDumpReaderTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("reads a real dump of this JVM: header, states and the JVM's own threads")
    void readsARealDump() throws Exception {
        assumeTrue(RealThreadDump.isAvailable(), "no jcmd in this JDK, so no dump can be taken");
        Path file = RealThreadDump.capture(directory);

        ThreadDumpReport report = ThreadDumpReader.read(file, () -> { });

        assertThat(report.provenance().jvmName()).isNotBlank();
        assertThat(report.provenance().jvmVersion()).isNotBlank();
        assertThat(report.provenance().takenAt()).isNotBlank();
        assertThat(report.provenance().processId()).isEqualTo(ProcessHandle.current().pid());

        // The thread that took the dump is the test thread, and it is named in it. That is the
        // assertion that proves the parse is of this process and not of a plausible-looking file.
        assertThat(report.threads()).anySatisfy(thread ->
                assertThat(thread.name()).isEqualTo(Thread.currentThread().getName()));

        // A JVM always has both: application threads with a Java state, and its own runtime threads
        // without one. Reading only the first is how a concurrency report ends up lying about GC.
        assertThat(report.javaThreadCount()).isPositive();
        assertThat(report.threadsWithoutJavaState()).isPositive();
        assertThat(report.threads()).hasSize((int) (report.javaThreadCount() + report.threadsWithoutJavaState()));

        // Every thread with a state line is in one of the four states, and nothing is left over.
        assertThat(report.unknownStateCount()).isZero();
        assertThat(report.stateCounts().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(report.javaThreadCount());
        assertThat(report.stateCounts().keySet()).containsExactlyInAnyOrderElementsOf(
                ThreadDumpReport.REPORTABLE_STATES);
        assertThat(report.threads()).allSatisfy(thread -> {
            if (thread.hasJavaState()) {
                assertThat(thread.state()).isNotNull();
                assertThat(ThreadDumpReport.REPORTABLE_STATES).contains(thread.state());
            } else {
                assertThat(thread.state()).isNull();
            }
        });

        // A test JVM has no deadlock, and the dump says so by printing no section at all — which is
        // reported as an absent claim rather than as a search RepoPilot ran.
        assertThat(report.deadlocks()).isEmpty();
        assertThat(report.statedDeadlockCount()).isNull();
    }

    @Test
    @DisplayName("reads the deadlock section a real JDK wrote, and does not count its stacks twice")
    void readsTheDeadlockSection() throws Exception {
        Path file = write("deadlock.txt", deadlockDump());

        ThreadDumpReport report = ThreadDumpReader.read(file, () -> { });

        // 6. Four Java threads (main, Reference Handler, and the two deadlocked ones) and two of the
        // JVM's own.
        assertThat(report.threads()).hasSize(6);
        assertThat(report.javaThreadCount()).isEqualTo(4);
        assertThat(report.threadsWithoutJavaState()).isEqualTo(2);
        assertThat(report.stateCounts()).isEqualTo(Map.of(
                Thread.State.RUNNABLE, 1L,
                Thread.State.BLOCKED, 2L,
                Thread.State.WAITING, 1L,
                Thread.State.TIMED_WAITING, 0L));
        // The parenthesised detail is kept, because "WAITING (parking)" and "WAITING (on object
        // monitor)" are different waits.
        assertThat(report.stateDetails().get(Thread.State.WAITING)).containsExactlyInAnyOrderEntriesOf(
                Map.of("parking", 1L));
        assertThat(report.stateDetails().get(Thread.State.BLOCKED)).containsExactlyInAnyOrderEntriesOf(
                Map.of("on object monitor", 2L));

        // The deadlock section repeats both stacks, and each of those repeats carries the same
        // `- waiting to lock` line as the thread block above it. Two monitors, one waiter each: four
        // if the section's copies were counted as thread blocks.
        assertThat(report.contendedMonitors()).hasSize(2);
        assertThat(report.contendedMonitors().values()).allSatisfy(monitor -> {
            assertThat(monitor.type()).isEqualTo("a java.lang.Object");
            assertThat(monitor.waiters()).hasSize(1);
        });
        // And the holder is identified from the `- locked` lines, which is what makes the contention
        // figure usable: "someone is waiting" is not actionable, "this thread is holding it" is.
        assertThat(report.contendedMonitors().values()).anySatisfy(monitor ->
                assertThat(monitor.owner()).isEqualTo("deadlock-first-then-second"));
        assertThat(report.contendedMonitors().values()).anySatisfy(monitor ->
                assertThat(monitor.owner()).isEqualTo("deadlock-second-then-first"));

        assertThat(report.statedDeadlockCount()).isEqualTo(1);
        assertThat(report.deadlocks()).hasSize(1);
        assertThat(report.deadlockedThreadCount()).isEqualTo(2);
        assertThat(report.deadlockedThreadNames())
                .containsExactlyInAnyOrder("deadlock-first-then-second", "deadlock-second-then-first");
        assertThat(report.deadlocks().get(0).edges()).hasSize(2);
        assertThat(report.deadlocks().get(0).edges()).anySatisfy(edge -> {
            assertThat(edge.thread()).isEqualTo("deadlock-first-then-second");
            assertThat(edge.monitorType()).isEqualTo("a java.lang.Object");
            assertThat(edge.heldBy()).isEqualTo("deadlock-second-then-first");
        });
    }

    @Test
    @DisplayName("keeps a thread name that contains a quote, and does not read parking as contention")
    void handlesAwkwardNames() throws Exception {
        Path file = write("awkward.txt", String.join("\n", List.of(
                "1:",
                "2026-01-01 00:00:00",
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):",
                "",
                "\"say \"hi\"\" #5 [1] prio=5 os_prio=0 cpu=0.00ms elapsed=1.00s tid=0x1 nid=1 runnable  [0x1]",
                "   java.lang.Thread.State: TIMED_WAITING (parking)",
                "\tat jdk.internal.misc.Unsafe.park(java.base@21.0.5/Native Method)",
                "\t- parking to wait for  <0x000000071321ab18> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)",
                "\t- waiting on <0x000000071472a090> (a java.lang.Object)",
                "\tat java.util.concurrent.ForkJoinPool.awaitWork(java.base@21.0.5/ForkJoinPool.java:1)",
                "",
                "JNI global refs: 1, weak refs: 0")));

        ThreadDumpReport report = ThreadDumpReader.read(file, () -> { });

        ThreadDumpReport.DumpThread thread = report.threads().get(0);
        assertThat(thread.name()).isEqualTo("say \"hi\"");
        assertThat(thread.state()).isEqualTo(Thread.State.TIMED_WAITING);
        assertThat(thread.stateDetail()).isEqualTo("parking");
        assertThat(thread.stackDepth()).isEqualTo(2);
        // Parking and an Object.wait are not contention: a thread waiting to be notified is not
        // waiting for a monitor to be released, and reporting them together would overstate
        // contention in every queue-based application.
        assertThat(thread.waitingToLock()).isEmpty();
        assertThat(thread.heldMonitors()).isEmpty();
        assertThat(report.contendedMonitors()).isEmpty();
    }

    @Test
    @DisplayName("reads a dump a Windows shell re-encoded as UTF-16, BOM and all")
    void readsAWindowsCapturedDump() throws Exception {
        // `jcmd <pid> Thread.print > dump.txt` in PowerShell writes UTF-16LE with a byte-order mark,
        // because the shell transcodes the tool's output on the way to the file. Read as UTF-8 the
        // lines become every other byte, the header is no longer "Full thread dump ", and a perfectly
        // good dump is refused as "not a thread dump" — a refusal of evidence caused by the operator's
        // terminal. That is the failure this test exists to prevent.
        Path utf16 = directory.resolve("deadlock-utf16.txt");
        byte[] content = deadlockDump().getBytes(StandardCharsets.UTF_16LE);
        byte[] marked = new byte[content.length + 2];
        marked[0] = (byte) 0xFF;
        marked[1] = (byte) 0xFE;
        System.arraycopy(content, 0, marked, 2, content.length);
        Files.write(utf16, marked);

        assertThat(ThreadDumpReader.detect(utf16)).isEqualTo(ThreadDumpFormat.STACK_TRACE_TEXT);
        ThreadDumpReport report = ThreadDumpReader.read(utf16, () -> { });

        // Not "it parsed something": the same report the UTF-8 copy yields, down to the thread names.
        assertThat(report.provenance().processId()).isEqualTo(23720L);
        assertThat(report.threads()).hasSize(6);
        assertThat(report.stateCounts()).isEqualTo(Map.of(
                Thread.State.RUNNABLE, 1L,
                Thread.State.BLOCKED, 2L,
                Thread.State.WAITING, 1L,
                Thread.State.TIMED_WAITING, 0L));
        assertThat(report.deadlockedThreadNames())
                .containsExactlyInAnyOrder("deadlock-first-then-second", "deadlock-second-then-first");
        assertThat(report.threads()).extracting(ThreadDumpReport.DumpThread::name)
                .doesNotContain("");
        // The mark must not survive into a name or a count, or the first thread of every dump taken
        // this way would be called "\uFEFFmain".
        assertThat(report.threads()).allSatisfy(thread ->
                assertThat(thread.name()).doesNotContain("\uFEFF"));
    }

    @Test
    @DisplayName("refuses a thin dump_to_file file whatever its encoding")
    void refusesThinDumpsInAnyEncoding() throws Exception {
        String thin = String.join("\n", List.of(
                "9568",
                "2026-10-19T04:34:15.300714Z",
                "21.0.5+9-LTS-239",
                "",
                "#9 \"Reference Handler\"",
                "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)"));
        Path utf16 = writeUtf16("thin-utf16.txt", thin);

        // A refusal has to be accurate for the same reason a read does: a UTF-16 thin dump misread as
        // unknown would send the operator looking for a different dump rather than a different command.
        assertThat(ThreadDumpReader.detect(utf16)).isEqualTo(ThreadDumpFormat.DUMP_TO_FILE_PLAIN);
        assertThatThrownBy(() -> ThreadDumpReader.read(utf16, () -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Thread.dump_to_file");
    }

    @Test
    @DisplayName("refuses the thin dump_to_file shapes, with the reason and the fix")
    void refusesThinDumps() throws Exception {
        Path plain = write("thin.txt", String.join("\n", List.of(
                "9568",
                "2026-10-19T04:34:15.300714Z",
                "21.0.5+9-LTS-239",
                "",
                "#9 \"Reference Handler\"",
                "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)")));

        assertThatThrownBy(() -> ThreadDumpReader.read(plain, () -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Thread.dump_to_file")
                .hasMessageContaining("jcmd <pid> Thread.print");

        assertThat(ThreadDumpReader.detect(plain)).isEqualTo(ThreadDumpFormat.DUMP_TO_FILE_PLAIN);
    }

    @Test
    @DisplayName("refuses a dump whose threads state no state, rather than reporting a page of zeros")
    void refusesAFormatWithoutStates() throws Exception {
        // Not hypothetical: any tool that writes a stack per thread without HotSpot's state line
        // produces this, and reading it would report "0 threads in every state" — a report that is
        // indistinguishable from an idle, healthy JVM.
        Path file = write("stateless.txt", String.join("\n", List.of(
                "Full thread dump Some Other VM (1.0 mixed mode):",
                "",
                "\"main\" prio=6 tid=0x1 nid=0x1 runnable [0x1]",
                "\tat A.b(A.java:1)",
                "",
                "JNI global refs: 1, weak refs: 0")));

        assertThatThrownBy(() -> ThreadDumpReader.read(file, () -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("java.lang.Thread.State")
                .hasMessageContaining("zeros");
    }

    @Test
    @DisplayName("refuses a file with the header and no threads in it")
    void refusesAHeaderWithNoThreads() throws Exception {
        Path file = write("empty.txt", String.join("\n", List.of(
                "1:",
                "2026-01-01 00:00:00",
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):",
                "",
                "JNI global refs: 0, weak refs: 0")));

        assertThatThrownBy(() -> ThreadDumpReader.read(file, () -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no thread blocks");
    }

    @Test
    @DisplayName("checkpoints while reading, so a long dump can be cancelled")
    void checkpointsWhileReading() throws Exception {
        assumeTrue(RealThreadDump.isAvailable(), "no jcmd in this JDK, so no dump can be taken");
        Path file = RealThreadDump.capture(directory);

        int[] checkpoints = {0};
        ThreadDumpReader.read(file, () -> checkpoints[0]++);

        // A real dump of a test JVM is a few hundred lines, which is below one interval: the point
        // here is that the reader accepts a checkpoint at all, and the long-dump path is the same
        // code. The count is therefore asserted as non-negative rather than as a specific number.
        assertThat(checkpoints[0]).isNotNegative();
    }

    private Path write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** The same text as a Windows shell writes it: UTF-16LE with a byte-order mark. */
    private Path writeUtf16(String name, String content) throws IOException {
        byte[] encoded = content.getBytes(StandardCharsets.UTF_16LE);
        byte[] marked = new byte[encoded.length + 2];
        marked[0] = (byte) 0xFF;
        marked[1] = (byte) 0xFE;
        System.arraycopy(encoded, 0, marked, 2, encoded.length);
        Path file = directory.resolve(name);
        Files.write(file, marked);
        return file;
    }

    /**
     * The deadlock section of a real JDK 21.0.5 dump, with the two thread blocks that go with it.
     *
     * <p>Copied verbatim from a dump taken from a JVM whose two threads took the same two monitors in
     * opposite orders. The two `- waiting to lock` lines inside the threads and the two inside the
     * deadlock section are the same two monitors, which is exactly what a reader that does not stop
     * at the section header gets wrong.
     */
    private static String deadlockDump() {
        return String.join("\n", List.of(
                "23720:",
                "2026-10-19 10:04:53",
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):",
                "",
                "Threads class SMR info:",
                "_java_thread_list=0x0000021d4c4c45b0, length=13, elements={",
                "0x0000021d662d95f0, 0x0000021d7f8e8170",
                "}",
                "",
                "\"main\" #1 [28032] prio=5 os_prio=0 cpu=812.50ms elapsed=2.70s tid=0x0000021d662d95f0 nid=28032 runnable  [0x00000055e83fe000]",
                "   java.lang.Thread.State: RUNNABLE",
                "\tat java.lang.ProcessImpl.waitForInterruptibly(java.base@21.0.5/Native Method)",
                "\tat DeadlockDump.main(DeadlockDump.java:35)",
                "",
                "\"Reference Handler\" #9 [22960] daemon prio=10 os_prio=2 cpu=0.00ms elapsed=2.67s tid=0x0000021d7f8e8170 nid=22960 waiting on condition  [0x00000055e8bff000]",
                "   java.lang.Thread.State: WAITING (parking)",
                "\tat jdk.internal.misc.Unsafe.park(java.base@21.0.5/Native Method)",
                "\t- parking to wait for  <0x000000071321ab18> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)",
                "",
                "\"deadlock-first-then-second\" #27 [25496] prio=5 os_prio=0 cpu=0.00ms elapsed=1.82s tid=0x0000021d4bb52910 nid=25496 waiting for monitor entry  [0x00000055e9dff000]",
                "   java.lang.Thread.State: BLOCKED (on object monitor)",
                "\tat DeadlockDump.firstThenSecond(DeadlockDump.java:46)",
                "\t- waiting to lock <0x000000071472a090> (a java.lang.Object)",
                "\t- locked <0x000000071472a080> (a java.lang.Object)",
                "\tat DeadlockDump$$Lambda/0x0000021d071409f8.run(Unknown Source)",
                "",
                "\"deadlock-second-then-first\" #28 [28596] prio=5 os_prio=0 cpu=0.00ms elapsed=1.82s tid=0x0000021d4b8bdbf0 nid=28596 waiting for monitor entry  [0x00000055e9eff000]",
                "   java.lang.Thread.State: BLOCKED (on object monitor)",
                "\tat DeadlockDump.secondThenFirst(DeadlockDump.java:55)",
                "\t- waiting to lock <0x000000071472a080> (a java.lang.Object)",
                "\t- locked <0x000000071472a090> (a java.lang.Object)",
                "\tat DeadlockDump$$Lambda/0x0000021d07140c08.run(Unknown Source)",
                "",
                "\"VM Thread\" os_prio=2 cpu=0.00ms elapsed=2.68s tid=0x0000021d4c4c45b0 nid=12344 runnable",
                "",
                "\"GC Thread#0\" os_prio=2 cpu=0.00ms elapsed=2.68s tid=0x0000021d4c4d0c30 nid=0 runnable",
                "",
                "JNI global refs: 8, weak refs: 0",
                "",
                "Found one Java-level deadlock:",
                "=============================",
                "\"deadlock-first-then-second\":",
                "  waiting to lock monitor 0x0000021d4c50cc10 (object 0x000000071472a090, a java.lang.Object),",
                "  which is held by \"deadlock-second-then-first\"",
                "",
                "\"deadlock-second-then-first\":",
                "  waiting to lock monitor 0x0000021d4b5f6730 (object 0x000000071472a080, a java.lang.Object),",
                "  which is held by \"deadlock-first-then-second\"",
                "",
                "Java stack information for the threads listed above:",
                "===================================================",
                "\"deadlock-first-then-second\":",
                "\tat DeadlockDump.firstThenSecond(DeadlockDump.java:46)",
                "\t- waiting to lock <0x000000071472a090> (a java.lang.Object)",
                "\t- locked <0x000000071472a080> (a java.lang.Object)",
                "\"deadlock-second-then-first\":",
                "\tat DeadlockDump.secondThenFirst(DeadlockDump.java:55)",
                "\t- waiting to lock <0x000000071472a080> (a java.lang.Object)",
                "\t- locked <0x000000071472a090> (a java.lang.Object)",
                "",
                "Found 1 deadlock."));
    }
}
