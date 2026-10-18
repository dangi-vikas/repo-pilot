package dev.repopilot.runtime.query;

import dev.repopilot.runtime.MeasurementAggregation;
import dev.repopilot.runtime.MetricKind;
import dev.repopilot.runtime.RuntimeMeasurement;

import java.time.Instant;
import java.util.UUID;

/**
 * One measurement as the API reports it.
 *
 * <p>The reduction, the unit and the sample count travel with the value. A client that renders
 * {@code 128} without also rendering {@code ms (max of 92 samples)} is showing a number nobody
 * can interpret, so the response makes the context impossible to miss.
 *
 * @param attributesJson metric-specific caveats and shares; the client is expected to show them
 *                       rather than treat them as decoration
 */
public record RuntimeMeasurementView(UUID id,
                                     UUID analysisRunId,
                                     UUID artifactId,
                                     String metricKey,
                                     MetricKind metricKind,
                                     MeasurementAggregation aggregation,
                                     double value,
                                     String unit,
                                     int sampleCount,
                                     String scope,
                                     String attributesJson,
                                     Instant createdAt) {

    public static RuntimeMeasurementView from(RuntimeMeasurement measurement) {
        return new RuntimeMeasurementView(
                measurement.getId(),
                measurement.getAnalysisRunId(),
                measurement.getArtifactId(),
                measurement.getMetricKey(),
                measurement.getMetricKind(),
                measurement.getAggregation(),
                measurement.getValue(),
                measurement.getUnit(),
                measurement.getSampleCount(),
                measurement.getScope(),
                measurement.getAttributesJson(),
                measurement.getCreatedAt());
    }
}
