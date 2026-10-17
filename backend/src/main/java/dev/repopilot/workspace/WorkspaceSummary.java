package dev.repopilot.workspace;

import java.time.Instant;
import java.util.UUID;

/** Read model for a workspace plus its repository count. */
public record WorkspaceSummary(
        UUID id,
        String name,
        String slug,
        String description,
        Instant createdAt,
        long repositoryCount
) {
}
