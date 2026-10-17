package dev.repopilot.codeindex.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    List<SourceFile> findByRevisionIdOrderByRelativePathAsc(UUID revisionId);

    Page<SourceFile> findByRevisionIdOrderByRelativePathAsc(UUID revisionId, Pageable pageable);

    Optional<SourceFile> findByRevisionIdAndRelativePath(UUID revisionId, String relativePath);

    long countByRevisionId(UUID revisionId);

    Optional<SourceFile> findByIdAndRevisionId(UUID id, UUID revisionId);

    /**
     * File id plus declared package for a whole revision.
     *
     * <p>Reference resolution needs the package each reference was written in, and must not
     * read file contents or load full entities to obtain it.
     */
    @Query("SELECT new dev.repopilot.codeindex.resolve.SourceFilePackage(f.id, f.packageName) "
            + "FROM SourceFile f WHERE f.revisionId = :revisionId")
    List<dev.repopilot.codeindex.resolve.SourceFilePackage> findPackages(@Param("revisionId") UUID revisionId);

    /** Idempotency guard: an indexing retry for the same revision clears prior rows first. */
    @Modifying
    @Query("DELETE FROM SourceFile f WHERE f.revisionId = :revisionId")
    int deleteByRevisionId(@Param("revisionId") UUID revisionId);

    long countByRevisionIdAndParseStatus(UUID revisionId, dev.repopilot.codeindex.parse.ParseStatus parseStatus);
}
