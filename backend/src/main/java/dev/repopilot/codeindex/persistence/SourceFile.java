package dev.repopilot.codeindex.persistence;

import dev.repopilot.repository.scan.DetectedLanguage;
import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A file recorded for a revision, whether or not it parsed.
 *
 * <p>Every enumerated file that enters the analysis boundary is recorded here, even
 * when parsing fails. That is what allows the coverage report to state, truthfully,
 * how much of a repository RepoPilot actually understood rather than reporting only
 * the files it succeeded on.
 *
 * <p>{@link #parseError} holds a bounded, redacted description. Source text is never
 * persisted; {@link #contentHash} is what allows change detection without storing
 * content.
 */
@Entity
@Table(name = "source_file")
@AttributeOverride(name = "createdAt", column = @Column(name = "indexed_at", nullable = false, updatable = false))
public class SourceFile extends AbstractCreatedEntity {

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "relative_path", nullable = false, updatable = false, length = 1500)
    private String relativePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 32)
    private DetectedLanguage language;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "line_count")
    private Integer lineCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 32)
    private ParseStatus parseStatus;

    @Column(name = "parse_error", length = 2000)
    private String parseError;

    @Column(name = "package_name", length = 500)
    private String packageName;

    protected SourceFile() {
        // Required by JPA.
    }

    public SourceFile(UUID revisionId, String relativePath, DetectedLanguage language, long sizeBytes,
                      String contentHash, Integer lineCount, ParseStatus parseStatus,
                      String parseError, String packageName) {
        this.revisionId = revisionId;
        this.relativePath = relativePath;
        this.language = language;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.lineCount = lineCount;
        this.parseStatus = parseStatus;
        this.parseError = parseError;
        this.packageName = packageName;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public DetectedLanguage getLanguage() {
        return language;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Integer getLineCount() {
        return lineCount;
    }

    public ParseStatus getParseStatus() {
        return parseStatus;
    }

    public String getParseError() {
        return parseError;
    }

    public String getPackageName() {
        return packageName;
    }
}
