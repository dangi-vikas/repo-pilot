package dev.repopilot.runtime.heapdump;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A JVM with a deliberately countable object population, so a real heap dump of it can be checked
 * against the numbers it was built from.
 *
 * <p>Started by {@link TestHeapDump} as a separate process and dumped with {@code jcmd}, exactly as
 * an operator would dump an application. The point of the exact counts is that they are the only way
 * to tell a reader that walks a real HPROF file correctly from one that produces numbers that merely
 * look plausible: the JDK's own classes are in the dump too, so only the types defined here have
 * counts this test can predict, and every one of them is asserted exactly.
 *
 * <p>Run as {@code java HeapDumpSubject <ready-file>}: the population is allocated, the ready file is
 * written, and the process sleeps until it is killed.
 */
public final class HeapDumpSubject {

    /** {@code new byte[1024]} arrays held live. */
    public static final int BYTE_ARRAYS = 200;
    public static final int BYTE_ARRAY_SIZE = 1024;

    /** {@code new int[256]} arrays held live. */
    public static final int INT_ARRAYS = 50;
    public static final int INT_ARRAY_SIZE = 256;

    /** Instances of {@link Node}: a {@code String}, an {@code int} and an {@code Object[4]}. */
    public static final int NODES = 100;

    /** Instances of {@link Bucket}, each holding one {@code byte[512]}. */
    public static final int BUCKETS = 40;
    public static final int BUCKET_SIZE = 512;

    /**
     * The field bytes one {@code Node} carries in a dump: a reference (8), an int (4) and a reference
     * (8). Not the size the object occupies — a dump records field values, not a layout — which is
     * exactly the distinction the reader's figures have to preserve.
     */
    public static final long NODE_FIELD_BYTES = 20;

    /** The field bytes one {@code Bucket} carries: one reference. */
    public static final long BUCKET_FIELD_BYTES = 8;

    /** Held in a static field so a full GC during the dump cannot collect any of it. */
    static final List<Object> KEEP = new ArrayList<>();

    static final class Node {

        @SuppressWarnings("unused")
        final String name;
        @SuppressWarnings("unused")
        final int value;
        @SuppressWarnings("unused")
        final Object[] children = new Object[4];

        Node(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    static final class Bucket {

        @SuppressWarnings("unused")
        final byte[] data;

        Bucket(int size) {
            this.data = new byte[size];
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < BYTE_ARRAYS; i++) {
            KEEP.add(new byte[BYTE_ARRAY_SIZE]);
        }
        for (int i = 0; i < INT_ARRAYS; i++) {
            KEEP.add(new int[INT_ARRAY_SIZE]);
        }
        for (int i = 0; i < NODES; i++) {
            KEEP.add(new Node("node-" + i, i));
        }
        for (int i = 0; i < BUCKETS; i++) {
            KEEP.add(new Bucket(BUCKET_SIZE));
        }

        Path ready = Path.of(args[0]);
        Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(600_000);
    }
}
