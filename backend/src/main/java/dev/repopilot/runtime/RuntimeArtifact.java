package dev.repopilot.runtime;

import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An uploaded runtime artefact: a JFR recording, a heap dump, a thread dump or a benchmark
 * result file.
 *
 * <p>The bytes live in the data directory, never in the database and never inside a
 * repository working tree. What is stored here is the metadata needed to find them again
 * (a content-addressed {@code storage_ref}), to prove they are unchanged (a SHA-256 of the
 * bytes as received) and to know what they are (type, original name, size).
 *
 * <p>{@code analysis_run_id} is the one field written after the row is created: it is set
 * when a run starts reading the artefact. A recording uploaded but never analysed has no
 * run, and that is a state the UI reports rather than hides.
 *
 * <p>{@code purged_at} is the other, and it is not a deletion. Retention removes the file
 * from the store when it is older than the configured window and records when it did so; the
 * row, its run and every measurement the run produced all stay, because those measurements
 * are the only copy of the evidence once the bytes are gone. The row is the record that the
 * evidence existed and where it came from; the file was a copy of it.
 *
 * <p>The entity is therefore append-only in spirit rather than in mechanism: two fields change
 * after creation, and both changes are claims about the file's availability rather than
 * rewrites of what was received. Nothing here changes a hash, a size or a name.
 */
@Entity
@Table(name = "runtime_artifact")
public class RuntimeArtifact extends AbstractCreatedEntity {

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "analysis_run_id")
    private UUID analysisRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 32, updatable = false)
    private RuntimeArtifactType artifactType;

    @Column(name = "original_filename", nullable = false, length = 500, updatable = false)
    private String originalFilename;

    /**
     * SHA-256 of the bytes as received, in lowercase hex.
     *
     * <p>Used for identity, not for security: two uploads of the same recording are the same
     * artefact, and a reader can confirm that the file on disk still hashes to this value.
     */
    @Column(name = "content_hash", nullable = false, length = 128, updatable = false)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Path of the stored copy, relative to the artefacts directory. */
    @Column(name = "storage_ref", nullable = false, length = 1000, updatable = false)
    private String storageRef;

    /**
     * Facts about the artefact established at ingest, as JSON. For a JFR recording this is
     * where the recording's provenance is kept, so a measurement can always be traced back
     * to the environment that produced it.
     */
    @Column(name = "metadata_json", length = 8000)
    private String metadataJson;

    /**
     * When the stored bytes were removed by retention, or null while the file is on disk.
     *
     * <p>Written in one place: the retention pass, after the file is gone. A re-upload of the same
     * bytes clears it, because content-addressed storage puts the file back at exactly the path this
     * row names.
     */
    @Column(name = "purged_at")
    private Instant purgedAt;

    protected RuntimeArtifact() {
    }

    public RuntimeArtifact(UUID repositoryId, RuntimeArtifactType artifactType, String originalFilename,
                           String contentHash, long sizeBytes, String storageRef) {
        this.repositoryId = repositoryId;
        this.artifactType = artifactType;
        this.originalFilename = originalFilename;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.storageRef = storageRef;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public RuntimeArtifactType getArtifactType() {
        return artifactType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    /** Whether the stored file has been removed, leaving the row and its measurements behind. */
    public boolean isPurged() {
        return purgedAt != null;
    }

    /** Records that the stored bytes are gone. Called after the file is deleted, never before. */
    public void markPurged(Instant purgedAt) {
        this.purgedAt = purgedAt;
    }

    /**
     * Records that the stored bytes are present again.
     *
     * <p>Only the upload path calls this, and only for a row whose file has just been written back at
     * the same content-addressed path: the same bytes re-uploaded are the same artefact, so the row
     * must stop saying the file is gone rather than accumulating a second row for it.
     */
    public void markStored() {
        this.purgedAt = null;
    }
}
