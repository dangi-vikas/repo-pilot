package dev.repopilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryRevisionRepository extends JpaRepository<RepositoryRevision, UUID> {

    Optional<RepositoryRevision> findByRepositoryIdAndRevision(UUID repositoryId, String revision);

    Optional<RepositoryRevision> findFirstByRepositoryIdOrderByIndexedAtDesc(UUID repositoryId);

    List<RepositoryRevision> findByRepositoryIdOrderByIndexedAtDesc(UUID repositoryId);

    @Query("""
            SELECT new dev.repopilot.repository.RevisionLookup(
                rr.id, rr.revision, rr.branch, rr.fingerprint, rr.fileCount, rr.indexedAt)
            FROM RepositoryRevision rr
            WHERE rr.repositoryId = :repositoryId
            ORDER BY rr.indexedAt DESC
            """)
    List<RevisionLookup> findLookupsByRepository(@Param("repositoryId") UUID repositoryId);
}
