package dev.repopilot.analysis;

import dev.repopilot.analysis.query.AnalyzerTally;
import dev.repopilot.analysis.query.CategoryTally;
import dev.repopilot.analysis.query.SeverityTally;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Reads findings.
 *
 * <p>Extends {@link JpaSpecificationExecutor} because the findings list has several
 * independent optional filters (severity, category, analyzer, file). The alternative —
 * one JPQL query with {@code :param IS NULL} guards — binds an enum parameter that is
 * frequently null, which is exactly the shape Hibernate resolves least reliably.
 *
 * <p>Every read is scoped by {@code analysis_run_id}. A re-analysis of the same revision
 * appends a new run rather than deleting the previous findings, so a query that only knew
 * the revision would return two generations of results as if they were one.
 */
public interface AnalysisFindingRepository
        extends JpaRepository<AnalysisFinding, UUID>, JpaSpecificationExecutor<AnalysisFinding> {

    long countByAnalysisRunId(UUID analysisRunId);

    List<AnalysisFinding> findByAnalysisRunIdOrderBySeverityRankDescSourcePathAscStartLineAsc(UUID analysisRunId);

    @Query("""
            SELECT new dev.repopilot.analysis.query.SeverityTally(f.severity, COUNT(f))
            FROM AnalysisFinding f
            WHERE f.analysisRunId = :runId
            GROUP BY f.severity
            """)
    List<SeverityTally> tallyBySeverity(@Param("runId") UUID runId);

    @Query("""
            SELECT new dev.repopilot.analysis.query.CategoryTally(f.category, COUNT(f))
            FROM AnalysisFinding f
            WHERE f.analysisRunId = :runId
            GROUP BY f.category
            """)
    List<CategoryTally> tallyByCategory(@Param("runId") UUID runId);

    @Query("""
            SELECT new dev.repopilot.analysis.query.AnalyzerTally(f.analyzerId, COUNT(f))
            FROM AnalysisFinding f
            WHERE f.analysisRunId = :runId
            GROUP BY f.analyzerId
            ORDER BY COUNT(f) DESC
            """)
    List<AnalyzerTally> tallyByAnalyzer(@Param("runId") UUID runId);
}
