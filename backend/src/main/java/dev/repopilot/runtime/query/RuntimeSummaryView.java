package dev.repopilot.runtime.query;

import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.runtime.MetricKind;
import dev.repopilot.runtime.RuntimeArtifactType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The header for a runtime analysis run: what was read, and what came out of it.
 *
 * <p>Mirrors {@code FindingSummaryView}, and for the same reason: "nothing has been recorded"
 * and "a recording produced nothing" are different answers, and a client that only sees an empty
 * list cannot tell them apart. {@code runId} being null says the first; a run with zero
 * measurements says the second.
 *
 * @param provenanceJson the recording's environment, as stored on the artefact: JVM name and
 *                       version, OS, CPU, memory, and the recording's own name and window. Passed
 *                       through as structured JSON rather than flattened, because it is a nested
 *                       fact and the client renders it as one.
 * @param byMetricFamily row counts grouped by the second segment of the metric key
 *                       ({@code gc}, {@code cpu}, {@code execution}, …), which is what the UI
 *                       groups by
 */
public record RuntimeSummaryView(UUID repositoryId,
                                 UUID runId,
                                 UUID artifactId,
                                 String artifactName,
                                 RuntimeArtifactType artifactType,
                                 String artifactHash,
                                 Long artifactSizeBytes,
                                 AnalysisRunStatus status,
                                 Instant startedAt,
                                 Instant finishedAt,
                                 String analyzerVersions,
                                 String summary,
                                 long measurementCount,
                                 Map<MetricKind, Long> byKind,
                                 Map<String, Long> byMetricFamily,
                                 Object provenanceJson) {

    public static RuntimeSummaryView empty(UUID repositoryId) {
        return new RuntimeSummaryView(repositoryId, null, null, null, null, null, null, null,
                null, null, null, null, 0L, Map.of(), Map.of(), null);
    }
}
