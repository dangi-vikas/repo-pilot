package dev.repopilot.codeindex.persistence;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.EdgeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, UUID> {

    List<DependencyEdge> findBySourceSymbolIdOrderBySourceLineAsc(UUID sourceSymbolId);

    /** Incoming edges: what depends on this symbol. Used by the impact view. */
    List<DependencyEdge> findByTargetSymbolIdOrderBySourceLineAsc(UUID targetSymbolId);

    Page<DependencyEdge> findByRevisionIdOrderBySourceLineAsc(UUID revisionId, Pageable pageable);

    long countByRevisionId(UUID revisionId);

    long countByRevisionIdAndResolution(UUID revisionId, EdgeResolution resolution);

    long countByRevisionIdAndEdgeType(UUID revisionId, EdgeType edgeType);

    /**
     * A bounded page of the graph.
     *
     * <p>Ordered by file, then line, then id. The id tie-break matters: without it two edges
     * on the same line have no defined relative order, so successive pages could repeat or
     * skip an edge.
     */
    @Query("SELECT e FROM DependencyEdge e WHERE e.revisionId = :revisionId "
            + "AND e.edgeType IN :types ORDER BY e.sourceFileId ASC, e.sourceLine ASC, e.id ASC")
    Page<DependencyEdge> findPageByRevisionIdAndTypes(@Param("revisionId") UUID revisionId,
                                                      @Param("types") List<EdgeType> types,
                                                      Pageable pageable);

    @Query("SELECT COUNT(e) FROM DependencyEdge e WHERE e.revisionId = :revisionId "
            + "AND e.edgeType IN :types")
    long countByRevisionIdAndTypes(@Param("revisionId") UUID revisionId,
                                   @Param("types") List<EdgeType> types);

    /** Page of edges still awaiting resolution. Drives the streaming resolution pass. */
    Page<DependencyEdge> findByRevisionIdAndResolution(UUID revisionId, EdgeResolution resolution, Pageable pageable);

    @Query("SELECT DISTINCT e.targetName FROM DependencyEdge e WHERE e.revisionId = :revisionId")
    List<String> findDistinctTargetNames(@Param("revisionId") UUID revisionId);

    @Modifying
    @Query("DELETE FROM DependencyEdge e WHERE e.revisionId = :revisionId")
    int deleteByRevisionId(@Param("revisionId") UUID revisionId);
}
