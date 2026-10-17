package dev.repopilot.codeindex.query;

import java.util.UUID;

/**
 * Trustworthy coverage of a revision.
 *
 * <p>Reports files and edges that were <em>not</em> fully understood, not just the
 * successes. A tool that says "fully indexed" while silently dropping unparsed files
 * and unresolved references makes every downstream claim suspect.
 *
 * <p>Two separate partitions are reported, and they answer different questions:
 * <ul>
 *   <li><b>What was read.</b> {@code totalFiles = parsedFiles + problemFiles + failedFiles +
 *       recordedFiles} (plus {@code excludedFiles} rows, a status the indexer does not
 *       currently write). {@code parsedFiles} counts files read cleanly; {@code problemFiles}
 *       counts files the parser recovered but that carry problems, so a file that does not
 *       compile can never be reported as merely "parsed". {@code recordedFiles} counts files
 *       that were read and stored but have no analyzer for their language yet: recorded is
 *       not parsed, and the two are counted separately so a configuration file cannot be
 *       mistaken for a clean Java parse.</li>
 *   <li><b>What was inspected.</b> {@code enumeratedFiles = totalFiles + excludedFiles}.
 *       {@code excludedFiles} is not a row count and cannot be derived from one: an excluded
 *       file never becomes a row. It is the number of files the walker inspected and
 *       deliberately did not read — credentials, unsupported languages, binary content,
 *       size limits — which is why {@code excludedByReason} carries the breakdown.</li>
 * </ul>
 *
 * <p>{@code prunedDirectories} is reported outside both partitions on purpose. A pruned
 * directory was never walked, so its contents were never seen and no file count can be
 * given for it; stating a number there would be inventing one.
 *
 * @param recordedFiles        read and recorded but not parsed: no analyzer for the language
 * @param excludedByReason     {@code REASON=count} breakdown of {@code excludedFiles}, or
 *                             {@code null} when nothing was excluded
 * @param prunedByReason       {@code REASON=count} breakdown of {@code prunedDirectories}
 * @param compiledClassesPresent whether the revision contains conventional build output.
 *                             The precondition for bytecode-based analysis, recorded so
 *                             "SpotBugs was not applicable" is checkable.
 */
public record CoverageView(
        UUID revisionId,
        long totalFiles,
        long parsedFiles,
        long problemFiles,
        long failedFiles,
        long recordedFiles,
        long excludedFiles,
        long enumeratedFiles,
        long prunedDirectories,
        String excludedByReason,
        String prunedByReason,
        boolean compiledClassesPresent,
        long symbolCount,
        long edgeCount,
        long resolvedEdges,
        long inferredEdges,
        long externalEdges,
        long mappingCount
) {

    /** Files the parser produced a tree for, cleanly or with problems. */
    public long readableFiles() {
        return parsedFiles + problemFiles;
    }

    /** Share of files the parser produced a tree for. */
    public double parsedRatio() {
        return totalFiles == 0 ? 1.0 : (double) readableFiles() / totalFiles;
    }

    public double resolvedRatio() {
        long internal = resolvedEdges + inferredEdges;
        long considered = internal + externalEdges;
        return considered == 0 ? 1.0 : (double) resolvedEdges / considered;
    }
}
