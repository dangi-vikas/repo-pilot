package dev.repopilot.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable snapshot identity for a repository.
 *
 * <p>Every derived record in RepoPilot — source files, symbols, edges, findings,
 * investigations, benchmarks — points at a revision. This is what makes the claim
 * "this finding refers to this code" verifiable, and what allows a finding to be
 * compared against a later revision rather than silently drifting.
 *
 * <p>{@link #revision} is a Git commit id when the repository is a Git working
 * tree, otherwise the content fingerprint itself. {@link #fingerprint} is always a
 * content-derived hash of the analysed file set, so two analyses can be proven
 * equivalent or different regardless of Git availability.
 */
@Entity
@Table(name = "repository_revision")
public class RepositoryRevision {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "revision", nullable = false, updatable = false, length = 200)
    private String revision;

    @Column(name = "branch", length = 300)
    private String branch;

    @Column(name = "commit_author", length = 300)
    private String commitAuthor;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "fingerprint", nullable = false, length = 128)
    private String fingerprint;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    // ---- scan accounting (see V2__revision_scan_accounting.sql) ------------

    @Column(name = "enumerated_file_count", nullable = false)
    private long enumeratedFileCount;

    @Column(name = "excluded_file_count", nullable = false)
    private long excludedFileCount;

    @Column(name = "pruned_directory_count", nullable = false)
    private long prunedDirectoryCount;

    @Column(name = "excluded_by_reason", length = 600)
    private String excludedByReason;

    @Column(name = "pruned_by_reason", length = 600)
    private String prunedByReason;

    @Column(name = "compiled_classes_present", nullable = false)
    private boolean compiledClassesPresent;

    protected RepositoryRevision() {
        // Required by JPA.
    }

    public RepositoryRevision(UUID repositoryId, String revision, String branch, String commitAuthor,
                              Instant committedAt, String fingerprint, int fileCount, Instant indexedAt,
                              ScanAccounting scanAccounting) {
        this.repositoryId = repositoryId;
        this.revision = revision;
        this.branch = branch;
        this.commitAuthor = commitAuthor;
        this.committedAt = committedAt;
        this.fingerprint = fingerprint;
        this.fileCount = fileCount;
        this.indexedAt = indexedAt;
        recordScanAccounting(scanAccounting);
    }

    /**
     * Records what the enumeration deliberately did not analyse.
     *
     * <p>Called again when an unchanged repository reuses its revision. The revision
     * identity is the fingerprint of the <em>analysed</em> set, so adding a credential
     * file to an otherwise untouched repository reuses the revision while changing what
     * was excluded. The accounting is therefore refreshed rather than frozen at creation.
     */
    public void recordScanAccounting(ScanAccounting accounting) {
        ScanAccounting value = accounting == null ? ScanAccounting.NONE : accounting;
        this.enumeratedFileCount = value.enumeratedFiles();
        this.excludedFileCount = value.excludedFiles();
        this.prunedDirectoryCount = value.prunedDirectories();
        this.excludedByReason = value.excludedByReason();
        this.prunedByReason = value.prunedByReason();
        this.compiledClassesPresent = value.compiledClassesPresent();
    }

    /** What this revision's enumeration inspected, skipped and refused to walk. */
    public ScanAccounting scanAccounting() {
        return new ScanAccounting(enumeratedFileCount, excludedFileCount, prunedDirectoryCount,
                excludedByReason, prunedByReason, compiledClassesPresent);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getRevision() {
        return revision;
    }

    public String getBranch() {
        return branch;
    }

    public String getCommitAuthor() {
        return commitAuthor;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFileCount() {
        return fileCount;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    /** A short, human-facing revision label such as {@code a1b2c3d} or {@code content:9f3c1a2b}. */
    public String shortLabel() {
        String value = revision;
        if (value != null && value.length() > 12 && value.matches("[0-9a-fA-F]+")) {
            return value.substring(0, 12);
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RepositoryRevision that
                && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
