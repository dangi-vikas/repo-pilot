package dev.repopilot.runtime.heapdump;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What one HPROF heap dump said, as facts rather than conclusions.
 *
 * <p>A heap dump is a snapshot of one process's heap at one instant. It is not a profile and it does
 * not contain a single timestamped event: everything in it describes the moment the JVM wrote it.
 * The report below is therefore a census — how many objects, of which types, holding how much
 * recorded data — plus the dump's header and its garbage-collection roots.
 *
 * <h2>What is deliberately not in here</h2>
 * No retained sizes and no dominator tree. "Retained size" is the memory that would be freed if an
 * object became unreachable, and computing it means walking the whole object graph, choosing which
 * roots to trust and deciding how to attribute an object reachable from two others. That is a
 * different, much larger computation with its own assumptions, and a number produced by a partial
 * version of it would look authoritative and be wrong. What is here instead is each type's
 * <em>own</em> recorded bytes: for an instance, the field values the dump wrote for it; for an
 * array, its element data. Headers, class metadata and everything reachable from an object are
 * excluded, and the mapper says so in every row it publishes.
 *
 * <h2>Why every type carries an object count and a byte count</h2>
 * The two answer different questions. A type with millions of small objects and a type with three
 * enormous arrays can hold the same bytes, and only the counts tell them apart. Neither number is a
 * problem statement: a heap is supposed to be full of objects.
 *
 * @param provenance the dump's header and what was counted in the file
 * @param census     the whole-heap totals
 * @param types      one entry per type that holds at least one recorded object, largest first
 * @param roots      one entry per kind of garbage-collection root the dump recorded
 */
public record HeapDumpReport(Provenance provenance,
                             Census census,
                             List<TypeCensus> types,
                             List<RootCensus> roots) {

    /** What kind of type an entry describes. */
    public enum TypeKind {

        /** A class's instances, exactly as the dump recorded them. */
        INSTANCE("instance"),

        /** An array of references. */
        OBJECT_ARRAY("object array"),

        /** An array of primitives. */
        PRIMITIVE_ARRAY("primitive array");

        private final String label;

        TypeKind(String label) {
            this.label = label;
        }

        /** The phrase used in a measurement's attributes, so a number is not read as the wrong kind. */
        public String label() {
            return label;
        }
    }

    /**
     * One type's recorded objects.
     *
     * @param name       the type's name in source form: {@code java.lang.String}, {@code byte[]},
     *                   {@code java.lang.Object[]}
     * @param kind       what kind of type it is
     * @param objects    how many objects of this type the dump recorded
     * @param elements   for an array, the total number of elements across all of them (0 for an instance)
     * @param bytes      the recorded bytes: instance field values, or array element data
     * @param largestBytes the largest single object of this type, in recorded bytes
     */
    public record TypeCensus(String name,
                             TypeKind kind,
                             long objects,
                             long elements,
                             long bytes,
                             long largestBytes) {
    }

    /** One kind of garbage-collection root and how many of it the dump recorded. */
    public record RootCensus(String type, long count) {
    }

    /**
     * The dump's header, and the sizes of the parts of the file that are not the heap.
     *
     * @param formatVersion  the HPROF version from the header, e.g. {@code 1.0.2}
     * @param identifierSize the width in bytes of an object identifier in this file (4 or 8)
     * @param takenAt        the instant the dump was written, or null when the header's timestamp
     *                       field held a value that is not a plausible instant
     * @param takenAtBasis   what the instant is and where it came from, or why it is absent
     * @param fileBytes      the size of the file on disk
     * @param fileBytesBasis what {@code fileBytes} counts, and what it does not
     * @param strings        how many text records the file's string table holds
     * @param classesLoaded  how many classes the file declares through LOAD_CLASS records
     * @param classesDumped  how many classes carry a CLASS_DUMP record in the heap itself
     */
    public record Provenance(String formatVersion,
                             int identifierSize,
                             Instant takenAt,
                             String takenAtBasis,
                             long fileBytes,
                             String fileBytesBasis,
                             long strings,
                             long classesLoaded,
                             long classesDumped) {
    }

    /**
     * The whole-heap totals.
     *
     * @param instances            instances recorded
     * @param instanceBytes        their recorded field bytes
     * @param objectArrays         arrays of references recorded
     * @param objectArrayBytes     their recorded element bytes
     * @param primitiveArrays      arrays of primitives recorded
     * @param primitiveArrayBytes  their recorded element bytes
     * @param namedTypes           types with at least one object, that could be named
     * @param unnamedObjects       objects whose type the file never named
     * @param roots                garbage-collection root records
     */
    public record Census(long instances,
                         long instanceBytes,
                         long objectArrays,
                         long objectArrayBytes,
                         long primitiveArrays,
                         long primitiveArrayBytes,
                         long namedTypes,
                         long unnamedObjects,
                         long roots) {
    }

    /** Every object the dump recorded: instances and arrays together. */
    public long objects() {
        return census.instances() + census.objectArrays() + census.primitiveArrays();
    }

    /** Every byte of object data the dump recorded, excluding headers and class metadata. */
    public long objectBytes() {
        return census.instanceBytes() + census.objectArrayBytes() + census.primitiveArrayBytes();
    }

    /** Every array the dump recorded. */
    public long arrays() {
        return census.objectArrays() + census.primitiveArrays();
    }

    /** The type holding the most recorded bytes, or null when the dump recorded no objects. */
    public TypeCensus largestType() {
        return types.isEmpty() ? null : types.get(0);
    }

    /** The single largest object the dump recorded, or null when there was none. */
    public TypeCensus largestObjectHoldingType() {
        return types.stream()
                .max((left, right) -> Long.compare(left.largestBytes(), right.largestBytes()))
                .filter(type -> type.largestBytes() > 0)
                .orElse(null);
    }

    /** The roots as a map from root kind to count, in the order the reporter presents them. */
    public Map<String, Long> rootCounts() {
        return roots.stream().collect(Collectors.toMap(RootCensus::type, RootCensus::count,
                (left, right) -> left, java.util.LinkedHashMap::new));
    }
}
