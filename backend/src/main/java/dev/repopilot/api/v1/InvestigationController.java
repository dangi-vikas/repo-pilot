package dev.repopilot.api.v1;

import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.investigation.InvestigationService;
import dev.repopilot.investigation.query.InvestigationQueryService;
import dev.repopilot.investigation.query.InvestigationViews;
import dev.repopilot.shared.reactive.Blocking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Investigation endpoints: ask a question of an indexed revision, read the answer and its verdict.
 *
 * <p>Asking is a job, not a request. A local model can take minutes, and a request that waited
 * for it would be a request the browser has to hold open with no way to cancel, no record if the
 * tab closes, and no visibility in the job list. The POST therefore returns a job and an
 * investigation id; the detail endpoint is polled while the job is queued or running.
 *
 * <p>There is no endpoint that returns "just the answer". The evidence, the claims and the
 * verdicts travel with it, because an answer without the citations that support it is exactly the
 * artefact this screen exists to avoid.
 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/investigations")
public class InvestigationController {

    private final InvestigationService service;
    private final InvestigationQueryService queries;

    public InvestigationController(InvestigationService service, InvestigationQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    /**
     * A question about one repository.
     *
     * @param question the question; it becomes the investigation's title and its retrieval terms
     * @param scope    optional note about the area of interest, stored and shown with the answer
     */
    public record AskRequest(
            @NotBlank(message = "question is required")
            @Size(min = InvestigationService.MIN_QUESTION_CHARS, max = 2000,
                    message = "question must be between " + InvestigationService.MIN_QUESTION_CHARS
                            + " and 2000 characters")
            String question,
            @Size(max = 500, message = "scope must be at most 500 characters")
            String scope
    ) {
    }

    /** What the caller needs to follow the work: the investigation, the job and the current state. */
    public record SubmissionView(UUID investigationId, UUID jobId, String status) {
    }

    @PostMapping
    public Mono<ResponseEntity<SubmissionView>> ask(@PathVariable UUID repositoryId,
                                                    @Valid @RequestBody AskRequest request) {
        return Blocking.deferOffload(() -> service.submit(repositoryId, request.question(), request.scope()))
                .map(submission -> ResponseEntity.status(HttpStatus.ACCEPTED).body(
                        new SubmissionView(submission.investigationId(), submission.jobId(),
                                submission.status())));
    }

    @GetMapping
    public Mono<PageResponse<InvestigationViews.Summary>> list(
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            Page<InvestigationViews.Summary> result = queries.list(repositoryId,
                    Paging.of(page, size, Sort.unsorted()));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    @GetMapping("/{investigationId}")
    public Mono<InvestigationViews.Detail> detail(@PathVariable UUID repositoryId,
                                                  @PathVariable UUID investigationId) {
        return Blocking.deferOffload(() -> queries.detail(repositoryId, investigationId));
    }

    /**
     * Cancels an investigation.
     *
     * <p>Responds {@code 202} when the request was accepted and {@code 409} when the investigation
     * had already finished — a finished answer is a record, not something to be withdrawn.
     */
    @PostMapping("/{investigationId}/cancel")
    public Mono<ResponseEntity<Void>> cancel(@PathVariable UUID repositoryId,
                                             @PathVariable UUID investigationId) {
        return Blocking.deferOffload(() -> service.cancel(repositoryId, investigationId))
                .map(accepted -> accepted
                        ? ResponseEntity.accepted().build()
                        : ResponseEntity.status(HttpStatus.CONFLICT).build());
    }
}
