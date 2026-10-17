package dev.repopilot.api.v1;

import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.analysis.EvidenceCategory;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingSeverity;
import dev.repopilot.analysis.query.AnalysisQueryService;
import dev.repopilot.analysis.query.FindingFilter;
import dev.repopilot.analysis.query.FindingSummaryView;
import dev.repopilot.analysis.query.FindingView;
import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.shared.error.RepoPilotException;
import dev.repopilot.shared.reactive.Blocking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Static-analysis and findings endpoints.
 *
 * <p>Both halves are thin bridges onto the blocking services via {@link Blocking}. The interesting
 * part of this controller is what it refuses to accept: a findings query cannot ask for "all
 * findings everywhere". It is always scoped to a repository, and the service resolves it to one
 * analysis run, because a findings list with no run behind it cannot say which revision it
 * describes.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class AnalysisController {

    private final RepositoryService repositories;
    private final JobService jobs;
    private final AnalysisQueryService analysis;

    public AnalysisController(RepositoryService repositories, JobService jobs, AnalysisQueryService analysis) {
        this.repositories = repositories;
        this.jobs = jobs;
        this.analysis = analysis;
    }

    /** Request body for submitting an analysis job. */
    public record SubmitAnalysisRequest(
            @Size(max = 200, message = "idempotencyKey must be at most 200 characters")
            String idempotencyKey
    ) {
    }

    /**
     * Submits a static-analysis job for the repository's newest revision.
     *
     * <p>Refuses before queueing when the repository has never been indexed, because the job would
     * fail a moment later for the same reason and the caller would have to poll to find out.
     */
    @PostMapping("/{repositoryId}/analyze")
    public Mono<ResponseEntity<JobView>> analyze(@PathVariable UUID repositoryId,
                                                 @Valid @RequestBody(required = false) SubmitAnalysisRequest request) {
        return Blocking.deferOffload(() -> {
            RepositorySummary repository = repositories.require(repositoryId);
            if (repositories.latestRevision(repositoryId).isEmpty()) {
                throw RepoPilotException.conflict(
                        "This repository has not been indexed yet. Run an index job before analysing it.");
            }
            if (jobs.countActiveForRepository(repositoryId) > 0) {
                throw RepoPilotException.conflict(
                        "This repository already has a job in progress. Wait for it to finish before analysing.");
            }
            String key = request == null ? null : request.idempotencyKey();
            return jobs.submit(JobType.STATIC_ANALYSIS, repository.workspaceId(), repositoryId, null, key);
        }).map(submission -> ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.from(submission.job())));
    }

    /**
     * A page of findings from the repository's current analysis run.
     *
     * <p>Ordered most severe first, then by path and line, unless the caller supplies its own sort.
     * An empty page means either "the run found nothing" or "nothing has been analysed"; the summary
     * endpoint is what distinguishes them, and the UI calls both.
     */
    @GetMapping("/{repositoryId}/findings")
    public Mono<PageResponse<FindingView>> findings(@PathVariable UUID repositoryId,
                                                    @RequestParam(required = false) UUID runId,
                                                    @RequestParam(required = false) FindingSeverity severity,
                                                    @RequestParam(required = false) FindingCategory category,
                                                    @RequestParam(required = false) EvidenceCategory evidenceCategory,
                                                    @RequestParam(required = false) String analyzerId,
                                                    @RequestParam(required = false) String path,
                                                    @RequestParam(name = "q", required = false) String text,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            FindingFilter filter = new FindingFilter(runId, severity, category, evidenceCategory,
                    analyzerId, path, text);
            Page<FindingView> result = analysis.findings(repositoryId, filter,
                    Paging.of(page, size, Sort.unsorted()));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    /**
     * Counts and the run header for the repository's findings.
     *
     * <p>Returns zero counts and no run when nothing has been analysed. That is a state with its own
     * meaning, not a 404: the caller asked what we have looked at, and the honest answer is "nothing
     * yet" rather than "not found".
     */
    @GetMapping("/{repositoryId}/findings/summary")
    public Mono<FindingSummaryView> findingsSummary(@PathVariable UUID repositoryId,
                                                    @RequestParam(required = false) UUID revisionId) {
        return Blocking.deferOffload(() -> analysis.summary(repositoryId, revisionId,
                AnalysisRunType.STATIC_ANALYSIS));
    }
}
