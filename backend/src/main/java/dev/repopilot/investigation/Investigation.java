package dev.repopilot.investigation;

import dev.repopilot.shared.persistence.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One question asked of a revision, with the answer and the verdict on it.
 *
 * <p>The row is the record of an <em>interaction</em>, not of a model call: it holds the
 * question that was asked, the evidence that was actually retrieved, the answer as the
 * model wrote it, and — separately — what the backend could verify about it. Keeping the
 * answer and the verdict in different places is deliberate; a single "verified answer"
 * column would make it impossible to show what the model said when it was wrong.
 *
 * <p>{@code grounded} is not "the model was confident". It is true only when every claim
 * cited evidence that was in the prompt and every citation resolved. A run can succeed
 * and be ungrounded; the UI shows both facts.
 */
@Entity
@Table(name = "investigation")
public class Investigation extends AbstractAuditedEntity {

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "revision_id")
    private UUID revisionId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "question", nullable = false, length = 4000)
    private String question;

    @Column(name = "scope", length = 1000)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvestigationStatus status = InvestigationStatus.QUEUED;

    /** The model's answer, verbatim. Never rewritten by the validator. */
    @Column(name = "answer", length = 20000)
    private String answer;

    /** The model's own confidence label; the backend reports but does not endorse it. */
    @Column(name = "confidence", length = 16)
    private String confidence;

    @Column(name = "retrieval_summary", length = 4000)
    private String retrievalSummary;

    @Column(name = "limitations", length = 2000)
    private String limitations;

    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "prompt_chars")
    private Integer promptChars;

    @Column(name = "context_chars")
    private Integer contextChars;

    @Column(name = "evidence_count")
    private Integer evidenceCount;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "grounded", nullable = false)
    private boolean grounded;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "prompt_sha256", length = 64)
    private String promptSha256;

    /** Unanswered questions, dangling markers and retrieval notes, as JSON. */
    @Column(name = "diagnostics_json", length = 4000)
    private String diagnosticsJson;

    protected Investigation() {
    }

    public Investigation(UUID repositoryId, UUID revisionId, String title, String question, String scope) {
        this.repositoryId = repositoryId;
        this.revisionId = revisionId;
        this.title = title;
        this.question = question;
        this.scope = scope;
    }

    public void markRunning() {
        this.status = InvestigationStatus.RUNNING;
        this.errorMessage = null;
    }

    public void markSucceeded(String answer, String confidence, String retrievalSummary,
                              String limitations, int evidenceCount, boolean grounded,
                              String diagnosticsJson, Instant completedAt) {
        this.status = InvestigationStatus.SUCCEEDED;
        this.answer = answer;
        this.confidence = confidence;
        this.retrievalSummary = retrievalSummary;
        this.limitations = limitations;
        this.evidenceCount = evidenceCount;
        this.grounded = grounded;
        this.diagnosticsJson = diagnosticsJson;
        this.completedAt = completedAt;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage, Instant completedAt) {
        this.status = InvestigationStatus.FAILED;
        this.errorMessage = truncate(errorMessage, 2000);
        this.completedAt = completedAt;
    }

    public void markCancelled(Instant completedAt) {
        this.status = InvestigationStatus.CANCELLED;
        this.completedAt = completedAt;
    }

    /** Records what the provider and the prompt were, whether or not an answer was produced. */
    public void recordCall(String modelProvider, String modelName, Integer promptChars,
                           Integer contextChars, Long latencyMs, String promptSha256) {
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.promptChars = promptChars;
        this.contextChars = contextChars;
        this.latencyMs = latencyMs;
        this.promptSha256 = promptSha256;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
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

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getTitle() {
        return title;
    }

    public String getQuestion() {
        return question;
    }

    public String getScope() {
        return scope;
    }

    public InvestigationStatus getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public String getConfidence() {
        return confidence;
    }

    public String getRetrievalSummary() {
        return retrievalSummary;
    }

    public String getLimitations() {
        return limitations;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public Integer getPromptChars() {
        return promptChars;
    }

    public Integer getContextChars() {
        return contextChars;
    }

    public Integer getEvidenceCount() {
        return evidenceCount;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public boolean isGrounded() {
        return grounded;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getPromptSha256() {
        return promptSha256;
    }

    public String getDiagnosticsJson() {
        return diagnosticsJson;
    }
}
