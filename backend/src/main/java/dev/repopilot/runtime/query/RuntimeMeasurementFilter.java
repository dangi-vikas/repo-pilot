package dev.repopilot.runtime.query;

import dev.repopilot.runtime.MetricKind;

import java.util.UUID;

/**
 * What a measurements query may narrow to.
 *
 * <p>Every field is optional except the run, which the service resolves. There is deliberately no
 * "all repositories" mode: a measurement is only meaningful against the recording it came from.
 *
 * @param metricKeyPrefix matches the start of the metric key, so {@code jfr.gc} returns every GC
 *                        figure without the client enumerating keys
 */
public record RuntimeMeasurementFilter(UUID runId,
                                       String metricKeyPrefix,
                                       MetricKind metricKind,
                                       String scopeContains) {
}
