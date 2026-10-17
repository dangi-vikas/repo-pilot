package dev.repopilot.analysis.query;

import dev.repopilot.analysis.FindingCategory;

/** One row of a {@code GROUP BY category} tally. See {@link SeverityTally}. */
public record CategoryTally(FindingCategory category, long total) {
}
