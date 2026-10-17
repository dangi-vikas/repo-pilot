package dev.repopilot.workspace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Workspace> findAllByOrderByNameAsc(Pageable pageable);

    /**
     * Workspaces with their repository count, in one query.
     *
     * <p>Uses an explicit entity join with {@code COUNT}/{@code GROUP BY} rather than
     * a correlated subquery: JPQL does not permit subqueries in the select list, and
     * this form is supported by every JPA provider RepoPilot targets. Avoids the
     * classic N+1 count-per-workspace pattern.
     */
    @Query("""
            SELECT new dev.repopilot.workspace.WorkspaceSummary(
                w.id, w.name, w.slug, w.description, w.createdAt, COUNT(r))
            FROM Workspace w
            LEFT JOIN Repository r ON r.workspaceId = w.id
            GROUP BY w.id, w.name, w.slug, w.description, w.createdAt
            ORDER BY w.name ASC
            """)
    List<WorkspaceSummary> findAllSummaries();

    @Query("""
            SELECT new dev.repopilot.workspace.WorkspaceSummary(
                w.id, w.name, w.slug, w.description, w.createdAt, COUNT(r))
            FROM Workspace w
            LEFT JOIN Repository r ON r.workspaceId = w.id
            WHERE w.id = :id
            GROUP BY w.id, w.name, w.slug, w.description, w.createdAt
            """)
    Optional<WorkspaceSummary> findSummaryById(@Param("id") UUID id);
}
