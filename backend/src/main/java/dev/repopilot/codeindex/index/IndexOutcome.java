package dev.repopilot.codeindex.index;

import java.util.UUID;

/**
 * What an indexing run actually produced.
 *
 * <p>Returned to the job layer and used to populate the repository's file count and
 * the coverage view. Every count is a count of persisted rows, not an estimate, so
 * the number shown to a user is the number that exists.
 *
 * <p>{@code filesExcluded} and {@code prunedDirectories} come from the scan manifest and
 * not from rows, because an excluded file never becomes a row. They are reported in the
 * job result on purpose: "3 003 files indexed" without "5 files were excluded, 3 of them
 * credential files" is a half-truth, and the job history is where an operator looks for it.
 */
public record IndexOutcome(
        UUID revisionId,
        String revisionLabel,
        String fingerprint,
        long enumeratedFiles,
        int filesIndexed,
        long filesExcluded,
        long prunedDirectories,
        String excludedByReason,
        int parseFailures,
        int filesRecorded,
        long symbols,
        long edges,
        long mappings,
        int resolvedEdges,
        int inferredEdges,
        int externalEdges,
        boolean truncated
) {

    public String summary() {
        StringBuilder text = new StringBuilder()
                .append(filesIndexed).append(" file(s) indexed, ")
                .append(symbols).append(" symbol(s), ")
                .append(edges).append(" reference(s), ")
                .append(mappings).append(" mapping(s)");
        if (parseFailures > 0) {
            text.append(", ").append(parseFailures).append(" unparsed");
        }
        if (filesRecorded > 0) {
            text.append(", ").append(filesRecorded)
                    .append(" recorded without a language analyzer");
        }
        if (filesExcluded > 0) {
            text.append(", ").append(filesExcluded).append(" file(s) excluded");
            if (excludedByReason != null && !excludedByReason.isBlank()) {
                text.append(" (").append(excludedByReason).append(")");
            }
        }
        if (prunedDirectories > 0) {
            text.append(", ").append(prunedDirectories).append(" directory(ies) pruned");
        }
        if (truncated) {
            text.append(" (truncated)");
        }
        return text.toString();
    }
}
