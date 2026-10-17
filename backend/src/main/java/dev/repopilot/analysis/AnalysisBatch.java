package dev.repopilot.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An analyzer's result for one batch of files.
 *
 * <h2>Why a batch and not a file</h2>
 * Some analyzers pay a large fixed cost per run — PMD loads and instantiates its ruleset before
 * it can look at anything, and doing that once per file would dominate the analysis of a
 * 3 000-file repository. Handing an analyzer a batch lets it amortise that setup while keeping
 * the runner in charge of how big a batch is, of memory limits, and of cancellation.
 *
 * <p>An analyzer must not assume the batch is the whole revision or that its files are
 * related: a batch is a scheduling unit, not a semantic one. Anything an analyzer needs to say
 * about the repository as a whole belongs in {@link #notes}, which are stored on the run.
 *
 * @param findingsByPath findings keyed by repository-relative path, insertion-ordered. A path
 *                       with no findings is absent rather than present with an empty list
 * @param notes          facts about the run that are not findings: an analyzer's configuration
 *                       and the files it could not fully read. A note is never a claim about
 *                       the code, and never an error the caller must handle
 */
public record AnalysisBatch(Map<String, List<StaticFinding>> findingsByPath, List<String> notes) {

    public AnalysisBatch {
        Map<String, List<StaticFinding>> copy = new LinkedHashMap<>();
        if (findingsByPath != null) {
            findingsByPath.forEach((path, findings) -> {
                if (path != null && findings != null && !findings.isEmpty()) {
                    copy.put(path, List.copyOf(findings));
                }
            });
        }
        findingsByPath = Collections.unmodifiableMap(copy);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static AnalysisBatch empty() {
        return new AnalysisBatch(Map.of(), List.of());
    }

    public static AnalysisBatch of(Map<String, List<StaticFinding>> findingsByPath, List<String> notes) {
        return new AnalysisBatch(findingsByPath, notes);
    }

    public List<StaticFinding> forPath(String relativePath) {
        List<StaticFinding> findings = findingsByPath.get(relativePath);
        return findings == null ? List.of() : findings;
    }

    public boolean isEmpty() {
        return findingsByPath.isEmpty();
    }

    public int findingCount() {
        return findingsByPath.values().stream().mapToInt(List::size).sum();
    }
}
