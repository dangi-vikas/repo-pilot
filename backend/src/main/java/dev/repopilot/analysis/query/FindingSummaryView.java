package dev.repopilot.analysis.query;

import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingSeverity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Header plus counts for the findings of one analysis run.
 *
 * <p>Every count here describes a single run. The run id, its revision and its status are
 * part of the response because a findings list without them is indistinguishable from a
 * findings list for different code — which is the confusion this whole package exists to
 * prevent.
 *
 * <p>All run fields are absent when the repository has never been analysed. That is a real
 * state with its own meaning ("no analysis has run"), not an error and not an empty list of
 * findings.
 */
public record FindingSummaryView(
        UUID repositoryId,
        UUID runId,
        UUID revisionId,
        AnalysisRunStatus runStatus,
        Instant startedAt,
        Instant finishedAt,
        String analyzerVersions,
        String runSummary,
        long total,
        Map<FindingSeverity, Long> bySeverity,
        Map<FindingCategory, Long> byCategory,
        Map<String, Long> byAnalyzer
) {
}
