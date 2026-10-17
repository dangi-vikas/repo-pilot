package dev.repopilot.analysis.query;

import dev.repopilot.analysis.AnalysisFinding;
import dev.repopilot.analysis.EvidenceCategory;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingConfidence;
import dev.repopilot.analysis.FindingSeverity;
import dev.repopilot.analysis.VerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A finding as the API exposes it.
 *
 * <p>Carries the fields that make the claim auditable rather than just the fields that make
 * it readable: which analyzer and rule produced it, which run it belongs to, its evidence
 * category, and whether its location was checked. A client that renders a finding without
 * the severity and verification status is rendering an assertion with no provenance.
 *
 * <p>{@code startLine} and {@code endLine} are 1-based and may be null for a file-level
 * finding. Zero is never used as a line number; a client must treat absent as absent.
 */
public record FindingView(
        UUID id,
        UUID runId,
        String analyzerId,
        String ruleId,
        String findingType,
        String title,
        String summary,
        FindingSeverity severity,
        FindingConfidence confidence,
        FindingCategory category,
        EvidenceCategory evidenceCategory,
        VerificationStatus verificationStatus,
        String sourcePath,
        Integer startLine,
        Integer endLine,
        String impact,
        String recommendedAction,
        String limitations,
        UUID symbolId,
        Instant createdAt
) {

    public static FindingView from(AnalysisFinding finding) {
        return new FindingView(
                finding.getId(),
                finding.getAnalysisRunId(),
                finding.getAnalyzerId(),
                finding.getRuleId(),
                finding.getFindingType(),
                finding.getTitle(),
                finding.getSummary(),
                finding.getSeverity(),
                finding.getConfidence(),
                finding.getCategory(),
                finding.getEvidenceCategory(),
                finding.getVerificationStatus(),
                finding.getSourcePath(),
                finding.getStartLine(),
                finding.getEndLine(),
                finding.getImpact(),
                finding.getRecommendedAction(),
                finding.getLimitations(),
                finding.getSymbolId(),
                finding.getCreatedAt());
    }
}
