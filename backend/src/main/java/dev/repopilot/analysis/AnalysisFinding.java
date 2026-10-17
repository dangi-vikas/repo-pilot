package dev.repopilot.analysis;

import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A persisted finding: one analyzer's claim about one place in a revision.
 *
 * <p>Append-only. A finding is never updated, because changing it would rewrite history:
 * the row records what an analyzer said about a specific revision at a specific time, and
 * a later run produces its own rows. {@link AbstractCreatedEntity} is the right base for
 * that — no version column, no {@code updated_at}, both of which would imply an update
 * path that must not exist.
 *
 * <p>Foreign keys are raw {@link UUID} columns rather than associations, matching the rest
 * of the schema: the analysis package reads code-index rows but must not be able to walk
 * into them, and `finding` rows are the one place where a mistake would make an
 * unverified claim look verified.
 *
 * <p>{@code evidenceCategory}, {@code confidence} and {@code verificationStatus} are
 * additionally non-null in the database. They are the columns that make a claim honest, so
 * a bug that left one of them null must fail at write time rather than render as an
 * unqualified statement.
 */
@Entity
@Table(name = "finding")
public class AnalysisFinding extends AbstractCreatedEntity {

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "revision_id", updatable = false)
    private UUID revisionId;

    @Column(name = "analysis_run_id", updatable = false)
    private UUID analysisRunId;

    @Column(name = "source_file_id", updatable = false)
    private UUID sourceFileId;

    @Column(name = "symbol_id", updatable = false)
    private UUID symbolId;

    @Column(name = "fingerprint", nullable = false, length = 200, updatable = false)
    private String fingerprint;

    @Column(name = "finding_type", nullable = false, length = 64, updatable = false)
    private String findingType;

    @Column(name = "title", nullable = false, length = 500, updatable = false)
    private String title;

    @Column(name = "summary", length = 4000, updatable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16, updatable = false)
    private FindingSeverity severity;

    /**
     * Denormalised ordering key for {@link #severity}. See
     * {@code V3__finding_severity_rank.sql}: sorting by the severity name would sort by
     * spelling, which puts INFO between HIGH and LOW.
     */
    @Column(name = "severity_rank", nullable = false, updatable = false)
    private short severityRank;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_category", nullable = false, length = 32, updatable = false)
    private EvidenceCategory evidenceCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 16, updatable = false)
    private FindingConfidence confidence;

    @Column(name = "analyzer_id", nullable = false, length = 64, updatable = false)
    private String analyzerId;

    @Column(name = "rule_id", length = 200, updatable = false)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 64, updatable = false)
    private FindingCategory category;

    @Column(name = "source_path", length = 1500, updatable = false)
    private String sourcePath;

    @Column(name = "start_line", updatable = false)
    private Integer startLine;

    @Column(name = "end_line", updatable = false)
    private Integer endLine;

    @Column(name = "impact", length = 2000, updatable = false)
    private String impact;

    @Column(name = "recommended_action", length = 4000, updatable = false)
    private String recommendedAction;

    @Column(name = "limitations", length = 2000, updatable = false)
    private String limitations;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32, updatable = false)
    private VerificationStatus verificationStatus;

    protected AnalysisFinding() {
    }

    public AnalysisFinding(UUID repositoryId,
                           UUID revisionId,
                           UUID analysisRunId,
                           UUID sourceFileId,
                           UUID symbolId,
                           String fingerprint,
                           String findingType,
                           String title,
                           String summary,
                           FindingSeverity severity,
                           EvidenceCategory evidenceCategory,
                           FindingConfidence confidence,
                           String analyzerId,
                           String ruleId,
                           FindingCategory category,
                           String sourcePath,
                           Integer startLine,
                           Integer endLine,
                           String impact,
                           String recommendedAction,
                           String limitations,
                           VerificationStatus verificationStatus) {
        this.repositoryId = repositoryId;
        this.revisionId = revisionId;
        this.analysisRunId = analysisRunId;
        this.sourceFileId = sourceFileId;
        this.symbolId = symbolId;
        this.fingerprint = fingerprint;
        this.findingType = findingType;
        this.title = title;
        this.summary = summary;
        this.severity = severity;
        this.severityRank = severity == null ? 0 : (short) severity.rank();
        this.evidenceCategory = evidenceCategory;
        this.confidence = confidence;
        this.analyzerId = analyzerId;
        this.ruleId = ruleId;
        this.category = category;
        this.sourcePath = sourcePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.impact = impact;
        this.recommendedAction = recommendedAction;
        this.limitations = limitations;
        this.verificationStatus = verificationStatus;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getSymbolId() {
        return symbolId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getFindingType() {
        return findingType;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public short getSeverityRank() {
        return severityRank;
    }

    public EvidenceCategory getEvidenceCategory() {
        return evidenceCategory;
    }

    public FindingConfidence getConfidence() {
        return confidence;
    }

    public String getAnalyzerId() {
        return analyzerId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public FindingCategory getCategory() {
        return category;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public String getImpact() {
        return impact;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public String getLimitations() {
        return limitations;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }
}
