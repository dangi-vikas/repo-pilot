package dev.repopilot.runtime.heapdump;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MeasurementDraft;
import dev.repopilot.runtime.MetricKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from a dump's facts to the numbers RepoPilot stores.
 *
 * <p>What these tests are really pinning is the discipline: every byte figure says what it counts and
 * that it is not a retained size, no row carries a severity, the cut at the twentieth type is stated
 * rather than silent, and the counts and the byte totals are never presented as if either explained
 * the other.
 */
class HeapDumpMeasurementMapperTest {

    @Test
    @DisplayName("publishes the census with a unit and a reduction on every row")
    void publishesTheCensus() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump());

        assertThat(value(drafts, "heapdump.objects.count")).isEqualTo(6);
        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.objects.count"))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.kind()).isEqualTo(MetricKind.COUNTER);
                    assertThat(draft.aggregation()).isEqualTo(MeasurementAggregation.COUNT);
                    assertThat(draft.unit()).isEqualTo("count");
                    // How many objects are in this heap has two defensible answers, and the operator has
                    // to know which one is in front of them.
                    assertThat(String.valueOf(draft.attributes().get("note")))
                            .contains("collects first")
                            .contains("garbage");
                });

        assertThat(value(drafts, "heapdump.objects.bytes")).isEqualTo(12 + 32 + 8 + 6);
        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.objects.bytes"))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.kind()).isEqualTo(MetricKind.SIZE);
                    assertThat(draft.aggregation()).isEqualTo(MeasurementAggregation.SUM);
                    assertThat(draft.unit()).isEqualTo("bytes");
                    assertThat(draft.sampleCount()).isEqualTo(6);
                });

        assertThat(value(drafts, "heapdump.instances.count")).isEqualTo(4);
        assertThat(value(drafts, "heapdump.instances.bytes")).isEqualTo(12 + 6);
        assertThat(value(drafts, "heapdump.arrays.count")).isEqualTo(2);
        assertThat(value(drafts, "heapdump.arrays.bytes")).isEqualTo(40);
        assertThat(value(drafts, "heapdump.arrays.primitive.count")).isEqualTo(1);
        assertThat(value(drafts, "heapdump.arrays.primitive.bytes")).isEqualTo(8);
        assertThat(value(drafts, "heapdump.arrays.object.count")).isEqualTo(1);
        assertThat(value(drafts, "heapdump.arrays.object.bytes")).isEqualTo(32);
        assertThat(value(drafts, "heapdump.classes.loaded")).isEqualTo(9);
        assertThat(value(drafts, "heapdump.classes.dumped")).isEqualTo(7);
        assertThat(value(drafts, "heapdump.types.count")).isEqualTo(4);
    }

    @Test
    @DisplayName("every byte row says what it counts and that it is not a retained size")
    void everyByteRowCarriesItsBasis() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump());

        List<MeasurementDraft> byteRows = drafts.stream()
                .filter(draft -> "bytes".equals(draft.unit()))
                .toList();
        assertThat(byteRows).isNotEmpty();
        assertThat(byteRows).allSatisfy(draft -> {
            assertThat(draft.attributes()).containsKey("basis");
            assertThat(String.valueOf(draft.attributes().get("basis")))
                    .contains("field values")
                    .contains("element data")
                    .contains("excludes every object header")
                    .contains("not a retained size");
        });
        assertThat(byteRows).filteredOn(draft -> draft.metricKey().equals("heapdump.objects.bytes"))
                .singleElement()
                .satisfies(draft -> assertThat(String.valueOf(draft.attributes().get("note")))
                        .contains("dominator tree")
                        .contains("a leak"));
    }

    @Test
    @DisplayName("no row is a threshold, a severity or a verdict")
    void appliesNoThresholdAndAssignsNoSeverity() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump());
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.metricKey()).startsWith(HeapDumpMeasurementMapper.NAMESPACE);
            assertThat(draft.attributes()).doesNotContainKeys("severity", "threshold", "finding", "leak");
        });
        assertThat(HeapDumpMeasurementMapper.SNAPSHOT).contains("one instant").contains("a rate");
    }

    @Test
    @DisplayName("publishes each kind of root the dump recorded, and nothing when it recorded none")
    void publishesRootKinds() {
        List<MeasurementDraft> draftList = HeapDumpMeasurementMapper.toMeasurements(dump());
        assertThat(value(draftList, "heapdump.roots.count")).isEqualTo(4);
        assertThat(draftList)
                .filteredOn(draft -> draft.metricKey().equals("heapdump.roots.kind.count"))
                .extracting(MeasurementDraft::scope)
                .containsExactly("sticky-class", "thread-object", "interned-string");
        assertThat(value(draftList, "heapdump.roots.kind.count", "thread-object")).isEqualTo(2);

        assertThat(HeapDumpMeasurementMapper.toMeasurements(withRoots(List.of())))
                .noneSatisfy(draft -> assertThat(draft.metricKey()).startsWith("heapdump.roots"));
    }

    @Test
    @DisplayName("reports the largest single object with its type as the scope")
    void reportsTheLargestObject() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump());
        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.object.bytes.max"))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.value()).isEqualTo(32);
                    assertThat(draft.scope()).isEqualTo("java.lang.Object[]");
                    assertThat(draft.kind()).isEqualTo(MetricKind.SIZE);
                    assertThat(draft.aggregation()).isEqualTo(MeasurementAggregation.MAX);
                    assertThat(draft.attributes()).containsEntry("kind", "object array");
                });
    }

    @Test
    @DisplayName("reports the twenty largest types, says how many there were, and shares of the right total")
    void cutsTheTypeTablesAtTwenty() {
        List<HeapDumpReport.TypeCensus> types = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            types.add(new HeapDumpReport.TypeCensus("probe.T" + i, HeapDumpReport.TypeKind.INSTANCE,
                    100 - i, 0, (25 - i) * 1000L, 1000));
        }
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(withTypes(types));

        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.types.bytes"))
                .hasSize(20);
        assertThat(value(drafts, "heapdump.types.bytes.observed")).isEqualTo(25);
        assertThat(value(drafts, "heapdump.types.bytes.reported")).isEqualTo(20);
        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.types.bytes"))
                .first()
                .satisfies(draft -> {
                    assertThat(draft.scope()).isEqualTo("probe.T0");
                    assertThat(draft.value()).isEqualTo(25_000);
                    assertThat(draft.attributes()).containsEntry("objects", 100L);
                    assertThat(String.valueOf(draft.attributes().get("shareBasis")))
                            .isEqualTo("of all the object bytes the dump recorded");
                });

        // The caught-out count is disclosed; a share is of the whole dump's bytes, not of the top twenty.
        MeasurementDraft observed = drafts.stream()
                .filter(draft -> draft.metricKey().equals("heapdump.types.bytes.observed"))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(observed.attributes().get("note"))).contains("25").contains("20");

        List<MeasurementDraft> byObjects = drafts.stream()
                .filter(draft -> draft.metricKey().equals("heapdump.types.objects"))
                .toList();
        assertThat(byObjects).hasSize(20);
        assertThat(byObjects.get(0).scope()).isEqualTo("probe.T0");
        assertThat(byObjects.get(0).value()).isEqualTo(100);
        assertThat(byObjects.get(1).scope()).isEqualTo("probe.T1");
        assertThat(byObjects).allSatisfy(
                draft -> assertThat(String.valueOf(draft.attributes().get("shareBasis")))
                        .isEqualTo("of all the objects the dump recorded"));
    }

    @Test
    @DisplayName("names a type the same way in every row it appears in")
    void usesConsistentTypeNames() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump());
        assertThat(drafts).filteredOn(draft -> draft.scope() != null)
                .extracting(MeasurementDraft::scope)
                .doesNotContain("[B", "[Ljava.lang.Object;");
    }

    @Test
    @DisplayName("discloses objects the file never named instead of filing them under a guess")
    void disclosesUnnamedObjects() {
        List<MeasurementDraft> drafts = HeapDumpMeasurementMapper.toMeasurements(dump(3));
        assertThat(drafts).filteredOn(draft -> draft.metricKey().equals("heapdump.objects.unnamed"))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.value()).isEqualTo(3);
                    assertThat(String.valueOf(draft.attributes().get("note")))
                            .contains("never named")
                            .contains("no LOAD_CLASS");
                });
        assertThat(HeapDumpMeasurementMapper.toMeasurements(dump(0)))
                .noneSatisfy(draft -> assertThat(draft.metricKey()).isEqualTo("heapdump.objects.unnamed"));
    }

    // ---------------------------------------------------------------- helpers

    private static HeapDumpReport dump() {
        return dump(0);
    }

    /** A dump with four instances, two arrays and the three root kinds the tests name. */
    private static HeapDumpReport dump(long unnamedObjects) {
        List<HeapDumpReport.TypeCensus> types = List.of(
                new HeapDumpReport.TypeCensus("probe.Model", HeapDumpReport.TypeKind.INSTANCE, 2, 0, 12, 8),
                new HeapDumpReport.TypeCensus("java.lang.Object[]",
                        HeapDumpReport.TypeKind.OBJECT_ARRAY, 1, 4, 32, 32),
                new HeapDumpReport.TypeCensus("byte[]", HeapDumpReport.TypeKind.PRIMITIVE_ARRAY, 1, 8, 8, 8),
                new HeapDumpReport.TypeCensus("probe.Small", HeapDumpReport.TypeKind.INSTANCE, 2, 0, 6, 4));
        List<HeapDumpReport.RootCensus> roots = List.of(
                new HeapDumpReport.RootCensus("sticky-class", 1),
                new HeapDumpReport.RootCensus("thread-object", 2),
                new HeapDumpReport.RootCensus("interned-string", 1));
        return report(types, roots, unnamedObjects);
    }

    private static HeapDumpReport withRoots(List<HeapDumpReport.RootCensus> roots) {
        return report(dump().types(), roots, 0);
    }

    private static HeapDumpReport withTypes(List<HeapDumpReport.TypeCensus> types) {
        return report(types, dump().roots(), 0);
    }

    private static HeapDumpReport report(List<HeapDumpReport.TypeCensus> types,
                                         List<HeapDumpReport.RootCensus> roots, long unnamedObjects) {
        long instances = 0;
        long instanceBytes = 0;
        long objectArrays = 0;
        long objectArrayBytes = 0;
        long primitiveArrays = 0;
        long primitiveArrayBytes = 0;
        for (HeapDumpReport.TypeCensus type : types) {
            switch (type.kind()) {
                case INSTANCE -> {
                    instances += type.objects();
                    instanceBytes += type.bytes();
                }
                case OBJECT_ARRAY -> {
                    objectArrays += type.objects();
                    objectArrayBytes += type.bytes();
                }
                case PRIMITIVE_ARRAY -> {
                    primitiveArrays += type.objects();
                    primitiveArrayBytes += type.bytes();
                }
            }
        }
        long totalRoots = roots.stream().mapToLong(HeapDumpReport.RootCensus::count).sum();
        HeapDumpReport.Census census = new HeapDumpReport.Census(instances, instanceBytes,
                objectArrays, objectArrayBytes, primitiveArrays, primitiveArrayBytes,
                types.size(), unnamedObjects, totalRoots);
        HeapDumpReport.Provenance provenance = new HeapDumpReport.Provenance("1.0.2", 8,
                Instant.parse("2026-10-19T06:27:15.908Z"), "the header's timestamp",
                10_055_100L, "the size of the uploaded file", 500, 9, 7);
        return new HeapDumpReport(provenance, census, types, roots);
    }

    private static double value(List<MeasurementDraft> drafts, String key) {
        return drafts.stream()
                .filter(draft -> draft.metricKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement " + key + " among "
                        + drafts.size()))
                .value();
    }

    private static double value(List<MeasurementDraft> drafts, String key, String scope) {
        return drafts.stream()
                .filter(draft -> draft.metricKey().equals(key) && scope.equals(draft.scope()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement " + key + " scoped " + scope))
                .value();
    }
}
