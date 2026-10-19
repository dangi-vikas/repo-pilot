package dev.repopilot.runtime.threaddump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A real thread dump of the JVM running the tests.
 *
 * <p>The plain-text format is a moving target: it is HotSpot's, not a specification, and its exact
 * words are what this reader is built on. So the tests do not check in a dump from some JDK and
 * assert against it forever — they ask the JDK in use to write one, exactly as an operator would:
 * {@code jcmd <pid> Thread.print}. A reader that only worked on a file captured in 2024 would pass
 * every test here and fail on the operator's machine.
 *
 * <p>{@code jcmd} ships with a JDK and not with a JRE, so the tests that need a dump skip
 * themselves when it is absent rather than failing for a reason that has nothing to do with the code
 * under test.
 */
public final class RealThreadDump {

    private RealThreadDump() {
    }

    /** Whether this JVM's distribution includes {@code jcmd}. */
    public static boolean isAvailable() {
        return Files.isExecutable(jcmd());
    }

    /**
     * Asks this JVM to print its own threads into {@code directory}, and returns the file.
     *
     * <p>The dump is of the test JVM itself, so the thread that took it is in it by name and the
     * assertions have something specific to find rather than only a plausible shape.
     */
    public static Path capture(Path directory) throws IOException {
        Path target = directory.resolve("thread-dump.txt");
        Process process = new ProcessBuilder(jcmd().toString(),
                Long.toString(ProcessHandle.current().pid()), "Thread.print")
                .redirectOutput(target.toFile())
                .redirectErrorStream(true)
                .start();
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for jcmd", e);
        }
        if (exit != 0) {
            throw new IOException("jcmd " + ProcessHandle.current().pid() + " Thread.print exited with "
                    + exit + " and wrote: " + (Files.exists(target) ? Files.readString(target) : "(nothing)"));
        }
        if (!Files.exists(target) || Files.size(target) == 0) {
            throw new IOException("jcmd wrote no dump");
        }
        return target;
    }

    /** The same dump as bytes, for tests that upload it over HTTP. */
    public static byte[] captureBytes(Path directory) throws IOException {
        return Files.readAllBytes(capture(directory));
    }

    /** {@code jcmd} on Windows is {@code jcmd.exe}; on a unix-like JDK it has no extension. */
    private static Path jcmd() {
        Path base = Path.of(System.getProperty("java.home"), "bin", "jcmd");
        Path windows = Path.of(base + ".exe");
        return Files.exists(windows) ? windows : base;
    }
}
