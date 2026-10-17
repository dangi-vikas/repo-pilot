package dev.repopilot.analysis.query;

import dev.repopilot.analysis.EvidenceCategory;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingSeverity;

import java.util.UUID;

/**
 * Optional filters for a findings query.
 *
 * <p>{@code runId} is the only field a caller normally sets, and it identifies the analysis
 * run the findings must come from. The rest narrow within that run. A filter that selects
 * nothing is not distinguished from a run that produced nothing: both return an empty page
 * with {@code totalItems = 0}, and the summary endpoint is what tells the two apart.
 *
 * @param runId            the analysis run; required for a meaningful list
 * @param severity         exact severity match
 * @param category         exact category match
 * @param evidenceCategory exact evidence-category match
 * @param analyzerId       exact analyzer match, e.g. {@code pmd}
 * @param sourcePath       exact repository-relative path, for the file drill-down
 * @param text             case-insensitive substring of title, rule id or path
 */
public record FindingFilter(
        UUID runId,
        FindingSeverity severity,
        FindingCategory category,
        EvidenceCategory evidenceCategory,
        String analyzerId,
        String sourcePath,
        String text
) {

    public static FindingFilter ofRun(UUID runId) {
        return new FindingFilter(runId, null, null, null, null, null, null);
    }
}
