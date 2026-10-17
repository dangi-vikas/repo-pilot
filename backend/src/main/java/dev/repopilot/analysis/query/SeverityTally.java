package dev.repopilot.analysis.query;

import dev.repopilot.analysis.FindingSeverity;

/**
 * One row of a {@code GROUP BY severity} tally.
 *
 * <p>Written by JPQL constructor expressions in the finding repository. A record rather
 * than a map so the query result is typed, and rather than an interface projection so a
 * renamed alias is a compile error instead of an empty map.
 */
public record SeverityTally(FindingSeverity severity, long total) {
}
