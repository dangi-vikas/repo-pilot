package dev.repopilot.runtime.query;

import dev.repopilot.runtime.RuntimeArtifact;
import dev.repopilot.runtime.RuntimeArtifactType;

import java.time.Instant;
import java.util.UUID;

/**
 * An uploaded runtime artefact as the API reports it.
 *
 * <p>{@code supported} and {@code unsupportedReason} are carried to the client on purpose. When
 * someone uploads a heap dump this build cannot parse, the useful answer is "stored, not
 * analysed, because heap dump analysis is not implemented" — not a success that quietly
 * produced nothing, and not a refusal that throws away a file the operator may need.
 */
public record RuntimeArtifactView(UUID id,
                                  UUID repositoryId,
                                  RuntimeArtifactType artifactType,
                                  String originalFilename,
                                  String contentHash,
                                  long sizeBytes,
                                  UUID analysisRunId,
                                  Instant createdAt,
                                  boolean supported,
                                  String unsupportedReason,
                                  boolean containsProcessMemory,
                                  long measurementCount) {

    public static RuntimeArtifactView from(RuntimeArtifact artifact, long measurementCount) {
        return new RuntimeArtifactView(
                artifact.getId(),
                artifact.getRepositoryId(),
                artifact.getArtifactType(),
                artifact.getOriginalFilename(),
                artifact.getContentHash(),
                artifact.getSizeBytes(),
                artifact.getAnalysisRunId(),
                artifact.getCreatedAt(),
                artifact.getArtifactType().supported(),
                artifact.getArtifactType().unsupportedReason(),
                artifact.getArtifactType().containsProcessMemory(),
                measurementCount);
    }
}
