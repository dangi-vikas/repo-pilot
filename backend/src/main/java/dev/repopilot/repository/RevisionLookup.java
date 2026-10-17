package dev.repopilot.repository;

import java.time.Instant;
import java.util.UUID;

/** Bounded projection of a revision for list endpoints. */
public record RevisionLookup(
        UUID id,
        String revision,
        String branch,
        String fingerprint,
        int fileCount,
        Instant indexedAt
) {
    /** Short label for display, mirroring {@link RepositoryRevision#shortLabel()}. */
    public String shortLabel() {
        if (revision != null && revision.length() > 12 && revision.matches("[0-9a-fA-F]+")) {
            return revision.substring(0, 12);
        }
        return revision;
    }
}
