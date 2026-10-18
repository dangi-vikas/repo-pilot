package dev.repopilot.runtime;

import java.util.Map;

/**
 * One measurement, before it is attached to a run and given an identity.
 *
 * <p>Pure data with no persistence concerns: a reader produces these, the job handler
 * assigns repository, run and artefact, and the entity is what lands in the table. Keeping
 * the draft separate means the mapping from a recording to numbers is testable without a
 * database, and the numbers a recording yields can be asserted exactly.
 *
 * @param metricKey   stable identifier of what was measured, e.g. {@code jfr.gc.pause.max}
 * @param kind        what kind of quantity it is
 * @param aggregation how the observations were reduced
 * @param value       the reduced value
 * @param unit        unit of the value ({@code count}, {@code ms}, {@code bytes}, {@code %},
 *                    {@code weight}); never blank, because a bare number is not a measurement
 * @param sampleCount how many observations were reduced (0 for a value that is not a
 *                    reduction over samples, such as the recording window)
 * @param scope       what the value is over (a method, a class, a GC cause), or null for a
 *                    repository-wide figure
 * @param attributes  optional metric-specific detail; must never contain source text
 */
public record MeasurementDraft(String metricKey,
                               MetricKind kind,
                               MeasurementAggregation aggregation,
                               double value,
                               String unit,
                               int sampleCount,
                               String scope,
                               Map<String, Object> attributes) {

    public MeasurementDraft {
        if (metricKey == null || metricKey.isBlank()) {
            throw new IllegalArgumentException("a measurement must name what it measured");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("a measurement must carry a unit: " + metricKey);
        }
        if (scope != null && scope.length() > 300) {
            scope = scope.substring(0, 297) + "...";
        }
    }

    public static MeasurementDraft count(String metricKey, long value, int sampleCount, String scope) {
        return new MeasurementDraft(metricKey, MetricKind.COUNTER, MeasurementAggregation.COUNT,
                value, "count", sampleCount, scope, Map.of());
    }

    public static MeasurementDraft millis(String metricKey, MeasurementAggregation aggregation,
                                          double value, int sampleCount, String scope) {
        return new MeasurementDraft(metricKey, MetricKind.DURATION, aggregation, value, "ms",
                sampleCount, scope, Map.of());
    }

    public static MeasurementDraft of(String metricKey, MetricKind kind, MeasurementAggregation aggregation,
                                      double value, String unit, int sampleCount, String scope,
                                      Map<String, Object> attributes) {
        return new MeasurementDraft(metricKey, kind, aggregation, value, unit, sampleCount, scope, attributes);
    }
}
