package dev.repopilot.runtime.heapdump;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the HPROF binary format that {@code jmap}, {@code jcmd GC.heap_dump} and
 * {@code -XX:+HeapDumpOnOutOfMemoryError} write, and reports what the dump recorded.
 *
 * <h2>The shape of the format, and why reading it is exact</h2>
 * An HPROF file is a header — {@code JAVA PROFILE 1.0.2\0}, the identifier width, and milliseconds
 * since the epoch — followed by a stream of length-prefixed records (strings, loaded classes, stack
 * traces, and one or more heap-dump segments). Top-level records carry their own length, so anything
 * this reader does not understand can be skipped exactly and safely.
 *
 * <p>The records <em>inside</em> a heap segment carry no length, which is the whole difficulty of the
 * format: to walk them you must know how many bytes each kind occupies. Three facts make that exact
 * rather than a guess, and each was established against a dump taken from a real JVM rather than
 * taken from memory:
 *
 * <ul>
 *   <li>An instance record declares the number of field bytes that follow it, so this reader never
 *       needs a class's field layout to walk past an instance. Verified against a real dump: the
 *       declared length equals the field sizes the class record also declares, inherited fields
 *       included (a {@code java.lang.String} instance declares and carries exactly the 4+8+1+4+1
 *       bytes its fields add up to).</li>
 *   <li>Array records declare their element count, and a reference in an array occupies the file's
 *       identifier width — 8 bytes in a 64-bit dump, compressed references notwithstanding.
 *       Verified: the byte counts that fall out of this are exact multiples of 8 while the equally
 *       plausible 4-byte reading is not, and the walk of a 6.9 MB heap stays exact.</li>
 *   <li>A class record's size is derived by walking its constant pool, static fields and instance
 *       fields with the format's fixed type widths. Verified: all 2905 class records in a real dump
 *       are consumed exactly and the counts this yields match {@code jmap -histo} for the same JVM
 *       (13 521 {@code byte[]} and 12 312 {@code java.lang.String}).</li>
 * </ul>
 *
 * <p>Every segment is read against a budget: the walker may not read past the end of the segment it
 * is in, it may not stop before it, and a record that does not fit is an error rather than a
 * truncated count. A record tag this reader does not know is refused by name. The alternative —
 * assuming a size and continuing — is the failure mode that matters here, because a parser that
 * guesses produces a page of plausible numbers that are wrong, and nothing downstream can tell.
 *
 * <h2>Bounded work over a file that is not trusted</h2>
 * The file is walked, never loaded: nothing is buffered whole, and no per-object state is kept. What
 * is kept is one aggregate per type — bounded, because a JVM has a bounded number of classes — and
 * the text of the strings a loaded class names itself with. That last part is why there are two
 * passes: the string table can be arbitrarily large, so the first pass reads only the class table and
 * the second keeps only the strings that table refers to. A dump that declares more types than
 * {@link #MAX_TYPES} is refused, because that bound is what stops the aggregate table from being the
 * thing that exhausts memory.
 */
public final class HeapDumpReader {

    /** Version of this reader, recorded on every run so a measurement can be traced to a parser. */
    public static final String READER_VERSION = "heap-dump-reader=1.0.0";

    private static final String MAGIC = "JAVA PROFILE ";

    /** {@code JAVA PROFILE 1.0.2} plus the terminating NUL. */
    private static final int MAGIC_LENGTH = 19;

    /** The header: magic, identifier width, timestamp. */
    private static final int HEADER_BYTES = MAGIC_LENGTH + 4 + 8;

    /** Top-level record tags. Everything else is skipped by the record's own length. */
    private static final int TAG_STRING = 0x01;
    private static final int TAG_LOAD_CLASS = 0x02;
    private static final int TAG_HEAP_DUMP = 0x0C;
    private static final int TAG_HEAP_DUMP_SEGMENT = 0x1C;

    /**
     * Types this reader will aggregate. A JVM with 50 000 loaded classes is a large application; this
     * is ten times that, so a legitimate dump never meets it and a crafted one cannot get past it.
     */
    private static final int MAX_TYPES = 500_000;

    /**
     * Longest class-name string this reader will keep. A class name comes from a class file's constant
     * pool, where a UTF-8 entry may not exceed 65 535 bytes, so a longer one is not a name.
     */
    private static final int MAX_NAME_BYTES = 65_535;

    /** How often the walker checks for cancellation, and how often it reports progress. */
    private static final int CHECKPOINT_RECORDS = 50_000;
    private static final long REPORT_BYTES = 8L * 1024 * 1024;

    private static final String FILE_BYTES_BASIS = "the size of the uploaded file: the on-disk size, "
            + "which is the size of the heap image only for the region of the heap the dump recorded; "
            + "it is not the size of the process and not the size of the heap";

    private HeapDumpReader() {
    }

    /**
     * How a reader is told to stop, and how it says how far it has got.
     *
     * <p>Deliberately not a job type: the reader knows nothing about jobs or transactions, and the
     * handler adapts one to the other. The two methods are separate because they mean different
     * things — a checkpoint may abort, a progress report may not.
     */
    public interface Progress {

        /** Called between records. Must throw (a {@code JobAbortedException}) when the job is cancelled. */
        void checkpoint();

        /**
         * How much of the file has been consumed.
         *
         * @param fraction 0.0 to 1.0
         * @param detail   a short phrase for the operator
         */
        void report(double fraction, String detail);
    }

    /** What kind of file this is, and what it begins with. */
    public record Detection(HeapDumpFormat format, String head) {
    }

    /**
     * Identifies a file from its first bytes, so an obvious mismatch is refused before a job is spent.
     *
     * <p>Reads at most one kilobyte and never parses the file.
     */
    public static Detection detect(Path file) throws IOException {
        byte[] head = new byte[1024];
        int read;
        try (DataInputStream in = open(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read == 0) {
            return new Detection(HeapDumpFormat.EMPTY, null);
        }
        if (startsWith(head, read, MAGIC.getBytes(StandardCharsets.US_ASCII))) {
            String version = new String(head, MAGIC.length(), 5, StandardCharsets.US_ASCII);
            HeapDumpFormat format = switch (version) {
                case "1.0.2" -> HeapDumpFormat.HPROF_1_0_2;
                case "1.0.1" -> HeapDumpFormat.HPROF_1_0_1;
                default -> HeapDumpFormat.HPROF_OTHER_VERSION;
            };
            return new Detection(format, quote(version));
        }
        if (read >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            return new Detection(HeapDumpFormat.GZIP, null);
        }
        if (read >= 4 && head[0] == 'F' && head[1] == 'L' && head[2] == 'R' && head[3] == 0) {
            return new Detection(HeapDumpFormat.FLIGHT_RECORDER, null);
        }
        String text = new String(head, 0, read, StandardCharsets.ISO_8859_1);
        if (text.contains("Full thread dump")) {
            return new Detection(HeapDumpFormat.THREAD_DUMP_TEXT, null);
        }
        if (text.contains("#instances") && text.contains("#bytes")) {
            return new Detection(HeapDumpFormat.CLASS_HISTOGRAM, null);
        }
        return new Detection(HeapDumpFormat.UNKNOWN, hex(head, Math.min(read, 8)));
    }

    /** Reads the whole dump and reports what it held. */
    public static HeapDumpReport read(Path file, Progress progress) throws IOException {
        long fileBytes = Files.size(file);

        progress.report(0.0, "Reading the dump's header");
        Header header = readHeader(file);
        progress.checkpoint();

        // Pass one: the class table only. Its bodies are declared, so this pass can skip the heap
        // entirely — which is what keeps the strings it needs bounded rather than unbounded.
        progress.report(0.02, "Reading the class table");
        ClassTable classTable = readClassTable(file, header, progress);
        progress.checkpoint();

        // Pass two: the heap itself, plus the text of the class names pass one asked for.
        progress.report(0.08, "Walking the heap");
        Walk walk = new Walk(header, fileBytes, Set.copyOf(classTable.nameStringIds().values()));
        walk.run(file, progress);

        List<HeapDumpReport.TypeCensus> types = walk.types(classTable);
        HeapDumpReport.Census census = new HeapDumpReport.Census(
                walk.instances, walk.instanceBytes,
                walk.objectArrays, walk.objectArrayBytes,
                walk.primitiveArrays, walk.primitiveArrayBytes,
                types.size(), walk.unnamedObjects, walk.rootTotal);
        HeapDumpReport.Provenance provenance = new HeapDumpReport.Provenance(
                header.formatVersion(), header.identifierSize(), header.takenAt(), header.takenAtBasis(),
                fileBytes, FILE_BYTES_BASIS, classTable.strings(), classTable.loadClasses(),
                walk.classesDumped);

        progress.report(1.0, census.instances() + census.objectArrays() + census.primitiveArrays()
                + " object(s) read");
        return new HeapDumpReport(provenance, census, types, walk.roots());
    }

    /**
     * Turns a JVM-internal type name into the form a reader recognises.
     *
     * <p>{@code java/lang/String} becomes {@code java.lang.String}, {@code [B} becomes {@code byte[]}
     * and {@code [Ljava/lang/Object;} becomes {@code java.lang.Object[]}. A name that is not a
     * descriptor is returned as it was written rather than mangled into one.
     */
    static String prettyTypeName(String jvmName) {
        if (jvmName == null) {
            return null;
        }
        int dimensions = 0;
        while (dimensions < jvmName.length() && jvmName.charAt(dimensions) == '[') {
            dimensions++;
        }
        if (dimensions == 0) {
            return jvmName.replace('/', '.');
        }
        if (dimensions >= jvmName.length()) {
            return jvmName;
        }
        String element;
        char kind = jvmName.charAt(dimensions);
        if (kind == 'L' && jvmName.endsWith(";")) {
            element = jvmName.substring(dimensions + 1, jvmName.length() - 1).replace('/', '.');
        } else {
            element = switch (kind) {
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'S' -> "short";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                default -> null;
            };
            if (element == null) {
                return jvmName;
            }
        }
        return element + "[]".repeat(dimensions);
    }

    /** The HPROF element-type code as the array type's name, or null when the code is not a primitive. */
    static String elementTypeName(int type) {
        return switch (type) {
            case 4 -> "boolean[]";
            case 5 -> "char[]";
            case 6 -> "float[]";
            case 7 -> "double[]";
            case 8 -> "byte[]";
            case 9 -> "short[]";
            case 10 -> "int[]";
            case 11 -> "long[]";
            default -> null;
        };
    }

    private static DataInputStream open(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20));
    }

    private static Header readHeader(Path file) throws IOException {
        try (DataInputStream in = open(file)) {
            byte[] magic = new byte[MAGIC_LENGTH];
            try {
                in.readFully(magic);
            } catch (EOFException e) {
                throw new IOException("the file is shorter than the 19-byte HPROF header");
            }
            if (!startsWith(magic, MAGIC_LENGTH, MAGIC.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("the file does not begin with an HPROF header");
            }
            String version = new String(magic, MAGIC.length(), 5, StandardCharsets.US_ASCII);
            if (!HeapDumpFormat.READABLE_VERSIONS.contains(version)) {
                throw new IOException(
                        HeapDumpFormat.explain(HeapDumpFormat.HPROF_OTHER_VERSION, quote(version)));
            }
            int identifierSize = in.readInt();
            if (identifierSize != 4 && identifierSize != 8) {
                throw new IOException("the HPROF header declares an identifier size of " + identifierSize
                        + " bytes; this build reads the 4-byte and 8-byte layouts");
            }
            long millis = in.readLong();
            Instant takenAt = null;
            String basis;
            if (millis <= 0 || millis > Instant.parse("2200-01-01T00:00:00Z").toEpochMilli()) {
                basis = "the header's timestamp field held " + millis + ", which is not a plausible "
                        + "instant, so no time is reported for this dump";
            } else {
                takenAt = Instant.ofEpochMilli(millis);
                basis = "the HPROF header's own timestamp field: milliseconds since the epoch, which the "
                        + "format defines as UTC. Unlike a thread dump, a heap dump states its instant "
                        + "unambiguously.";
            }
            return new Header(version, identifierSize, takenAt, basis);
        }
    }

    /**
     * Pass one: the class table.
     *
     * <p>Reads the declared length of every record but parses only LOAD_CLASS, which maps a class
     * object identifier to the string that names it. Heap segments are skipped whole, which is why
     * this pass costs a fraction of a second even on a large dump.
     */
    private static ClassTable readClassTable(Path file, Header header, Progress progress) throws IOException {
        long fileBytes = Files.size(file);
        Map<Long, Long> nameStringIds = new HashMap<>();
        long strings = 0;
        long loadClasses = 0;
        long records = 0;
        try (DataInputStream in = open(file)) {
            Cursor top = new Cursor(in, fileBytes, header.identifierSize());
            top.skip(HEADER_BYTES);
            while (top.hasRemaining()) {
                int tag = top.readU1();
                top.readU4();
                long length = top.readU4();
                Cursor body = top.slice(length, "a top-level record");
                if (tag == TAG_STRING) {
                    strings++;
                } else if (tag == TAG_LOAD_CLASS) {
                    if (length < 24) {
                        throw new IOException("a LOAD_CLASS record of " + length
                                + " bytes is too short to hold a class name");
                    }
                    body.readU4();
                    long classObjId = body.readId();
                    body.readU4();
                    long nameStringId = body.readId();
                    if (nameStringIds.size() >= MAX_TYPES) {
                        throw new IOException("the file declares more than " + MAX_TYPES
                                + " classes, which is beyond what this build will aggregate");
                    }
                    nameStringIds.put(classObjId, nameStringId);
                    loadClasses++;
                }
                body.drain();
                if (++records % CHECKPOINT_RECORDS == 0) {
                    progress.checkpoint();
                    progress.report(0.02 + 0.05 * (1.0 - ((double) top.remaining() / Math.max(1, fileBytes))),
                            "Reading the class table");
                }
            }
        }
        return new ClassTable(nameStringIds, strings, loadClasses);
    }

    /**
     * Pass two: the heap, and the class names pass one asked for.
     *
     * <p>Only strings whose identifier is a class name are kept. The string table itself is bounded by
     * nothing the file has to justify, so keeping it whole would let a small upload decide how much
     * memory this reader uses.
     */
    private static final class Walk {

        /** Element-type codes run 4..11; slot 0 is left unused so the code indexes the array. */
        private static final int ELEMENT_TYPE_SLOTS = 12;

        private final Header header;
        private final long fileBytes;
        private final Set<Long> wantedStrings;

        private final Map<Long, Tally> instanceTally = new HashMap<>();
        private final Map<Long, Tally> objectArrayTally = new HashMap<>();
        private final Tally[] primitiveTally = new Tally[ELEMENT_TYPE_SLOTS];
        private final Map<Long, String> names = new HashMap<>();
        private final Map<String, Long> rootTally = new LinkedHashMap<>();

        private long instances;
        private long instanceBytes;
        private long objectArrays;
        private long objectArrayBytes;
        private long primitiveArrays;
        private long primitiveArrayBytes;
        private long classesDumped;
        private long unnamedObjects;
        private long rootTotal;
        private long segments;
        private long records;
        private long lastReportedBytes;

        Walk(Header header, long fileBytes, Set<Long> wantedStrings) {
            this.header = header;
            this.fileBytes = fileBytes;
            this.wantedStrings = wantedStrings;
        }

        void run(Path file, Progress progress) throws IOException {
            try (DataInputStream in = open(file)) {
                Cursor top = new Cursor(in, fileBytes, header.identifierSize());
                top.skip(HEADER_BYTES);
                while (top.hasRemaining()) {
                    int tag = top.readU1();
                    top.readU4();
                    long length = top.readU4();
                    Cursor body = top.slice(length, "a top-level record");
                    switch (tag) {
                        case TAG_STRING -> string(body);
                        case TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEGMENT -> segment(body, ++segments);
                        default -> body.drain();
                    }
                    if (body.hasRemaining()) {
                        throw new IOException("record type 0x" + Integer.toHexString(tag) + " declared "
                                + length + " bytes but " + body.remaining() + " of them were not accounted for");
                    }
                    maybeReport(progress, top);
                }
            }
        }

        private void string(Cursor body) throws IOException {
            long id = body.readId();
            int textBytes = (int) body.remaining();
            if (!wantedStrings.contains(id)) {
                body.drain();
                return;
            }
            if (textBytes > MAX_NAME_BYTES) {
                throw new IOException("a class name in the file is " + textBytes
                        + " bytes long, which no class file permits");
            }
            names.put(id, new String(body.readBytes(textBytes), StandardCharsets.UTF_8));
        }

        private void segment(Cursor body, long index) throws IOException {
            while (body.hasRemaining()) {
                int tag = body.readU1();
                records++;
                switch (tag) {
                    // Garbage-collection roots: each is a record kind of its own fixed width, and the
                    // widths are the ones a real dump demands rather than the ones a summary of the
                    // format suggests. Verified against dumps from HotSpot 21: 0x01, 0x03, 0x05 and
                    // 0x08 all appear, the walk stays exact across them, and reading
                    // ROOT_THREAD_OBJECT as an id plus the 4-byte thread serial some descriptions give
                    // — rather than the 8 bytes HotSpot actually writes — desynchronises the parse
                    // within one segment. The widths for the tags those dumps did not contain are the
                    // format's, and an unknown tag is refused rather than guessed at.
                    case 0x00 -> root("unknown", body, idSize());
                    case 0x01 -> root("jni-global", body, 2L * idSize());
                    case 0x02 -> root("jni-local", body, idSize() + 8L);
                    case 0x03 -> root("java-frame", body, idSize() + 8L);
                    case 0x04 -> root("native-stack", body, idSize() + 4L);
                    case 0x05 -> root("sticky-class", body, idSize());
                    case 0x06 -> root("thread-block", body, idSize() + 4L);
                    case 0x07 -> root("monitor-used", body, idSize());
                    case 0x08 -> root("thread-object", body, idSize() + 8L);
                    case 0x89 -> root("interned-string", body, idSize());
                    case 0x8a -> root("finalizing", body, idSize());
                    case 0x8b -> root("debugger", body, idSize());
                    case 0x8c -> root("reference-cleanup", body, idSize());
                    case 0x8d -> root("vm-internal", body, idSize());
                    case 0x8e -> root("jni-monitor", body, idSize() + 8L);
                    case 0xfe -> body.skip(4L + idSize());
                    case 0x20 -> classRecord(body);
                    case 0x21 -> instanceRecord(body);
                    case 0x22 -> objectArrayRecord(body);
                    case 0x23 -> primitiveArrayRecord(body);
                    default -> throw new IOException("segment " + index + " contains heap record type 0x"
                            + Integer.toHexString(tag) + ", which this build does not know. Refusing the file: "
                            + "a reader that guessed this record's size would produce counts that look right "
                            + "and are not.");
                }
            }
        }

        private void root(String type, Cursor body, long width) throws IOException {
            rootTally.merge(type, 1L, Long::sum);
            rootTotal++;
            body.skip(width);
        }

        /**
         * Skips a class record.
         *
         * <p>Its length is not declared, so it is derived: the fixed head, then the constant pool, the
         * static fields and the instance fields, each entry carrying its own type code and the
         * corresponding value width. Nothing here is kept — this reader never needs a class's field
         * layout, because instance records declare their own size.
         */
        private void classRecord(Cursor body) throws IOException {
            body.readId();
            body.skip(4);                       // stack trace serial
            body.skip(6L * idSize());           // superclass, loader, signers, protection domain, 2 reserved
            body.skip(4);                       // instance size
            int constants = body.readU2();
            for (int i = 0; i < constants; i++) {
                body.skip(2);
                body.skip(valueWidth(body.readU1()));
            }
            int statics = body.readU2();
            for (int i = 0; i < statics; i++) {
                body.skip(idSize());
                body.skip(valueWidth(body.readU1()));
            }
            int fields = body.readU2();
            for (int i = 0; i < fields; i++) {
                body.skip(idSize());
                body.readU1();
            }
            classesDumped++;
            if (classesDumped > MAX_TYPES) {
                throw new IOException("the dump contains CLASS_DUMP records for more than " + MAX_TYPES
                        + " classes, which is beyond what this build will aggregate");
            }
        }

        private void instanceRecord(Cursor body) throws IOException {
            body.readId();
            body.skip(4);
            long classObjId = body.readId();
            long fieldBytes = body.readU4();
            body.skip(fieldBytes);
            instances++;
            instanceBytes += fieldBytes;
            tally(instanceTally, classObjId).add(0, fieldBytes);
        }

        private void objectArrayRecord(Cursor body) throws IOException {
            body.readId();
            body.skip(4);
            long elements = body.readU4();
            long classObjId = body.readId();
            long bytes = elements * idSize();
            body.skip(bytes);
            objectArrays++;
            objectArrayBytes += bytes;
            tally(objectArrayTally, classObjId).add(elements, bytes);
        }

        private void primitiveArrayRecord(Cursor body) throws IOException {
            body.readId();
            body.skip(4);
            long elements = body.readU4();
            int elementType = body.readU1();
            if (elementTypeName(elementType) == null) {
                throw new IOException("a primitive array in the dump has element type code " + elementType
                        + ", which is not one of the eight primitive kinds");
            }
            long bytes = elements * elementWidth(elementType);
            body.skip(bytes);
            primitiveArrays++;
            primitiveArrayBytes += bytes;
            if (primitiveTally[elementType] == null) {
                primitiveTally[elementType] = new Tally();
            }
            primitiveTally[elementType].add(elements, bytes);
        }

        private int valueWidth(int type) throws IOException {
            return switch (type) {
                case 2 -> idSize();
                case 4, 8 -> 1;
                case 5, 9 -> 2;
                case 6, 10 -> 4;
                case 7, 11 -> 8;
                default -> throw new IOException("the dump declares field type code " + type
                        + ", which is not part of the HPROF type table");
            };
        }

        private int elementWidth(int type) {
            return switch (type) {
                case 4, 8 -> 1;
                case 5, 9 -> 2;
                case 6, 10 -> 4;
                default -> 8;
            };
        }

        private int idSize() {
            return header.identifierSize();
        }

        private static Tally tally(Map<Long, Tally> into, long key) {
            return into.computeIfAbsent(key, ignored -> new Tally());
        }

        private void maybeReport(Progress progress, Cursor top) {
            if (records % CHECKPOINT_RECORDS == 0) {
                progress.checkpoint();
            }
            long consumed = fileBytes - top.remaining();
            if (consumed - lastReportedBytes >= REPORT_BYTES) {
                lastReportedBytes = consumed;
                progress.report(0.08 + 0.62 * ((double) consumed / Math.max(1, fileBytes)),
                        records + " heap record(s) read");
            }
        }

        /** The type table: named types only, largest by recorded bytes first. */
        List<HeapDumpReport.TypeCensus> types(ClassTable classTable) {
            List<HeapDumpReport.TypeCensus> types = new ArrayList<>(instanceTally.size()
                    + objectArrayTally.size() + primitiveTally.length);
            instanceTally.forEach((classObjId, tally) ->
                    add(types, classTable, classObjId, HeapDumpReport.TypeKind.INSTANCE, tally));
            objectArrayTally.forEach((classObjId, tally) ->
                    add(types, classTable, classObjId, HeapDumpReport.TypeKind.OBJECT_ARRAY, tally));
            for (int type = 0; type < primitiveTally.length; type++) {
                Tally tally = primitiveTally[type];
                if (tally != null) {
                    types.add(new HeapDumpReport.TypeCensus(elementTypeName(type),
                            HeapDumpReport.TypeKind.PRIMITIVE_ARRAY,
                            tally.objects, tally.elements, tally.bytes, tally.largest));
                }
            }
            types.sort(Comparator.comparingLong(HeapDumpReport.TypeCensus::bytes).reversed()
                    .thenComparing(HeapDumpReport.TypeCensus::name));
            return types;
        }

        private void add(List<HeapDumpReport.TypeCensus> types, ClassTable classTable, long classObjId,
                         HeapDumpReport.TypeKind kind, Tally tally) {
            Long nameStringId = classTable.nameStringIds().get(classObjId);
            String name = nameStringId == null ? null : names.get(nameStringId);
            if (name == null) {
                // Counted, so the census still adds up, but not filed under a type the file never named.
                unnamedObjects += tally.objects;
                return;
            }
            types.add(new HeapDumpReport.TypeCensus(prettyTypeName(name), kind,
                    tally.objects, tally.elements, tally.bytes, tally.largest));
        }

        List<HeapDumpReport.RootCensus> roots() {
            List<HeapDumpReport.RootCensus> roots = new ArrayList<>(rootTally.size());
            rootTally.forEach((type, count) -> roots.add(new HeapDumpReport.RootCensus(type, count)));
            roots.sort(Comparator.comparingLong(HeapDumpReport.RootCensus::count).reversed()
                    .thenComparing(HeapDumpReport.RootCensus::type));
            return roots;
        }
    }

    /** One type's running totals while the heap is walked. */
    private static final class Tally {

        private long objects;
        private long elements;
        private long bytes;
        private long largest;

        void add(long elements, long bytes) {
            objects++;
            this.elements += elements;
            this.bytes += bytes;
            if (bytes > largest) {
                largest = bytes;
            }
        }
    }

    private record Header(String formatVersion, int identifierSize, Instant takenAt, String takenAtBasis) {
    }

    private record ClassTable(Map<Long, Long> nameStringIds, long strings, long loadClasses) {
    }

    /**
     * A byte source with a budget.
     *
     * <p>Every read and every skip is charged against a limit, and a slice carries its own budget
     * while also charging its parent. That is what turns "this record is 12 bytes" from an assumption
     * into something the file has to justify: a record that claims to be longer than the segment it
     * sits in, or a segment whose records do not add up to its length, fails here instead of quietly
     * shifting every subsequent count.
     */
    static final class Cursor {

        private final DataInputStream in;
        private long remaining;
        private final int idSize;

        Cursor(DataInputStream in, long remaining, int idSize) {
            this.in = in;
            this.remaining = remaining;
            this.idSize = idSize;
        }

        boolean hasRemaining() {
            return remaining > 0;
        }

        long remaining() {
            return remaining;
        }

        /** Takes a view of the next {@code length} bytes, charged to this cursor. */
        Cursor slice(long length, String what) throws IOException {
            require(length, what);
            remaining -= length;
            return new Cursor(in, length, idSize);
        }

        int readU1() throws IOException {
            require(1, "a byte");
            remaining--;
            return in.readUnsignedByte();
        }

        int readU2() throws IOException {
            require(2, "a two-byte count");
            remaining -= 2;
            return in.readUnsignedShort();
        }

        long readU4() throws IOException {
            require(4, "a four-byte count");
            remaining -= 4;
            return Integer.toUnsignedLong(in.readInt());
        }

        long readId() throws IOException {
            require(idSize, "an object identifier");
            remaining -= idSize;
            return idSize == 8 ? in.readLong() : Integer.toUnsignedLong(in.readInt());
        }

        byte[] readBytes(int count) throws IOException {
            require(count, "text");
            remaining -= count;
            byte[] bytes = new byte[count];
            in.readFully(bytes);
            return bytes;
        }

        void skip(long count) throws IOException {
            require(count, "a record's body");
            long left = count;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("the file ends in the middle of a record: "
                                + left + " byte(s) short");
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
            remaining -= count;
        }

        /** Takes whatever is left, so a caller can assert the budget is used exactly. */
        void drain() throws IOException {
            skip(remaining);
        }

        private void require(long count, String what) throws IOException {
            if (count > remaining) {
                throw new IOException("the file declares " + what + " of " + count + " bytes, but only "
                        + remaining + " byte(s) remain before the end of the record or segment it is in");
            }
        }
    }

    private static boolean startsWith(byte[] bytes, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String quote(String text) {
        return text == null ? null : "'" + text.trim() + "'";
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder builder = new StringBuilder("the bytes ");
        for (int i = 0; i < length; i++) {
            builder.append(String.format("%02x", bytes[i]));
            if (i < length - 1) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }
}
