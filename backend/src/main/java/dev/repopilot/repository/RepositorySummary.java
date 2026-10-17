package dev.repopilot.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a repository.
 *
 * <p>Deliberately omits {@link Repository#getCanonicalLocation()}: the absolute
 * server path is never returned through the API. {@code displayLocation} is the
 * workspace-relative form the UI renders.
 */
public record RepositorySummary(
        UUID id,
        UUID workspaceId,
        String name,
        SourceType sourceType,
        String displayLocation,
        RepositoryStatus status,
        Long sizeBytes,
        int analyzedFileCount,
        String indexingError,
        Instant lastIndexedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static RepositorySummary from(Repository repository) {
        return new RepositorySummary(
                repository.getId(),
                repository.getWorkspaceId(),
                repository.getName(),
                repository.getSourceType(),
                repository.getDisplayLocation(),
                repository.getStatus(),
                repository.getSizeBytes(),
                repository.getAnalyzedFileCount(),
                repository.getIndexingError(),
                repository.getLastIndexedAt(),
                repository.getCreatedAt(),
                repository.getUpdatedAt());
    }
}
