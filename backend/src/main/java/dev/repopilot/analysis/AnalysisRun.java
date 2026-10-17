package dev.repopilot.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One execution of an analysis pipeline over a revision.
 *
 * <p>Separate from the job that triggered it, and deliberately so. A job is a unit of
 * scheduling with a lifecycle that can be cancelled or retried; a run is a unit of
 * <em>evidence</em>. Retrying a failed job produces a second run, and both survive —
 * findings carry the run they came from, so "why is this finding here" always has an
 * answer, and a re-analysis that finds fewer problems is visible as a change rather than
 * as a silent overwrite.
 *
 * <p>This entity does not extend the shared bases: {@code analysis_run} has
 * {@code started_at}/{@code finished_at} rather than {@code created_at}/{@code updated_at},
 * because a run's timestamps are observations about an execution, not bookkeeping about a
 * row. It repeats the explicit-newness contract
 * ({@link Persistable} plus a transient flag) for the same reason the bases do: a
 * pre-populated identifier or a primitive version field makes a new entity look detached
 * and silently turns a {@code persist} into a {@code merge}.
 */
@Entity
@Table(name = "analysis_run")
public class AnalysisRun implements Persistable<UUID> {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "revision_id")
    private UUID revisionId;

    @Column(name = "job_id")
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 48)
    private AnalysisRunType runType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private AnalysisRunStatus status = AnalysisRunStatus.RUNNING;

    /** Analyzer id and version, e.g. {@code custom-checks=1.0.0,pmd=7.28.0}. */
    @Column(name = "analyzer_versions", length = 4000)
    private String analyzerVersions;

    @Column(name = "summary", length = 4000)
    private String summary;

    @Column(name = "finding_count", nullable = false)
    private int findingCount;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Transient
    private boolean newEntity = true;

    protected AnalysisRun() {
    }

    public AnalysisRun(UUID repositoryId, UUID revisionId, UUID jobId, AnalysisRunType runType, Instant startedAt) {
        this.repositoryId = repositoryId;
        this.revisionId = revisionId;
        this.jobId = jobId;
        this.runType = runType;
        this.startedAt = startedAt;
        this.status = AnalysisRunStatus.RUNNING;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Closes the run with what actually happened, including how many findings it produced. */
    public void complete(AnalysisRunStatus finalStatus, String analyzerVersions, String summary,
                         int findingCount, Instant finishedAt) {
        this.status = finalStatus;
        this.analyzerVersions = analyzerVersions;
        this.summary = summary;
        this.findingCount = findingCount;
        this.finishedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public AnalysisRunType getRunType() {
        return runType;
    }

    public AnalysisRunStatus getStatus() {
        return status;
    }

    public String getAnalyzerVersions() {
        return analyzerVersions;
    }

    public String getSummary() {
        return summary;
    }

    public int getFindingCount() {
        return findingCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AnalysisRun that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), id);
    }
}
