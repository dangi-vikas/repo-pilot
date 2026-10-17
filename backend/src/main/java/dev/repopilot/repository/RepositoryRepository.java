package dev.repopilot.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    Page<Repository> findByWorkspaceIdOrderByNameAsc(UUID workspaceId, Pageable pageable);

    List<Repository> findByWorkspaceIdOrderByNameAsc(UUID workspaceId);

    Optional<Repository> findByWorkspaceIdAndName(UUID workspaceId, String name);

    long countByWorkspaceId(UUID workspaceId);

    /** All repositories, used by the retention task and the startup recovery pass. */
    @Query("SELECT r FROM Repository r WHERE r.status <> dev.repopilot.repository.RepositoryStatus.INDEXING")
    List<Repository> findAllNotIndexing();

    /** Repositories left in INDEXING after a restart are reconciled by the recovery task. */
    @Query("SELECT r FROM Repository r WHERE r.status = dev.repopilot.repository.RepositoryStatus.INDEXING")
    List<Repository> findAllIndexing();

    @Query("SELECT COUNT(r) FROM Repository r")
    long countAll();

    @Query("SELECT r FROM Repository r WHERE r.id = :id AND r.workspaceId = :workspaceId")
    Optional<Repository> findInWorkspace(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);
}
