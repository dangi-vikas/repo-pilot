package dev.repopilot.repository;

/**
 * What an enumeration deliberately did not analyse, recorded against the revision it
 * describes.
 *
 * <p>The enumerator always knew this. The coverage view did not, because a skipped file
 * never becomes a row: counting rows can only ever report the files that were read. That
 * produced the worst kind of report — a confident {@code 0 excluded} for a scan that had
 * skipped credential files — so the numbers are carried on the revision instead.
 *
 * <p>Two partitions are reported, and they are not the same claim:
 * <ul>
 *   <li>{@code enumeratedFiles = analysedFiles + excludedFiles}. Every file the walker
 *       inspected was either read or deliberately skipped, and both are counted.</li>
 *   <li>{@code prunedDirectories} counts directories that were never walked. Their
 *       contents were never seen, so no file count can be given for them. Reporting a
 *       pruned directory as "N skipped files" would be a fabricated number.</li>
 * </ul>
 *
 * @param enumeratedFiles        files inspected by the walker
 * @param excludedFiles          files inspected and deliberately not read
 * @param prunedDirectories      directories not walked at all
 * @param excludedByReason       {@code REASON=count} list for {@code excludedFiles}, or
 *                               {@code null} when nothing was excluded
 * @param prunedByReason         {@code REASON=count} list for {@code prunedDirectories}, or
 *                               {@code null} when nothing was pruned
 * @param compiledClassesPresent true when the repository contains conventional build output
 *                               with {@code .class} files. This is the precondition for
 *                               bytecode-based analysis, so "SpotBugs was not applicable"
 *                               can be checked rather than taken on trust.
 */
public record ScanAccounting(
        long enumeratedFiles,
        long excludedFiles,
        long prunedDirectories,
        String excludedByReason,
        String prunedByReason,
        boolean compiledClassesPresent
) {

    /** The accounting of a scan that inspected nothing, used for rows predating the record. */
    public static final ScanAccounting NONE = new ScanAccounting(0, 0, 0, null, null, false);

    public ScanAccounting {
        excludedByReason = blankToNull(excludedByReason);
        prunedByReason = blankToNull(prunedByReason);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
