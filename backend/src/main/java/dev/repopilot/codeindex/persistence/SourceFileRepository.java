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

    /**
     * Path search used by investigation retrieval.
     *
     * <p>Matching a question against a path is the cheapest way to find the file somebody
     * meant when they wrote "the OrderService class" without naming a symbol that exists.
     * Ordered shortest-first so a path that <em>is</em> the match beats one that merely
     * contains it.
     */
    @Query("""
            SELECT f FROM SourceFile f
            WHERE f.revisionId = :revisionId
              AND LOWER(f.relativePath) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY LENGTH(f.relativePath) ASC, f.relativePath ASC
            """)
    List<SourceFile> searchByPath(@Param("revisionId") UUID revisionId,
                                  @Param("term") String term,
                                  Pageable pageable);
}
