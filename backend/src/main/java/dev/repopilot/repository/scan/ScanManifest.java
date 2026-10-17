package dev.repopilot.repository.scan;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The result of enumerating a repository, before any file has been parsed.
 *
 * <p>The manifest is deliberately explicit about what was skipped and why. The
 * alternative — reporting "3 812 files indexed" without mentioning the 1 240 that
 * were skipped — is the kind of unstated limitation that makes a code-intelligence
 * tool untrustworthy.
 *
 * @param root                 canonical repository root
 * @param analysable           files that will be read and analysed
 * @param skippedCounts        skip reason to file count
 * @param prunedDirectories    skip reason to pruned directory count. Reported separately
 *                             from file counts because a pruned directory represents an
 *                             unknown number of unseen files, not a known number of
 *                             inspected ones.
 * @param enumeratedFiles      every directory entry inspected, including skipped ones
 * @param enumeratedBytes      total size of files that were included
 * @param truncated            true when a hard limit stopped enumeration early
 * @param truncationReason     human-readable reason when {@code truncated} is true
 * @param compiledClassesFound true when a conventional build output directory with
 *                             {@code .class} files exists. Recorded because it is the
 *                             precondition for bytecode-based analysis (SpotBugs); its
 *                             absence must be reported rather than glossed over.
 */
public record ScanManifest(
        Path root,
        List<ScannedFile> analysable,
        Map<SkipReason, Long> skippedCounts,
        Map<SkipReason, Long> prunedDirectories,
        long enumeratedFiles,
        long enumeratedBytes,
        boolean truncated,
        String truncationReason,
        boolean compiledClassesFound
) {

    /** Count of files skipped for a given reason. */
    public long skipped(SkipReason reason) {
        return skippedCounts.getOrDefault(reason, 0L);
    }

    /** Count of directories pruned for a given reason. */
    public long pruned(SkipReason reason) {
        return prunedDirectories.getOrDefault(reason, 0L);
    }

    /**
     * Compact, deterministic {@code REASON=count} list of the files that were skipped, or
     * {@code null} when nothing was skipped.
     *
     * <p>Reasons are ordered by name and zero counts are dropped, so the same scan always
     * produces the same string and two revisions can be compared as text.
     */
    public String skippedByReasonText() {
        return encode(skippedCounts);
    }

    /** Same encoding as {@link #skippedByReasonText()} for the pruned directories. */
    public String prunedByReasonText() {
        return encode(prunedDirectories);
    }

    /** Directories that were never walked. Their contents are counted nowhere, by design. */
    public long totalPrunedDirectories() {
        return prunedDirectories.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String encode(Map<SkipReason, Long> counts) {
        String text = counts.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        return text.isEmpty() ? null : text;
    }

    public long totalSkipped() {
        return skippedCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    public boolean isEmpty() {
        return analysable.isEmpty();
    }
}
