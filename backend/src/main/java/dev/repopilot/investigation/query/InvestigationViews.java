package dev.repopilot.investigation.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read model for investigations.
 *
 * <p>Grouped in one file because the shapes only make sense together: a list row is a detail
 * without the evidence, and a detail is a list row plus everything that was checked. Every
 * field here is something the UI can show without a second request, and nothing here is
 * derived from the model's confidence unless it is labelled as the model's.
 */
public final class InvestigationViews {

    private InvestigationViews() {
    }

    /** A row in the investigation list. */
    public record Summary(
            UUID id,
            String title,
            String question,
            String status,
            boolean grounded,
            String confidence,
            Integer evidenceCount,
            String modelProvider,
            UUID jobId,
            Instant createdAt,
            Instant completedAt,
            String errorMessage
    ) {
    }

    /** Everything one investigation produced. */
    public record Detail(
            UUID id,
            UUID repositoryId,
            UUID revisionId,
            UUID jobId,
            String title,
            String question,
            String scope,
            String status,
            String answer,
            String confidence,
            boolean grounded,
            String retrievalSummary,
            String limitations,
            String modelProvider,
            String modelName,
            Integer promptChars,
            Integer contextChars,
            Integer evidenceCount,
            Long latencyMs,
            String promptSha256,
            Instant createdAt,
            Instant completedAt,
            String errorMessage,
            List<Evidence> evidence,
            List<Claim> claims,
            Diagnostics diagnostics,
            int verifiedClaims,
            int partialClaims,
            int unsupportedClaims
    ) {
    }

    /** One piece of evidence, with the verdict on its excerpt. */
    public record Evidence(
            String key,
            String kind,
            String label,
            String detail,
            String path,
            Integer startLine,
            Integer endLine,
            String symbolName,
            String symbolKind,
            double retrievalScore,
            String retrievalMethod,
            int rank,
            boolean cited,
            String sourceStatus,
            Instant verifiedAt,
            Integer excerptChars
    ) {
    }

    /** One claim, as the model wrote it, with the backend's verdict beside it. */
    public record Claim(
            int index,
            String text,
            String kind,
            List<String> citedKeys,
            String verificationStatus,
            String notes
    ) {
    }

    /** What could not be answered, what did not resolve, and why evidence was left out. */
    public record Diagnostics(
            List<String> unanswered,
            List<String> danglingMarkers,
            List<String> retrievalNotes,
            List<String> parserNotes
    ) {
        public static Diagnostics empty() {
            return new Diagnostics(List.of(), List.of(), List.of(), List.of());
        }
    }
}
