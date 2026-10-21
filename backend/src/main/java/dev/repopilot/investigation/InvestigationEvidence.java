package dev.repopilot.investigation;

import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One piece of evidence that was in the prompt.
 *
 * <p>Stored whether or not the model cited it, because the question "what was it shown"
 * has to be answerable from the record and not from a log. The excerpt itself is not
 * stored — only its hash and length — so this table is an index of what the model saw,
 * not a copy of it.
 */
@Entity
@Table(name = "investigation_evidence")
public class InvestigationEvidence extends AbstractCreatedEntity {

    /** The only role this build writes: evidence retrieved and offered to the model. */
    public static final String ROLE_RETRIEVED = "RETRIEVED";

    @Column(name = "investigation_id", nullable = false, updatable = false)
    private UUID investigationId;

    @Column(name = "finding_id")
    private UUID findingId;

    @Column(name = "evidence_role", nullable = false, length = 32)
    private String evidenceRole = ROLE_RETRIEVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 48)
    private EvidenceKind evidenceType;

    @Column(name = "label", nullable = false, length = 500)
    private String label;

    @Column(name = "detail", length = 4000)
    private String detail;

    @Column(name = "source_path", length = 1500)
    private String sourcePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "symbol_name", length = 1000)
    private String symbolName;

    @Column(name = "symbol_kind", length = 32)
    private String symbolKind;

    @Column(name = "retrieval_score")
    private Double retrievalScore;

    @Column(name = "retrieval_method", length = 32)
    private String retrievalMethod;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "cited", nullable = false)
    private boolean cited;

    @Column(name = "evidence_key", length = 12)
    private String evidenceKey;

    @Column(name = "excerpt_sha256", length = 64)
    private String excerptSha256;

    @Column(name = "excerpt_chars")
    private Integer excerptChars;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_status", length = 32)
    private SourceStatus sourceStatus;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected InvestigationEvidence() {
    }

    public InvestigationEvidence(UUID investigationId, EvidenceItem item, int rank) {
        this.investigationId = investigationId;
        this.evidenceType = item.kind();
        this.label = item.label();
        this.detail = item.detail();
        this.sourcePath = item.path();
        this.startLine = item.startLine();
        this.endLine = item.endLine();
        this.symbolName = item.symbolName();
        this.symbolKind = item.symbolKind();
        this.retrievalScore = item.retrievalScore();
        this.retrievalMethod = item.retrievalMethod();
        this.rank = rank;
        this.evidenceKey = item.key();
        this.excerptSha256 = item.excerptSha256();
        this.excerptChars = item.excerpt() == null ? null : item.excerpt().length();
    }

    public void recordSourceCheck(SourceStatus status, Instant checkedAt) {
        this.sourceStatus = status;
        this.verifiedAt = checkedAt;
    }

    public void markCited(boolean cited) {
        this.cited = cited;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public String getEvidenceRole() {
        return evidenceRole;
    }

    public EvidenceKind getEvidenceType() {
        return evidenceType;
    }

    public String getLabel() {
        return label;
    }

    public String getDetail() {
        return detail;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public String getSymbolKind() {
        return symbolKind;
    }

    public Double getRetrievalScore() {
        return retrievalScore;
    }

    public String getRetrievalMethod() {
        return retrievalMethod;
    }

    public int getRank() {
        return rank;
    }

    public boolean isCited() {
        return cited;
    }

    public String getEvidenceKey() {
        return evidenceKey;
    }

    public String getExcerptSha256() {
        return excerptSha256;
    }

    public Integer getExcerptChars() {
        return excerptChars;
    }

    public SourceStatus getSourceStatus() {
        return sourceStatus;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
