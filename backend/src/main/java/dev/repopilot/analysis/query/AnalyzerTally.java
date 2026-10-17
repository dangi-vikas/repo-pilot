package dev.repopilot.analysis.query;

/**
 * One row of a {@code GROUP BY analyzer_id} tally.
 *
 * <p>Kept so a reader can tell a finding produced by a hand-written check from one produced
 * by PMD. The two are not equally likely to be wrong, and a total that hides the split
 * invites treating an entire ruleset as one voice.
 */
public record AnalyzerTally(String analyzerId, long total) {
}
