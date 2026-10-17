package dev.repopilot.codeindex.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeSymbolRepository extends JpaRepository<CodeSymbol, UUID> {

    List<CodeSymbol> findBySourceFileIdOrderByStartLineAsc(UUID sourceFileId);

    List<CodeSymbol> findByRevisionIdAndKindOrderByQualifiedNameAsc(UUID revisionId,
                                                                    dev.repopilot.codeindex.model.SymbolKind kind);

    Optional<CodeSymbol> findByIdAndRevisionId(UUID id, UUID revisionId);

    long countByRevisionId(UUID revisionId);

    /**
     * Name-based search used by the code explorer and the retrieval step of AI
     * investigations. Substring matching is deliberately the baseline: it needs no
     * index infrastructure and works on the zero-dependency local profile.
     */
    @Query("""
            SELECT s FROM CodeSymbol s
            WHERE s.revisionId = :revisionId
              AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(s.qualifiedName) LIKE LOWER(CONCAT('%', :term, '%')))
            ORDER BY s.qualifiedName ASC
            """)
    Page<CodeSymbol> search(@Param("revisionId") UUID revisionId,
                            @Param("term") String term, Pageable pageable);

    @Query("SELECT DISTINCT s.qualifiedName FROM CodeSymbol s WHERE s.revisionId = :revisionId "
            + "AND s.kind IN (dev.repopilot.codeindex.model.SymbolKind.CLASS, "
            + "dev.repopilot.codeindex.model.SymbolKind.INTERFACE, "
            + "dev.repopilot.codeindex.model.SymbolKind.ENUM, "
            + "dev.repopilot.codeindex.model.SymbolKind.RECORD, "
            + "dev.repopilot.codeindex.model.SymbolKind.ANNOTATION)")
    List<String> findTypeQualifiedNames(@Param("revisionId") UUID revisionId);

    /**
     * Name-and-kind projection for the resolution pass. Deliberately narrow: the index
     * is held in memory for the whole revision, so it must not carry signatures,
     * Javadoc or annotations.
     */
    @Query("""
            SELECT new dev.repopilot.codeindex.resolve.CodeSymbolLookup(s.id, s.name, s.qualifiedName, s.kind)
            FROM CodeSymbol s
            WHERE s.revisionId = :revisionId
            """)
    List<dev.repopilot.codeindex.resolve.CodeSymbolLookup> findLookups(@Param("revisionId") UUID revisionId);
}
