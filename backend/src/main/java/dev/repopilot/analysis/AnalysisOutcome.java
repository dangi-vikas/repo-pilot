package dev.repopilot.analysis;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What an analysis pipeline did, reported as a job result.
 *
 * <p>Carries the counts that let the run's own summary be honest: how many files were analysed,
 * how many were skipped because no analyzer supports their language, how many could not be read,
 * and how many findings were produced at each severity. A run that reports only "N findings"
 * cannot be distinguished from a run that read nothing.
 *
 * @param runId              the persisted run
 * @param revisionId         the revision the findings describe
 * @param status             final run status; {@code PARTIAL} when coverage is incomplete
 * @param filesAnalysed      files actually handed to an analyzer
 * @param filesSkippedLanguage files skipped because no analyzer reads their language
 * @param filesUnreadable    files the sandbox or the read policy refused, or that failed to decode
 * @param findings           findings persisted
 * @param findingsBySeverity findings by severity, descending severity order
 * @param analyzers          analyzers that ran, as {@code id version}
 * @param notes              facts about the run that are not findings — an analyzer's
 *                           configuration, a batch it failed on. Capped by the caller
 * @param additionalNotes    how many further notes were dropped from the summary
 */
public record AnalysisOutcome(
        UUID runId,
        UUID revisionId,
        AnalysisRunStatus status,
        int filesAnalysed,
        int filesSkippedLanguage,
        int filesUnreadable,
        int findings,
        Map<FindingSeverity, Integer> findingsBySeverity,
        List<String> analyzers,
        List<String> notes,
        int additionalNotes
) {

    /** Job summaries are one line in the UI; beyond this the text stops being readable. */
    private static final int MAX_SUMMARY = 1000;

    public AnalysisOutcome {
        findingsBySeverity = findingsBySeverity == null ? Map.of() : Map.copyOf(findingsBySeverity);
        analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** One-line job summary, in the same shape the indexing job already uses. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append(findings).append(findings == 1 ? " finding" : " findings")
                .append(" across ").append(filesAnalysed)
                .append(filesAnalysed == 1 ? " file" : " files").append(" analysed");
        if (findings > 0) {
            text.append(": ").append(severityBreakdown());
        }
        if (filesSkippedLanguage > 0) {
            text.append("; ").append(filesSkippedLanguage)
                    .append(" file(s) skipped (no analyzer for the language)");
        }
        if (filesUnreadable > 0) {
            text.append("; ").append(filesUnreadable).append(" file(s) unreadable");
        }
        if (!analyzers.isEmpty()) {
            text.append(" \u2014 ").append(String.join(", ", analyzers));
        }
        if (!notes.isEmpty()) {
            text.append(" \u2014 ").append(String.join(" | ", notes));
            if (additionalNotes > 0) {
                text.append(" (+").append(additionalNotes).append(" more note(s))");
            }
        }
        return text.length() <= MAX_SUMMARY ? text.toString() : text.substring(0, MAX_SUMMARY - 1) + "\u2026";
    }

    private String severityBreakdown() {
        Map<FindingSeverity, Integer> ordered = new EnumMap<>(FindingSeverity.class);
        ordered.putAll(findingsBySeverity);
        return Arrays.stream(FindingSeverity.values())
                .sorted((a, b) -> Integer.compare(b.rank(), a.rank()))
                .filter(ordered::containsKey)
                .map(severity -> ordered.get(severity) + " " + severity.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
