package dev.repopilot.runtime.heapdump;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reader against files written byte by byte, and against one real dump.
 *
 * <p>The synthetic files here are how the refusal paths are reached: a record that overruns its
 * segment, a record tag the format does not define, an identifier width that is neither 4 nor 8.
 * Those are the cases a reader must fail on, because the failure mode that matters is not a crash —
 * it is a parse that stays inside the file and returns numbers that are wrong in a way nothing
 * downstream can see.
 */
class HeapDumpReaderTest {

    private static final long NOW = 1_792_391_235_908L;

    private static final HeapDumpReader.Progress NO_PROGRESS = new HeapDumpReader.Progress() {

        @Override
        public void checkpoint() {
        }

        @Override
        public void report(double fraction, String detail) {
        }
    };

    // ---------------------------------------------------------------- detection

    @Test
    @DisplayName("recognises the HPROF header both readable versions carry")
    void detectsReadableVersions() throws Exception {
        assertThat(HeapDumpReader.detect(file(hprof("1.0.2", 8, NOW))).format())
                .isEqualTo(HeapDumpFormat.HPROF_1_0_2);
        assertThat(HeapDumpReader.detect(file(hprof("1.0.1", 8, NOW))).format())
                .isEqualTo(HeapDumpFormat.HPROF_1_0_1);
    }

    @Test
    @DisplayName("refuses another HPROF version by name, and says which")
    void refusesOtherVersionsByName() throws Exception {
        HeapDumpReader.Detection detection = HeapDumpReader.detect(file(hprof("1.0.3", 8, NOW)));
        assertThat(detection.format()).isEqualTo(HeapDumpFormat.HPROF_OTHER_VERSION);
        assertThat(HeapDumpFormat.explain(detection.format(), detection.head()))
                .contains("1.0.3")
                .contains("1.0.2")
                .contains("ART");
    }

    @Test
    @DisplayName("names a gzip stream as compressed and gives the command that decompresses it")
    void detectsGzip() throws Exception {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(hprof("1.0.2", 8, NOW));
        }
        HeapDumpReader.Detection detection = HeapDumpReader.detect(file(gzipped.toByteArray()));
        assertThat(detection.format()).isEqualTo(HeapDumpFormat.GZIP);
        assertThat(HeapDumpFormat.explain(detection.format(), null))
                .contains("gzip")
                .contains("gzip -d")
                .contains("-gz");
    }

    @Test
    @DisplayName("names a JFR recording and a thread dump rather than calling them corrupt")
    void detectsOtherEvidenceTypes() throws Exception {
        assertThat(HeapDumpReader.detect(file("FLR\u0000\u0000\u0000rest".getBytes(
                StandardCharsets.ISO_8859_1))).format()).isEqualTo(HeapDumpFormat.FLIGHT_RECORDER);
        assertThat(HeapDumpReader.detect(file(("1234:\n2026-10-19 12:00:00\nFull thread dump "
                + "Java HotSpot(TM)\n").getBytes(StandardCharsets.UTF_8))).format())
                .isEqualTo(HeapDumpFormat.THREAD_DUMP_TEXT);
    }

    @Test
    @DisplayName("names a class histogram, the file most likely to be mistaken for a dump")
    void detectsAClassHistogram() throws Exception {
        String histogram = "1234:\n num     #instances         #bytes  class name (module)\n"
                + "-------------------------------------------------------\n"
                + "   1:         13521        2046144  [B (java.base@21.0.5)\n";
        HeapDumpReader.Detection detection = HeapDumpReader.detect(
                file(histogram.getBytes(StandardCharsets.UTF_8)));
        assertThat(detection.format()).isEqualTo(HeapDumpFormat.CLASS_HISTOGRAM);

        String refusal = HeapDumpFormat.explain(detection.format(), null);
        assertThat(refusal)
                .contains("histogram")
                .contains("jmap -histo")
                .contains("GC.class_histogram")
                .contains("jcmd <pid> GC.heap_dump");
    }

    @Test
    @DisplayName("calls an empty file empty and unknown bytes unknown")
    void detectsEmptyAndUnknown() throws Exception {
        assertThat(HeapDumpReader.detect(file(new byte[0])).format()).isEqualTo(HeapDumpFormat.EMPTY);
        HeapDumpReader.Detection detection = HeapDumpReader.detect(
                file(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}));
        assertThat(detection.format()).isEqualTo(HeapDumpFormat.UNKNOWN);
        assertThat(detection.head()).contains("ca fe ba be");
        assertThat(HeapDumpFormat.explain(detection.format(), detection.head()))
                .contains("ca fe ba be")
                .contains("JAVA PROFILE");
    }

    // ---------------------------------------------------------------- reading

    @Test
    @DisplayName("reads a hand-built dump into an exact census")
    void readsAnExactCensus(@TempDir Path directory) throws Exception {
        HeapDumpReport report = HeapDumpReader.read(file(directory, minimal(), "minimal.hprof"),
                NO_PROGRESS);

        assertThat(report.provenance().formatVersion()).isEqualTo("1.0.2");
        assertThat(report.provenance().identifierSize()).isEqualTo(8);
        assertThat(report.provenance().takenAt()).isEqualTo(Instant.ofEpochMilli(NOW));
        assertThat(report.provenance().classesLoaded()).isEqualTo(2);
        assertThat(report.provenance().classesDumped()).isEqualTo(1);
        assertThat(report.provenance().strings()).isEqualTo(2);

        HeapDumpReport.Census census = report.census();
        assertThat(census.instances()).isEqualTo(1);
        assertThat(census.instanceBytes()).isEqualTo(12);
        assertThat(census.objectArrays()).isEqualTo(1);
        assertThat(census.objectArrayBytes()).isEqualTo(32);
        assertThat(census.primitiveArrays()).isEqualTo(1);
        assertThat(census.primitiveArrayBytes()).isEqualTo(8);
        assertThat(census.unnamedObjects()).isZero();
        assertThat(census.roots()).isEqualTo(3);
        assertThat(report.objects()).isEqualTo(3);
        assertThat(report.objectBytes()).isEqualTo(52);

        assertThat(report.types())
                .extracting(HeapDumpReport.TypeCensus::name)
                .containsExactlyInAnyOrder("probe.Thing", "java.lang.Object[]", "byte[]");
        assertThat(report.types()).allSatisfy(type -> assertThat(type.objects()).isEqualTo(1));

        assertThat(report.rootCounts())
                .containsEntry("sticky-class", 2L)
                .containsEntry("thread-object", 1L);
    }

    @Test
    @DisplayName("derives an array type's name from the class the dump named")
    void resolvesArrayTypeNames(@TempDir Path directory) throws Exception {
        HeapDumpReport report = HeapDumpReader.read(file(directory, minimal(), "minimal.hprof"),
                NO_PROGRESS);
        assertThat(report.types())
                .filteredOn(type -> type.kind() == HeapDumpReport.TypeKind.OBJECT_ARRAY)
                .singleElement()
                .satisfies(type -> {
                    assertThat(type.name()).isEqualTo("java.lang.Object[]");
                    assertThat(type.elements()).isEqualTo(4);
                    assertThat(type.bytes()).isEqualTo(32);
                });
        assertThat(report.types())
                .filteredOn(type -> type.name().equals("byte[]"))
                .singleElement()
                .satisfies(type -> assertThat(type.kind())
                        .isEqualTo(HeapDumpReport.TypeKind.PRIMITIVE_ARRAY));
    }

    @Test
    @DisplayName("counts an object whose type the file never named, without inventing a name for it")
    void countsObjectsWithoutAType(@TempDir Path directory) throws Exception {
        byte[] bytes = hprof("1.0.2", 8, NOW,
                string(1, "probe/Thing"),
                loadClass(0x1000L, 1),
                segment(List.of(instanceRecord(0x2000L, 0x9999L, 16))));
        HeapDumpReport report = HeapDumpReader.read(file(directory, bytes, "unnamed.hprof"), NO_PROGRESS);

        assertThat(report.census().instances()).isEqualTo(1);
        assertThat(report.census().unnamedObjects()).isEqualTo(1);
        assertThat(report.census().namedTypes()).isZero();
        assertThat(report.types()).isEmpty();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("refuses a heap record tag the format does not define rather than guessing its size")
    void refusesAnUnknownHeapRecordTag(@TempDir Path directory) throws Exception {
        byte[] bytes = hprof("1.0.2", 8, NOW, rawSegment(5, new byte[] {0x77, 1, 2, 3, 4}));
        assertThatThrownBy(() -> HeapDumpReader.read(file(directory, bytes, "unknown-tag.hprof"),
                NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("0x77")
                .hasMessageContaining("does not know");
    }

    @Test
    @DisplayName("refuses a record that claims more bytes than the segment holding it can hold")
    void refusesARecordThatOverrunsItsSegment(@TempDir Path directory) throws Exception {
        byte[] instance = instanceRecord(0x2000L, 0x1000L, 1000);
        byte[] bytes = hprof("1.0.2", 8, NOW, rawSegment(30, instance));
        assertThatThrownBy(() -> HeapDumpReader.read(file(directory, bytes, "overrun.hprof"),
                NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("byte(s) remain");
    }

    @Test
    @DisplayName("refuses a top-level record whose declared length runs past the end of the file")
    void refusesATruncatedTopLevelRecord(@TempDir Path directory) throws Exception {
        byte[] bytes = hprof("1.0.2", 8, NOW, topLevel(0x01, 9, new byte[] {1, 2, 3, 4}));
        assertThatThrownBy(() -> HeapDumpReader.read(file(directory, bytes, "truncated.hprof"),
                NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("remain");
    }

    @Test
    @DisplayName("refuses a header that declares an identifier width the format does not allow")
    void refusesAnOddIdentifierSize(@TempDir Path directory) throws Exception {
        byte[] bytes = hprof("1.0.2", 6, NOW);
        assertThatThrownBy(() -> HeapDumpReader.read(file(directory, bytes, "padded.hprof"), NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identifier size")
                .hasMessageContaining("6");
    }
    @Test
    @DisplayName("refuses a file that is not an HPROF file at all")
    void refusesSomethingElse(@TempDir Path directory) throws Exception {
        assertThatThrownBy(() -> HeapDumpReader.read(
                file(directory, "just text\n".getBytes(StandardCharsets.UTF_8), "notes.hprof"),
                NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HPROF header");
    }

    @Test
    @DisplayName("refuses a file too short to hold the header")
    void refusesAShortFile(@TempDir Path directory) throws Exception {
        assertThatThrownBy(() -> HeapDumpReader.read(
                file(directory, "JAVA PROFILE".getBytes(StandardCharsets.US_ASCII), "short.hprof"),
                NO_PROGRESS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("19-byte");
    }

    @Test
    @DisplayName("reports no instant when the header's timestamp field is not a plausible one")
    void reportsNoInstantForANonsenseTimestamp(@TempDir Path directory) throws Exception {
        HeapDumpReport report = HeapDumpReader.read(
                file(directory, hprof("1.0.2", 8, -1), "ancient.hprof"), NO_PROGRESS);
        assertThat(report.provenance().takenAt()).isNull();
        assertThat(report.provenance().takenAtBasis()).contains("-1");
    }

    // ---------------------------------------------------------------- names

    @Test
    @DisplayName("renders JVM-internal type names the way a reader expects them")
    void rendersTypeNames() {
        assertThat(HeapDumpReader.prettyTypeName("java/lang/String")).isEqualTo("java.lang.String");
        assertThat(HeapDumpReader.prettyTypeName("probe/Thing$Inner")).isEqualTo("probe.Thing$Inner");
        assertThat(HeapDumpReader.prettyTypeName("[B")).isEqualTo("byte[]");
        assertThat(HeapDumpReader.prettyTypeName("[C")).isEqualTo("char[]");
        assertThat(HeapDumpReader.prettyTypeName("[Ljava/lang/String;")).isEqualTo("java.lang.String[]");
        assertThat(HeapDumpReader.prettyTypeName("[[I")).isEqualTo("int[][]");
        assertThat(HeapDumpReader.prettyTypeName("[[Ljava/lang/Object;"))
                .isEqualTo("java.lang.Object[][]");
        assertThat(HeapDumpReader.prettyTypeName("not-a-descriptor")).isEqualTo("not-a-descriptor");
    }

    // ---------------------------------------------------------------- against a real dump

    @Test
    @DisplayName("reads a heap dump of a real JVM and agrees with the population it was built from")
    void readsARealHeapDump(@TempDir Path directory) throws Exception {
        assumeTrue(TestHeapDump.isAvailable(), "jcmd is not available in this JVM");
        Path dump = TestHeapDump.capture(directory);

        HeapDumpReport report = HeapDumpReader.read(dump, NO_PROGRESS);

        assertThat(report.provenance().formatVersion()).contains("1.0.");
        assertThat(report.provenance().identifierSize()).isIn(4, 8);
        assertThat(report.provenance().takenAt()).isNotNull();
        assertThat(report.provenance().classesLoaded()).isGreaterThan(100);
        assertThat(report.objects()).isGreaterThan(1000);

        // The subject's own types, whose counts are known exactly. Everything else in the dump is the
        // JDK's, which is why only these are asserted.
        String subject = HeapDumpSubject.class.getName();
        String node = subject + "$Node";
        assertThat(typeNamed(report, node).objects()).isEqualTo(HeapDumpSubject.NODES);
        assertThat(typeNamed(report, node).bytes())
                .isEqualTo(HeapDumpSubject.NODES * HeapDumpSubject.NODE_FIELD_BYTES);

        String bucket = subject + "$Bucket";
        assertThat(typeNamed(report, bucket).objects()).isEqualTo(HeapDumpSubject.BUCKETS);
        assertThat(typeNamed(report, bucket).bytes())
                .isEqualTo(HeapDumpSubject.BUCKETS * HeapDumpSubject.BUCKET_FIELD_BYTES);

        // The arrays: at least the ones the subject allocated, exact in the bytes they hold.
        assertThat(typeNamed(report, "byte[]").objects()).isGreaterThanOrEqualTo(
                HeapDumpSubject.BYTE_ARRAYS + HeapDumpSubject.BUCKETS);
        assertThat(typeNamed(report, "byte[]").bytes()).isGreaterThanOrEqualTo(
                (long) HeapDumpSubject.BYTE_ARRAYS * HeapDumpSubject.BYTE_ARRAY_SIZE
                        + (long) HeapDumpSubject.BUCKETS * HeapDumpSubject.BUCKET_SIZE);
        assertThat(typeNamed(report, "int[]").objects()).isGreaterThanOrEqualTo(HeapDumpSubject.INT_ARRAYS);
        assertThat(typeNamed(report, "int[]").bytes()).isGreaterThanOrEqualTo(
                (long) HeapDumpSubject.INT_ARRAYS * HeapDumpSubject.INT_ARRAY_SIZE * 4);

        // Every type is named by the file, and the per-type table adds up to the whole census: the
        // two are computed from the same walk, so a disagreement would mean one of them lies.
        assertThat(report.census().unnamedObjects()).isZero();
        assertThat(report.types().stream().mapToLong(HeapDumpReport.TypeCensus::bytes).sum())
                .isEqualTo(report.objectBytes());
        assertThat(report.types().stream().mapToLong(HeapDumpReport.TypeCensus::objects).sum())
                .isEqualTo(report.objects());

        // Names are in source form, not the JVM's internal encoding, and the JVM's own classes resolve.
        assertThat(report.types()).noneSatisfy(type -> assertThat(type.name()).contains("/"));
        assertThat(report.types()).noneSatisfy(type -> assertThat(type.name()).startsWith("["));
        assertThat(report.types()).anySatisfy(type -> assertThat(type.name()).isEqualTo("java.lang.String"));

        // A live JVM has threads, so the dump recorded thread roots; a heap with roots is a heap the
        // reader could walk.
        assertThat(report.rootCounts()).containsKey("thread-object");
        assertThat(report.census().roots()).isGreaterThan(0);
    }

    private static HeapDumpReport.TypeCensus typeNamed(HeapDumpReport report, String name) {
        return report.types().stream()
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no type named " + name + " in "
                        + report.types().size() + " types"));
    }

    // ---------------------------------------------------------------- fixtures

    /** A file in a directory of its own, for the detection tests that never read past the head. */
    private static Path file(byte[] bytes) throws IOException {
        return file(Files.createTempDirectory("hprof-detect"), bytes, "probe.hprof");
    }

    private static Path file(Path directory, byte[] bytes, String name) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    /** The dump these tests assert against: one instance, one object array, one byte array, three roots. */
    private static byte[] minimal() throws IOException {
        return hprof("1.0.2", 8, NOW,
                string(1, "probe/Thing"),
                string(2, "[Ljava/lang/Object;"),
                loadClass(0x1000L, 1),
                loadClass(0x5000L, 2),
                segment(List.of(
                        classDump(0x1000L, 2),
                        instanceRecord(0x2000L, 0x1000L, 12),
                        objectArray(0x4000L, 0x5000L, 4),
                        primitiveArray(0x3000L, 8, 8),
                        stickyClassRoot(0x1000L),
                        stickyClassRoot(0x5000L),
                        threadObjectRoot(0x6000L))),
                topLevel(0x2C, 0, new byte[0]));
    }

    private static byte[] hprof(String version, int identifierSize, long timestamp,
                                byte[]... records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(("JAVA PROFILE " + version).getBytes(StandardCharsets.US_ASCII));
        out.writeByte(0);
        out.writeInt(identifierSize);
        out.writeLong(timestamp);
        for (byte[] record : records) {
            out.write(record);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] string(long id, String text) throws IOException {
        byte[] body = new byte[8 + text.length()];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(body);
        buffer.putLong(id).put(text.getBytes(StandardCharsets.UTF_8));
        return topLevel(0x01, body.length, body);
    }

    private static byte[] loadClass(long classObjId, long nameStringId) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt((int) classObjId);
        out.writeLong(classObjId);
        out.writeInt(0);
        out.writeLong(nameStringId);
        return topLevel(0x02, body.size(), body.toByteArray());
    }

    private static byte[] segment(List<byte[]> records) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] record : records) {
            body.write(record);
        }
        return topLevel(0x1C, body.size(), body.toByteArray());
    }

    private static byte[] rawSegment(int declaredLength, byte[] body) throws IOException {
        return topLevel(0x1C, declaredLength, body);
    }

    private static byte[] topLevel(int tag, int length, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(tag);
        out.writeInt((int) (NOW / 1000));
        out.writeInt(length);
        out.write(body);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] classDump(long classObjId, int instanceFields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x20);
        out.writeLong(classObjId);
        out.writeInt(0);
        out.writeLong(0);
        out.writeLong(0);
        out.writeLong(0);
        out.writeLong(0);
        out.writeLong(0);
        out.writeLong(0);
        out.writeInt(32);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(instanceFields);
        for (int i = 0; i < instanceFields; i++) {
            out.writeLong(1);
            out.writeByte(i == 0 ? 2 : 10);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] instanceRecord(long objId, long classObjId, int fieldBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x21);
        out.writeLong(objId);
        out.writeInt(0);
        out.writeLong(classObjId);
        out.writeInt(fieldBytes);
        out.write(new byte[fieldBytes]);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] objectArray(long objId, long arrayClassObjId, int elements) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x22);
        out.writeLong(objId);
        out.writeInt(0);
        out.writeInt(elements);
        out.writeLong(arrayClassObjId);
        out.write(new byte[elements * 8]);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] primitiveArray(long objId, int elementType, int elements) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x23);
        out.writeLong(objId);
        out.writeInt(0);
        out.writeInt(elements);
        out.writeByte(elementType);
        out.write(new byte[elements]);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] stickyClassRoot(long id) throws IOException {
        return root(0x05, id, 0);
    }

    private static byte[] threadObjectRoot(long id) throws IOException {
        // HotSpot writes eight bytes after the identifier here, not the four-byte thread serial that
        // descriptions of the format give. Four desynchronises the parse; eight is what the JDK does.
        return root(0x08, id, 8);
    }

    private static byte[] root(int tag, long id, int trailing) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(tag);
        out.writeLong(id);
        out.write(new byte[trailing]);
        out.flush();
        return bytes.toByteArray();
    }
}
