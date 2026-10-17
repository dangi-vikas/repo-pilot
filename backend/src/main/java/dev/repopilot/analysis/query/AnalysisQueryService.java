package dev.repopilot.analysis.query;

import dev.repopilot.analysis.AnalysisFinding;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.analysis.AnalysisFindingRepository;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingSeverity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read path for findings.
 *
 * <p>Every read is scoped to one analysis run, resolved here rather than left to the
 * caller. A findings endpoint that accepted only a repository id would have to guess which
 * run the results describe, and the wrong guess shows findings for code that no longer
 * exists.
 */
@Service
public class AnalysisQueryService {

    /**
     * Default ordering: most severe first, then reading order within a file. Reading order
     * is path then line, so a reviewer can walk a file top to bottom instead of jumping
     * between hunks.
     */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("severityRank"),
            Sort.Order.asc("sourcePath"),
            Sort.Order.asc("startLine"));

    /** Hard ceiling on page size, so a client cannot ask for the whole findings table at once. */
    private static final int MAX_PAGE_SIZE = 500;

    private final AnalysisRunRepository runs;
    private final AnalysisFindingRepository findings;

    public AnalysisQueryService(AnalysisRunRepository runs, AnalysisFindingRepository findings) {
        this.runs = runs;
        this.findings = findings;
    }

    /**
     * The run a repository's findings currently describe.
     *
     * @param revisionId when given, the newest run for that revision; otherwise the newest
     *                   run for the repository, whichever revision it analysed
     */
    @Transactional(readOnly = true)
    public Optional<AnalysisRun> currentRun(UUID repositoryId, UUID revisionId, AnalysisRunType runType) {
        return revisionId == null
                ? runs.findFirstByRepositoryIdAndRunTypeOrderByStartedAtDesc(repositoryId, runType)
                : runs.findFirstByRevisionIdAndRunTypeOrderByStartedAtDesc(revisionId, runType);
    }

    /**
     * Counts and run header for a repository's findings.
     *
     * <p>Returns a view with no run and zero counts when nothing has been analysed. That is
     * deliberately not an error: "we have not looked" and "we looked and found nothing" are
     * different answers, and only this response can distinguish them.
     */
    @Transactional(readOnly = true)
    public FindingSummaryView summary(UUID repositoryId, UUID revisionId, AnalysisRunType runType) {
        return currentRun(repositoryId, revisionId, runType)
                .map(run -> new FindingSummaryView(
                        repositoryId,
                        run.getId(),
                        run.getRevisionId(),
                        run.getStatus(),
                        run.getStartedAt(),
                        run.getFinishedAt(),
                        run.getAnalyzerVersions(),
                        run.getSummary(),
                        findings.countByAnalysisRunId(run.getId()),
                        bySeverity(run.getId()),
                        byCategory(run.getId()),
                        byAnalyzer(run.getId())))
                .orElseGet(() -> new FindingSummaryView(
                        repositoryId, null, null, null, null, null, null, null,
                        0L, Map.of(), Map.of(), Map.of()));
    }

    /**
     * A page of findings.
     *
     * <p>When the filter names a run it is used; otherwise the repository's current run is
     * resolved. If neither exists the result is an empty page rather than an error, for the
     * same reason as {@link #summary}: absence of analysis is a state, not a failure.
     */
    @Transactional(readOnly = true)
    public Page<FindingView> findings(UUID repositoryId, FindingFilter filter, Pageable pageable) {
        UUID runId = filter.runId();
        if (runId == null) {
            runId = currentRun(repositoryId, null, AnalysisRunType.STATIC_ANALYSIS)
                    .map(AnalysisRun::getId)
                    .orElse(null);
        }
        if (runId == null) {
            return Page.empty(pageable);
        }

        FindingFilter scoped = new FindingFilter(runId, filter.severity(), filter.category(),
                filter.evidenceCategory(), filter.analyzerId(), filter.sourcePath(), filter.text());
        Pageable effective = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), DEFAULT_SORT)
                : PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), pageable.getSort());
        return findings.findAll(specification(scoped), effective).map(FindingView::from);
    }

    private Map<FindingSeverity, Long> bySeverity(UUID runId) {
        Map<FindingSeverity, Long> tally = new EnumMap<>(FindingSeverity.class);
        for (SeverityTally row : findings.tallyBySeverity(runId)) {
            tally.put(row.severity(), row.total());
        }
        return orderedSeverities(tally);
    }

    private Map<FindingCategory, Long> byCategory(UUID runId) {
        Map<FindingCategory, Long> tally = new EnumMap<>(FindingCategory.class);
        for (CategoryTally row : findings.tallyByCategory(runId)) {
            tally.put(row.category(), row.total());
        }
        return Map.copyOf(tally);
    }

    private Map<String, Long> byAnalyzer(UUID runId) {
        Map<String, Long> tally = new LinkedHashMap<>();
        for (AnalyzerTally row : findings.tallyByAnalyzer(runId)) {
            tally.put(row.analyzerId(), row.total());
        }
        return Map.copyOf(tally);
    }

    /** Severities in descending urgency so clients can render the map without sorting it. */
    private static Map<FindingSeverity, Long> orderedSeverities(Map<FindingSeverity, Long> tally) {
        Map<FindingSeverity, Long> ordered = new LinkedHashMap<>();
        for (FindingSeverity severity : FindingSeverity.values()) {
            Long total = tally.get(severity);
            if (total != null) {
                ordered.put(severity, total);
            }
        }
        return Map.copyOf(ordered);
    }

    private static Specification<AnalysisFinding> specification(FindingFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("analysisRunId"), filter.runId()));
            if (filter.severity() != null) {
                predicates.add(builder.equal(root.get("severity"), filter.severity()));
            }
            if (filter.category() != null) {
                predicates.add(builder.equal(root.get("category"), filter.category()));
            }
            if (filter.evidenceCategory() != null) {
                predicates.add(builder.equal(root.get("evidenceCategory"), filter.evidenceCategory()));
            }
            if (filter.analyzerId() != null && !filter.analyzerId().isBlank()) {
                predicates.add(builder.equal(root.get("analyzerId"), filter.analyzerId().strip()));
            }
            if (filter.sourcePath() != null && !filter.sourcePath().isBlank()) {
                predicates.add(builder.equal(root.get("sourcePath"), filter.sourcePath().strip()));
            }
            if (filter.text() != null && !filter.text().isBlank()) {
                String needle = "%" + filter.text().strip().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), needle),
                        builder.like(builder.lower(root.get("ruleId")), needle),
                        builder.like(builder.lower(root.get("sourcePath")), needle)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
