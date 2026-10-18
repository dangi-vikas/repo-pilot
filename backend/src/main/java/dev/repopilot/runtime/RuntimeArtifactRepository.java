package dev.repopilot.runtime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads uploaded artefacts. Writes happen in {@link ArtifactStore} and the JFR handler. */
public interface RuntimeArtifactRepository extends JpaRepository<RuntimeArtifact, UUID> {

    Optional<RuntimeArtifact> findByIdAndRepositoryId(UUID id, UUID repositoryId);

    Page<RuntimeArtifact> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

    /**
     * Identity by content, not by filename.
     *
     * <p>Two uploads of the same bytes are the same artefact; a second upload is served by
     * the existing row rather than creating a duplicate run's worth of storage.
     */
    Optional<RuntimeArtifact> findFirstByRepositoryIdAndContentHashOrderByCreatedAtAsc(UUID repositoryId, String contentHash);

    List<RuntimeArtifact> findByAnalysisRunId(UUID analysisRunId);
}
