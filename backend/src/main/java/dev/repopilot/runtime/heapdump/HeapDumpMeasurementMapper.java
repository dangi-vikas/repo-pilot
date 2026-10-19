package dev.repopilot.runtime.heapdump;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link HeapDumpReport} into the measurements RepoPilot stores.
 *
 * <p>A pure function, and the place where every judgement about a dump is recorded. Four rules shape
 * the whole set:
 *
 * <ul>
 *   <li><b>One instant, no trend.</b> A heap dump has no events and no window: it is what the heap
 *       held when the JVM wrote it. Nothing here is a rate, a duration or a change over time, and no
 *       row is compared with another dump.</li>
 *   <li><b>Recorded bytes, not occupied memory.</b> Every byte figure is what the dump wrote for the
 *       objects themselves: an instance's field values, an array's elements. The JVM also gives every
 *       object a header and aligns it, and none of that is in the dump's field data — so these
 *       figures are <em>smaller</em> than the JVM's own estimate for the same objects, which is why a
 *       class near the top of a histogram and a type near the top of this table do not have to agree
 *       on the number. Every row says so.</li>
 *   <li><b>No retained size.</b> Retained size is what would be freed if an object became
 *       unreachable, and it needs a full object graph and a dominator tree. RepoPilot does not
 *       compute one, so the largest type here is a type holding a lot of recorded bytes — not a leak,
 *       and not a problem. Every row says that too.</li>
 *   <li><b>No thresholds and no severity.</b> Whether a heap is "too full" is a policy about a
 *       process's purpose and its heap limit, and this reader has been told neither. Sizes are
 *       reported; judgements are left where they belong.</li>
 * </ul>
 */
public final class HeapDumpMeasurementMapper {

    /** Every key this mapper produces starts here, so a reader can filter one namespace. */
    public static final String NAMESPACE = "heapdump.";

    /**
     * Types reported per table.
     *
     * <p>Twenty, not all of them: a large heap has tens of thousands of types and the tail is noise,
     * but the count that was cut is stated next to the rows rather than left implicit. The point of
     * the number is that a reader can see the shape of the heap without RepoPilot deciding which
     * types are interesting.
     */
    private static final int TOP_N = 20;

    /** What a byte figure is, on every row that carries one. */
    public static final String BYTES_BASIS = "the bytes the dump recorded for these objects themselves: "
            + "for an instance, its field values including the fields it inherits; for an array, its "
            + "element data. It excludes every object header, all class metadata and everything reachable "
            + "from the objects, so it is smaller than the memory they occupy and is not a retained size.";

    /** What a byte figure is not. */
    public static final String NOT_RETAINED = "not a retained size: RepoPilot does not build a dominator "
            + "tree, so a type with a large figure here is a type whose objects hold a lot of recorded "
            + "data, which is not the same as a leak and not a problem statement.";

    /** Why no row is a trend. */
    public static final String SNAPSHOT = "one instant of one process: a heap dump records what the heap "
            + "held when it was written, so nothing derived from it is a rate, a trend or a comparison "
            + "with any other dump.";

    /** What a share is a share of. */
    private static final String BYTES_SHARE_BASIS = "of all the object bytes the dump recorded";
    private static final String OBJECTS_SHARE_BASIS = "of all the objects the dump recorded";

    /**
     * What the census is a census of.
     *
     * <p>This is not a footnote. A histogram of the same JVM taken a second before its dump listed
     * 19 403 objects where the dump holds 18 501, because taking a dump collects first — which is also
     * why {@code GC.heap_dump} has an {@code -all} flag for the objects that are no longer reachable.
     * "How many objects are in this heap" therefore has two defensible answers, and the operator needs
     * to know which one is in front of them.
     */
    public static final String LIVE_ONLY = "the objects the process was still holding when the dump was "
            + "written: taking a dump collects first, so an object that had already become garbage is not "
            + "in the file, and these totals are of live objects rather than of everything allocated.";

    private HeapDumpMeasurementMapper() {
    }

    /** Every measurement one dump yields, in a fixed order. */
    public static List<MeasurementDraft> toMeasurements(HeapDumpReport report) {
        List<MeasurementDraft> drafts = new ArrayList<>();

        census(drafts, report);
        roots(drafts, report);
        largestObject(drafts, report);
        typeTables(drafts, report);

        return drafts;
    }

    /**
     * The whole-heap totals.
     *
     * <p>Both halves of each pair are kept: how many, and how many bytes. A heap can be dominated by a
     * few enormous arrays or by millions of tiny objects, and either figure on its own hides which.
     */
    private static void census(List<MeasurementDraft> drafts, HeapDumpReport report) {
        HeapDumpReport.Census census = report.census();
        long objects = report.objects();
        long objectBytes = report.objectBytes();

        drafts.add(new MeasurementDraft(NAMESPACE + "objects.count", MetricKind.COUNTER,
                MeasurementAggregation.COUNT, objects, "count",
                (int) Math.min(objects, Integer.MAX_VALUE), null, Map.of("note", LIVE_ONLY)));
        drafts.add(size(NAMESPACE + "objects.bytes", objectBytes, objects, null, Map.of(
                "basis", BYTES_BASIS,
                "note", NOT_RETAINED)));

        drafts.add(MeasurementDraft.count(NAMESPACE + "instances.count", census.instances(),
                (int) Math.min(census.instances(), Integer.MAX_VALUE), null));
        drafts.add(size(NAMESPACE + "instances.bytes", census.instanceBytes(), census.instances(), null,
                Map.of("basis", BYTES_BASIS)));

        drafts.add(MeasurementDraft.count(NAMESPACE + "arrays.count", report.arrays(),
                (int) Math.min(report.arrays(), Integer.MAX_VALUE), null));
        drafts.add(size(NAMESPACE + "arrays.bytes",
                census.objectArrayBytes() + census.primitiveArrayBytes(), report.arrays(), null,
                Map.of("basis", BYTES_BASIS)));

        drafts.add(MeasurementDraft.count(NAMESPACE + "arrays.primitive.count", census.primitiveArrays(),
                (int) Math.min(census.primitiveArrays(), Integer.MAX_VALUE), null));
        drafts.add(size(NAMESPACE + "arrays.primitive.bytes", census.primitiveArrayBytes(),
                census.primitiveArrays(), null, Map.of("basis", BYTES_BASIS)));

        drafts.add(MeasurementDraft.count(NAMESPACE + "arrays.object.count", census.objectArrays(),
                (int) Math.min(census.objectArrays(), Integer.MAX_VALUE), null));
        drafts.add(size(NAMESPACE + "arrays.object.bytes", census.objectArrayBytes(), census.objectArrays(),
                null, Map.of("basis", BYTES_BASIS)));

        drafts.add(MeasurementDraft.count(NAMESPACE + "classes.loaded", report.provenance().classesLoaded(),
                (int) Math.min(report.provenance().classesLoaded(), Integer.MAX_VALUE), null));
        drafts.add(MeasurementDraft.count(NAMESPACE + "classes.dumped", report.provenance().classesDumped(),
                (int) Math.min(report.provenance().classesDumped(), Integer.MAX_VALUE), null));
        drafts.add(MeasurementDraft.count(NAMESPACE + "types.count", census.namedTypes(),
                (int) Math.min(census.namedTypes(), Integer.MAX_VALUE), null));

        if (census.unnamedObjects() > 0) {
            drafts.add(new MeasurementDraft(NAMESPACE + "objects.unnamed", MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, census.unnamedObjects(), "count",
                    (int) Math.min(census.unnamedObjects(), Integer.MAX_VALUE), null,
                    Map.of("note", "objects the file never named: they have no LOAD_CLASS record, so "
                            + "RepoPilot counted them but could not say what type they are, and they are "
                            + "not in the per-type tables")));
        }
    }

    /**
     * The dump's garbage-collection roots.
     *
     * <p>Roots are where a collector starts from, and their kinds are a fact about the dump rather
     * than about the application: a heap with interned strings and no JNI globals looks different
     * from one with the reverse. The set is small and fixed, so every kind present gets a row rather
     * than a top-N cut.
     */
    private static void roots(List<MeasurementDraft> drafts, HeapDumpReport report) {
        if (report.census().roots() == 0) {
            return;
        }
        drafts.add(MeasurementDraft.count(NAMESPACE + "roots.count", report.census().roots(),
                (int) Math.min(report.census().roots(), Integer.MAX_VALUE), null));
        for (HeapDumpReport.RootCensus root : report.roots()) {
            drafts.add(MeasurementDraft.count(NAMESPACE + "roots.kind.count", root.count(),
                    (int) Math.min(root.count(), Integer.MAX_VALUE), root.type()));
        }
    }

    /**
     * The single largest object the dump recorded, and the type it belongs to.
     *
     * <p>One object rather than a table, because this is the figure an operator checks first when a
     * heap looks wrong — "is something enormous in here" — and it is the one figure a byte total
     * cannot answer.
     */
    private static void largestObject(List<MeasurementDraft> drafts, HeapDumpReport report) {
        HeapDumpReport.TypeCensus largest = report.largestObjectHoldingType();
        if (largest == null) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", largest.kind().label());
        attributes.put("objects", largest.objects());
        if (largest.elements() > 0) {
            attributes.put("elements", largest.elements());
        }
        attributes.put("basis", BYTES_BASIS);
        drafts.add(new MeasurementDraft(NAMESPACE + "object.bytes.max", MetricKind.SIZE,
                MeasurementAggregation.MAX, largest.largestBytes(), "bytes",
                (int) Math.min(largest.objects(), Integer.MAX_VALUE), largest.name(), attributes));
    }

    /**
     * The per-type tables: the types holding the most recorded bytes, and the types with the most
     * objects.
     *
     * <p>Both, because they answer different questions and a heap can be dominated by either. The
     * number of types that had to be left out is stated next to every set of rows, and the rows carry
     * their own basis, so a share read off one of them cannot be mistaken for a share of the process's
     * memory.
     */
    private static void typeTables(List<MeasurementDraft> drafts, HeapDumpReport report) {
        List<HeapDumpReport.TypeCensus> byBytes = report.types();
        if (byBytes.isEmpty()) {
            return;
        }
        long totalBytes = report.objectBytes();
        long totalObjects = report.objects();
        int reported = Math.min(TOP_N, byBytes.size());

        drafts.add(new MeasurementDraft(NAMESPACE + "types.bytes.observed", MetricKind.COUNTER,
                MeasurementAggregation.COUNT, byBytes.size(), "count", byBytes.size(), null,
                Map.of("note", "the dump recorded objects of " + byBytes.size() + " types; the tables "
                        + "below report the largest " + reported + " of them, so the two tables together "
                        + "omit " + (byBytes.size() - reported) + " type(s)")));
        drafts.add(MeasurementDraft.count(NAMESPACE + "types.bytes.reported", reported, reported, null));

        for (HeapDumpReport.TypeCensus type : byBytes.subList(0, reported)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kind", type.kind().label());
            attributes.put("objects", type.objects());
            if (type.elements() > 0) {
                attributes.put("elements", type.elements());
            }
            attributes.put("share", share(type.bytes(), totalBytes));
            attributes.put("shareBasis", BYTES_SHARE_BASIS);
            attributes.put("largestObjectBytes", type.largestBytes());
            attributes.put("basis", BYTES_BASIS);
            attributes.put("note", NOT_RETAINED);
            drafts.add(size(NAMESPACE + "types.bytes", type.bytes(), type.objects(), type.name(), attributes));
        }

        List<HeapDumpReport.TypeCensus> byObjects = byBytes.stream()
                .sorted(Comparator.comparingLong(HeapDumpReport.TypeCensus::objects).reversed()
                        .thenComparing(HeapDumpReport.TypeCensus::name))
                .toList();
        for (HeapDumpReport.TypeCensus type : byObjects.subList(0, reported)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kind", type.kind().label());
            attributes.put("bytes", type.bytes());
            if (type.elements() > 0) {
                attributes.put("elements", type.elements());
            }
            attributes.put("share", share(type.objects(), totalObjects));
            attributes.put("shareBasis", OBJECTS_SHARE_BASIS);
            attributes.put("basis", BYTES_BASIS);
            drafts.add(MeasurementDraft.of(NAMESPACE + "types.objects", MetricKind.COUNTER,
                    MeasurementAggregation.COUNT, type.objects(), "count",
                    (int) Math.min(type.objects(), Integer.MAX_VALUE), type.name(), attributes));
        }
    }

    private static MeasurementDraft size(String key, long value, long observations, String scope,
                                         Map<String, Object> attributes) {
        return new MeasurementDraft(key, MetricKind.SIZE, MeasurementAggregation.SUM, value, "bytes",
                (int) Math.min(Math.max(observations, 0), Integer.MAX_VALUE), scope, attributes);
    }

    /** A fraction, as the raw ratio, so the UI decides how many places to show. */
    private static double share(long part, long whole) {
        return whole <= 0 ? 0.0 : (double) part / whole;
    }
}
