package dev.repopilot.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    Optional<AnalysisJob> findByIdempotencyKey(String idempotencyKey);

    List<AnalysisJob> findByStatusOrderByPriorityAscCreatedAtAsc(JobStatus status);

    long countByStatus(JobStatus status);

    Page<AnalysisJob> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

    Page<AnalysisJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AnalysisJob> findByStatusOrderByCreatedAtDesc(JobStatus status, Pageable pageable);

    /**
     * Candidates for dispatch, oldest first, honouring {@code available_at} for backoff.
     *
     * <p>Returns ids only. The dispatcher then claims each candidate in its own
     * transaction, so a job is never executed twice even if two dispatchers select the
     * same candidate.
     */
    @Query("""
            SELECT j.id FROM AnalysisJob j
            WHERE j.status = dev.repopilot.jobs.JobStatus.QUEUED
              AND (j.availableAt IS NULL OR j.availableAt <= :now)
            ORDER BY j.priority ASC, j.createdAt ASC
            """)
    List<UUID> findDispatchableIds(@Param("now") Instant now, Pageable pageable);

    /** Jobs claimed as RUNNING, used to propagate cancellation and to reconcile after a restart. */
    @Query("SELECT j FROM AnalysisJob j WHERE j.status = dev.repopilot.jobs.JobStatus.RUNNING")
    List<AnalysisJob> findRunning();

    /** Running jobs whose heartbeat is older than the supplied instant: presumed dead. */
    @Query("""
            SELECT j FROM AnalysisJob j
            WHERE j.status = dev.repopilot.jobs.JobStatus.RUNNING
              AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :staleBefore)
            """)
    List<AnalysisJob> findStaleRunning(@Param("staleBefore") Instant staleBefore);

    long countByRepositoryIdAndStatusIn(UUID repositoryId, List<JobStatus> statuses);

    long countByStatusIn(List<JobStatus> statuses);

    /**
     * Active jobs for a repository, minus the ones that are merely housekeeping.
     *
     * <p>Used by the "one job at a time per repository" guard. A retention pass is queued at the
     * lowest priority precisely so that it can wait, and it must not make a user's analysis wait
     * behind it: counting it as activity would turn background maintenance into a lock.
     */
    @Query("""
            SELECT COUNT(j) FROM AnalysisJob j
            WHERE j.repositoryId = :repositoryId
              AND j.status IN :statuses
              AND j.jobType NOT IN :excludedTypes
            """)
    long countByRepositoryIdAndStatusInAndJobTypeNotIn(@Param("repositoryId") UUID repositoryId,
                                                       @Param("statuses") List<JobStatus> statuses,
                                                       @Param("excludedTypes") List<JobType> excludedTypes);

    @Query("""
            SELECT COUNT(j) FROM AnalysisJob j
            WHERE j.status = dev.repopilot.jobs.JobStatus.RUNNING
              AND j.repositoryId = :repositoryId
            """)
    long countRunningForRepository(@Param("repositoryId") UUID repositoryId);
}
