package dev.repopilot.runtime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads uploaded artefacts. Writes happen in {@link ArtifactStore} and the runtime handlers. */
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

    /**
     * The artefacts whose stored bytes are older than the retention cutoff.
     *
     * <p>Rows that are already purged are excluded: their bytes are not on disk, so including them
     * would have the pass count the same file as reclaimed on every run.
     */
    List<RuntimeArtifact> findByRepositoryIdAndPurgedAtIsNullAndCreatedAtBefore(UUID repositoryId, Instant cutoff);

    /** Artefacts whose bytes are still stored, newest first. */
    List<RuntimeArtifact> findByRepositoryIdAndPurgedAtIsNullOrderByCreatedAtDesc(UUID repositoryId);

    long countByRepositoryIdAndPurgedAtIsNull(UUID repositoryId);

    long countByRepositoryIdAndPurgedAtIsNotNull(UUID repositoryId);

    /** Total bytes of the files still on disk for a repository. */
    @Query("""
            SELECT COALESCE(SUM(a.sizeBytes), 0) FROM RuntimeArtifact a
            WHERE a.repositoryId = :repositoryId AND a.purgedAt IS NULL
            """)
    long sumStoredBytes(@Param("repositoryId") UUID repositoryId);

    /** Total bytes of the files retention has removed for a repository. */
    @Query("""
            SELECT COALESCE(SUM(a.sizeBytes), 0) FROM RuntimeArtifact a
            WHERE a.repositoryId = :repositoryId AND a.purgedAt IS NOT NULL
            """)
    long sumPurgedBytes(@Param("repositoryId") UUID repositoryId);

    /**
     * Whether any other row points at the same stored file.
     *
     * <p>Defensive rather than load-bearing: uploads deduplicate by content hash, so one repository
     * holds one row per stored file and two rows sharing a {@code storage_ref} should not exist. The
     * retention pass asks anyway before it deletes anything, because the cost of the question is one
     * index lookup and the cost of being wrong is destroying a file another row still describes.
     */
    boolean existsByStorageRefAndIdNot(String storageRef, UUID id);

    /** Marks artefacts purged in one statement, after their files have been removed. */
    @Modifying
    @Query("""
            UPDATE RuntimeArtifact a SET a.purgedAt = :purgedAt
            WHERE a.id IN :ids AND a.purgedAt IS NULL
            """)
    int markPurged(@Param("ids") Collection<UUID> ids, @Param("purgedAt") Instant purgedAt);
}
