package dev.repopilot.investigation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Reads investigations. Writes go through {@link InvestigationService} only. */
public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    /** Newest first, scoped to one repository: a question is always asked of one codebase. */
    Page<Investigation> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

    /** Detail lookup that cannot leak another repository's investigation. */
    Optional<Investigation> findByIdAndRepositoryId(UUID id, UUID repositoryId);
}
