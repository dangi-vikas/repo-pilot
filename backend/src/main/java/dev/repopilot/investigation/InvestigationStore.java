package dev.repopilot.investigation;

import dev.repopilot.ai.AiProviderRegistry;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The persistence half of an investigation: the short transactions a job runs between.
 *
 * <h2>Why this is separate from {@link InvestigationService}</h2>
 * The job handler needs to record progress, but it is itself part of the job infrastructure:
 * {@code JobService} depends on the handler registry, which depends on the handler. A handler that
 * also depended on the API-facing service would close a bean cycle through the job service. This
 * store has no dependency on job submission, so the graph stays acyclic and the transaction
 * boundaries are visible in one small class.
 *
 * <p>Nothing here holds a transaction across retrieval or a model call. {@link #begin} commits,
 * the handler does its work with no transaction open, and {@link #complete} writes the outcome in
 * one short transaction.
 */
@Service
public class InvestigationStore {

    private static final Logger log = LoggerFactory.getLogger(InvestigationStore.class);

    private final InvestigationRepository investigations;
    private final InvestigationEvidenceRepository evidence;
    private final InvestigationClaimRepository claims;
    private final AiProviderRegistry registry;

    public InvestigationStore(InvestigationRepository investigations,
                              InvestigationEvidenceRepository evidence,
                              InvestigationClaimRepository claims,
                              AiProviderRegistry registry) {
        this.investigations = investigations;
        this.evidence = evidence;
        this.claims = claims;
        this.registry = registry;
    }

    /** What the handler needs before retrieval can start. */
    public record Start(UUID investigationId, UUID repositoryId, UUID revisionId, String question,
                        String providerId, String modelName) {
    }

    /** Provenance of one model call, recorded whether the call succeeded or not. */
    public record CallInfo(String providerId, String modelName, Integer promptChars,
                           Integer contextChars, Long latencyMs, String promptSha256) {
    }

    /** Everything a completed investigation writes, in one short transaction. */
    public record Outcome(
            String answer,
            String confidence,
            String retrievalSummary,
            String limitations,
            boolean grounded,
            String diagnosticsJson,
            CallInfo call,
            List<EvidenceItem> evidence,
            Map<String, SourceStatus> sourceStatus,
            Set<String> citedKeys,
            List<dev.repopilot.investigation.answer.CitationValidator.ValidatedClaim> claims
    ) {
    }

    @Transactional
    public Start begin(UUID investigationId, UUID jobId) {
        Investigation investigation = require(investigationId);
        if (investigation.getStatus() == InvestigationStatus.SUCCEEDED) {
            throw new IllegalStateException("Investigation " + investigationId
                    + " already has an answer; it is not run again.");
        }
        if (investigation.getStatus() == InvestigationStatus.CANCELLED) {
            // The row was cancelled while the job sat in the queue. The dispatcher treats this as
            // an abort, so the job and the investigation end in the same state.
            throw new dev.repopilot.jobs.JobAbortedException(
                    "Investigation " + investigationId + " was cancelled before it started.", false);
        }
        investigation.markRunning();
        investigation.setJobId(jobId);
        var provider = registry.active().orElseThrow(() -> new IllegalStateException(
                registry.unavailableMessage()));
        return new Start(investigationId, investigation.getRepositoryId(),
                investigation.getRevisionId(), investigation.getQuestion(),
                provider.id(), provider.chatModel());
    }

    @Transactional
    public JobResult complete(UUID investigationId, Outcome outcome) {
        Investigation investigation = require(investigationId);
        if (investigation.getStatus() == InvestigationStatus.CANCELLED) {
            // The operator cancelled while the model was answering. The answer is discarded
            // rather than written over the cancellation: a cancelled investigation must not
            // come back to life because a slow provider finished afterwards.
            log.info("investigation {} was cancelled; its answer was discarded", investigationId);
            return JobResult.of("The investigation was cancelled before the answer was recorded.");
        }

        Instant checkedAt = Instant.now();
        int rank = 0;
        for (EvidenceItem item : outcome.evidence()) {
            rank++;
            InvestigationEvidence row = new InvestigationEvidence(investigationId, item, rank);
            row.markCited(outcome.citedKeys().contains(item.key()));
            row.recordSourceCheck(outcome.sourceStatus()
                    .getOrDefault(item.key(), SourceStatus.NOT_LOCATED), checkedAt);
            evidence.save(row);
        }
        for (var claim : outcome.claims()) {
            claims.save(new InvestigationClaim(investigationId, claim.index(), claim.text(),
                    claim.kind(), String.join(",", claim.citedKeys()), claim.status(), claim.notes()));
        }

        CallInfo call = outcome.call();
        investigation.recordCall(call.providerId(), call.modelName(), call.promptChars(),
                call.contextChars(), call.latencyMs(), call.promptSha256());
        investigation.markSucceeded(outcome.answer(), outcome.confidence(),
                outcome.retrievalSummary(), outcome.limitations(), outcome.evidence().size(),
                outcome.grounded(), outcome.diagnosticsJson(), Instant.now());

        long verified = outcome.claims().stream()
                .filter(claim -> claim.status() == ClaimVerification.VERIFIED).count();
        long partial = outcome.claims().stream()
                .filter(claim -> claim.status() == ClaimVerification.PARTIAL).count();
        long unsupported = outcome.claims().stream()
                .filter(claim -> claim.status() == ClaimVerification.UNSUPPORTED).count();

        String verdict = outcome.grounded()
                ? "the answer is grounded: every claim cites evidence that was in the prompt and resolved"
                : "the answer is NOT grounded: " + unsupported + " claim(s) unsupported, "
                  + partial + " partly supported of " + outcome.claims().size();
        String summary = "Answer from " + outcome.evidence().size() + " evidence item(s) and "
                + outcome.claims().size() + " claim(s); " + verified + " verified, " + partial
                + " partial, " + unsupported + " unsupported — " + verdict + ".";
        log.info("investigation {} completed: {}", investigationId, summary);
        return JobResult.of(summary);
    }

    @Transactional
    public void fail(UUID investigationId, String message, CallInfo call) {
        Investigation investigation = investigations.findById(investigationId).orElse(null);
        if (investigation == null) {
            log.warn("investigation {} disappeared before its failure could be recorded", investigationId);
            return;
        }
        if (call != null) {
            investigation.recordCall(call.providerId(), call.modelName(), call.promptChars(),
                    call.contextChars(), call.latencyMs(), call.promptSha256());
        }
        investigation.markFailed(message, Instant.now());
    }

    /** Marks a queued investigation cancelled. A running one is left to its handler. */
    @Transactional
    public void markCancelledIfQueued(UUID investigationId) {
        investigations.findById(investigationId).ifPresent(investigation -> {
            if (investigation.getStatus() == InvestigationStatus.QUEUED) {
                investigation.markCancelled(Instant.now());
            }
        });
    }

    /** Records a cancellation observed by the handler. A finished answer is never withdrawn. */
    @Transactional
    public void cancel(UUID investigationId) {
        investigations.findById(investigationId).ifPresent(investigation -> {
            if (investigation.getStatus() != InvestigationStatus.SUCCEEDED) {
                investigation.markCancelled(Instant.now());
            }
        });
    }

    private Investigation require(UUID investigationId) {
        return investigations.findById(investigationId)
                .orElseThrow(() -> RepoPilotException.notFound("Investigation", investigationId));
    }
}
