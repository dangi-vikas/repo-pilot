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
 *
 * <p>{@code purgedAt} is the other answer the client must be able to tell apart from an upload that
 * never happened. An artefact whose file retention removed is still an artefact: it has a name, a size,
 * a hash and every measurement its run produced. What it no longer has is bytes, so
 * {@link #purgedAt()} says when they went and {@code sizeBytes} stays the size of the file as received.
 * A client that showed a purged artefact as if it were readable would be inviting an analysis that
 * cannot run; one that hid it would be throwing away the only record of the evidence.
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
                                  long measurementCount,
                                  Instant purgedAt) {

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
                measurementCount,
                artifact.getPurgedAt());
    }

    /** Whether the stored file is gone, leaving the record and its measurements. */
    public boolean purged() {
        return purgedAt != null;
    }
}
