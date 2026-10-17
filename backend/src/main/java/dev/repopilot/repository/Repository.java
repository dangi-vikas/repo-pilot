package dev.repopilot.repository;

import dev.repopilot.shared.persistence.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered Java repository.
 *
 * <p>Foreign keys are modelled as raw {@link UUID} columns rather than JPA
 * associations. That is deliberate: RepoPilot's module boundaries are packages, and
 * lazy associations across modules would let a query in {@code codeindex} silently
 * traverse into {@code repository} and {@code workspace}, defeating the boundary and
 * producing unpredictable fetch behaviour in a reactive request. Object graphs stay
 * inside a module; cross-module reads go through explicit query interfaces.
 *
 * <p>{@link #canonicalLocation} is an absolute, symlink-resolved filesystem path. It
 * is never serialised to a client. {@link #displayLocation} is the sanitised value
 * the UI receives.
 */
@Entity
@Table(name = "repository")
public class Repository extends AbstractAuditedEntity {

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    /** Absolute, real (symlink-resolved) path. Server-side only. */
    @Column(name = "canonical_location", nullable = false, length = 2000)
    private String canonicalLocation;

    /** Sanitised, workspace-relative display path safe to return through the API. */
    @Column(name = "display_location", nullable = false, length = 2000)
    private String displayLocation;

    @Column(name = "default_branch", length = 300)
    private String defaultBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RepositoryStatus status = RepositoryStatus.REGISTERED;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "analyzed_file_count", nullable = false)
    private int analyzedFileCount = 0;

    @Column(name = "indexing_error", length = 2000)
    private String indexingError;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    protected Repository() {
        // Required by JPA.
    }

    public Repository(UUID workspaceId, String name, SourceType sourceType,
                      String canonicalLocation, String displayLocation) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.sourceType = sourceType;
        this.canonicalLocation = canonicalLocation;
        this.displayLocation = displayLocation;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getCanonicalLocation() {
        return canonicalLocation;
    }

    public String getDisplayLocation() {
        return displayLocation;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public RepositoryStatus getStatus() {
        return status;
    }

    public void setStatus(RepositoryStatus status) {
        this.status = status;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getAnalyzedFileCount() {
        return analyzedFileCount;
    }

    public void setAnalyzedFileCount(int analyzedFileCount) {
        this.analyzedFileCount = analyzedFileCount;
    }

    public String getIndexingError() {
        return indexingError;
    }

    public void setIndexingError(String indexingError) {
        this.indexingError = indexingError;
    }

    public Instant getLastIndexedAt() {
        return lastIndexedAt;
    }

    public void setLastIndexedAt(Instant lastIndexedAt) {
        this.lastIndexedAt = lastIndexedAt;
    }

    /** Clears a previous failure once a later attempt succeeds. */
    public void markIndexed(Instant at) {
        this.status = RepositoryStatus.READY;
        this.indexingError = null;
        this.lastIndexedAt = at;
    }

    public void markIndexingFailed(String reason) {
        this.status = RepositoryStatus.FAILED;
        this.indexingError = reason == null ? null
                : reason.substring(0, Math.min(reason.length(), 2000));
    }
}
