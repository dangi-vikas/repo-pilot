package dev.repopilot.runtime.heapdump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A real heap dump of a real JVM with a known object population.
 *
 * <p>HPROF is a binary format with no specification the JDK ships, and its record widths are the
 * whole difficulty of reading it. So the tests do not check in a dump from some JDK and assert
 * against it forever: they build a subject JVM in which every count is known, ask the JDK in use to
 * dump it with {@code jcmd GC.heap_dump}, and read the result. A reader that only worked on a file
 * captured once would pass every test here and fail on the operator's machine.
 *
 * <p>The subject is a separate process rather than the test JVM, because the test JVM's heap is
 * whatever the test framework has accumulated, and because dumping it would mean dumping a process
 * that is still running assertions.
 */
public final class TestHeapDump {

    private TestHeapDump() {
    }

    /**
     * Whether this JVM's distribution includes {@code jcmd}.
     *
     * <p>{@code jcmd} ships with a JDK and not with a JRE, so tests that need a dump skip themselves
     * when it is absent rather than failing for a reason that has nothing to do with the code under
     * test.
     */
    public static boolean isAvailable() {
        try {
            Process probe = new ProcessBuilder(jcmd(), "-h")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Starts a subject JVM, dumps its heap, and returns the file.
     *
     * <p>The subject writes its pid to a ready file once its population is allocated; the dump is not
     * taken until that file exists, so the counts the tests assert are the ones the subject was built
     * with rather than whatever it had reached when the dump happened to run.
     */
    public static Path capture(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path ready = directory.resolve("ready.txt");
        Path dump = directory.resolve("subject.hprof");
        Files.deleteIfExists(ready);
        Files.deleteIfExists(dump);

        Path log = directory.resolve("subject.log");
        Process subject = new ProcessBuilder(
                java(),
                "-Xmx256m",
                "-cp", System.getProperty("java.class.path"),
                HeapDumpSubject.class.getName(),
                ready.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        try {
            awaitReady(subject, ready, log);
            run(List.of(jcmd(), Long.toString(subject.pid()), "GC.heap_dump", dump.toString()));
            if (!Files.isRegularFile(dump) || Files.size(dump) == 0) {
                throw new IOException("jcmd GC.heap_dump produced no file; subject log:\n"
                        + read(log));
            }
            return dump;
        } finally {
            subject.destroyForcibly();
            try {
                subject.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitReady(Process subject, Path ready, Path log) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                return;
            }
            if (!subject.isAlive()) {
                throw new IOException("the heap-dump subject exited before it was ready (exit "
                        + subject.exitValue() + "); subject log:\n" + read(log));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the heap-dump subject", e);
            }
        }
        throw new IOException("the heap-dump subject did not become ready within 60 seconds");
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("timed out running " + command + ":\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running " + command, e);
        }
        if (process.exitValue() != 0) {
            throw new IOException(command + " exited " + process.exitValue() + ":\n" + output);
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "(no log)";
        } catch (IOException e) {
            return "(log unreadable: " + e.getMessage() + ")";
        }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String jcmd() {
        return Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
    }
}
