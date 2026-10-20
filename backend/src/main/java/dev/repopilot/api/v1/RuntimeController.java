package dev.repopilot.api.v1;

import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.jobs.JobService;
import dev.repopilot.runtime.MetricKind;
import dev.repopilot.runtime.RuntimeService;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.runtime.query.RuntimeMeasurementFilter;
import dev.repopilot.runtime.query.RuntimeMeasurementView;
import dev.repopilot.runtime.query.RuntimeQueryService;
import dev.repopilot.runtime.query.RuntimeSummaryView;
import dev.repopilot.runtime.retention.ArtifactRetentionService;
import dev.repopilot.shared.reactive.Blocking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Runtime evidence endpoints: upload a recording, analyse it, read what it measured.
 *
 * <p>Three steps rather than one, and deliberately so. Uploading and analysing are separate
 * requests because a recording is evidence that outlives the run that read it: an operator can
 * upload a set of recordings now and analyse them later, re-analyse a recording after a reader
 * fix, and see the artefact list whether or not a job ever succeeded. Collapsing the two into one
 * request would make "the upload failed" and "the analysis failed" the same answer.
 *
 * <p>The measurements endpoint is always scoped to a run. A client cannot ask for measurements
 * across runs or across repositories, because the answer would be a number describing nothing in
 * particular.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RuntimeController {

    private final RuntimeService runtime;
    private final RuntimeQueryService queries;
    private final ArtifactRetentionService retention;

    public RuntimeController(RuntimeService runtime, RuntimeQueryService queries,
                             ArtifactRetentionService retention) {
        this.runtime = runtime;
        this.queries = queries;
        this.retention = retention;
    }

    /** Request body for queueing an artefact's analysis. */
    public record SubmitArtifactAnalysisRequest(
            @Size(max = 200, message = "idempotencyKey must be at most 200 characters")
            String idempotencyKey
    ) {
    }

    /**
     * Request body for queueing a retention pass.
     *
     * @param olderThanDays apply this window instead of the configured one; absent means the
     *                      configured window, and zero is refused rather than read as "everything"
     */
    public record PurgeRetentionRequest(
            Integer olderThanDays,
            @Size(max = 200, message = "idempotencyKey must be at most 200 characters")
            String idempotencyKey
    ) {
    }

    /**
     * Uploads a runtime artefact.
     *
     * <p>Streamed to disk, never buffered: the part is written to a scratch file, hashed, and moved
     * into content-addressed storage. Peak memory is one hash buffer regardless of the file's size,
     * which matters because the artefacts this endpoint exists for are heap dumps and recordings
     * that can be larger than the JVM's own heap.
     *
     * @param artifactType optional; when absent the type is inferred from the filename extension
     */
    @PostMapping(value = "/{repositoryId}/runtime/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<RuntimeArtifactView>> uploadArtifact(@PathVariable UUID repositoryId,
                                                                    @RequestPart("file") FilePart file,
                                                                    @RequestParam(required = false) String artifactType) {
        return Blocking.deferOffload(() -> runtime.stage(repositoryId, file.filename(), artifactType))
                .flatMap(staged -> file.transferTo(staged.temporaryFile())
                        .thenReturn(staged)
                        // A part that never finishes streaming still reserved a scratch file. The
                        // framework can abort it (the per-part disk cap) as easily as a client can
                        // disconnect, and in both cases the file would otherwise sit there forever.
                        .onErrorResume(error -> {
                            runtime.discard(staged);
                            return Mono.error(error);
                        }))
                .flatMap(staged -> Blocking.deferOffload(() -> runtime.adopt(staged)))
                .map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view));
    }

    /** Artefacts uploaded for a repository, newest first. */
    @GetMapping("/{repositoryId}/runtime/artifacts")
    public Mono<PageResponse<RuntimeArtifactView>> artifacts(@PathVariable UUID repositoryId,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            Page<RuntimeArtifactView> result = queries.artifacts(repositoryId, Paging.of(page, size, Sort.unsorted()));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    /** One artefact, including whether this build can parse it. */
    @GetMapping("/{repositoryId}/runtime/artifacts/{artifactId}")
    public Mono<RuntimeArtifactView> artifact(@PathVariable UUID repositoryId, @PathVariable UUID artifactId) {
        return Blocking.deferOffload(() -> queries.artifact(repositoryId, artifactId));
    }

    /**
     * Queues the analysis of an uploaded artefact.
     *
     * <p>Accepted, not performed: the work runs as a durable job with its own lifecycle,
     * cancellation and retry semantics, and the response is the job to poll.
     */
    @PostMapping("/{repositoryId}/runtime/artifacts/{artifactId}/analyze")
    public Mono<ResponseEntity<JobView>> analyzeArtifact(@PathVariable UUID repositoryId,
                                                         @PathVariable UUID artifactId,
                                                         @Valid @RequestBody(required = false)
                                                         SubmitArtifactAnalysisRequest request) {
        return Blocking.deferOffload(() -> {
            String key = request == null ? null : request.idempotencyKey();
            JobService.Submission submission = runtime.submitAnalysis(repositoryId, artifactId, key);
            return JobView.from(submission.job());
        }).map(view -> ResponseEntity.status(HttpStatus.ACCEPTED).body(view));
    }

    /**
     * A page of measurements from one run.
     *
     * <p>Ordered by metric key, then scope, then reduction. A caller may narrow by key prefix,
     * kind or scope fragment, and may name a specific run from the history.
     */
    @GetMapping("/{repositoryId}/runtime/measurements")
    public Mono<PageResponse<RuntimeMeasurementView>> measurements(@PathVariable UUID repositoryId,
                                                                   @RequestParam(required = false) UUID runId,
                                                                   @RequestParam(required = false) String metric,
                                                                   @RequestParam(required = false) MetricKind kind,
                                                                   @RequestParam(required = false) String scope,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            RuntimeMeasurementFilter filter = new RuntimeMeasurementFilter(runId, metric, kind, scope);
            Page<RuntimeMeasurementView> result = queries.measurements(repositoryId, filter,
                    Paging.of(page, size, Sort.unsorted()));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    /**
     * The run header and breakdown for a repository's runtime evidence.
     *
     * <p>Zero counts and no run when no recording has been analysed. That is a state with its own
     * meaning, not a 404: the caller asked what has been measured, and the honest answer is
     * "nothing yet".
     */
    @GetMapping("/{repositoryId}/runtime/summary")
    public Mono<RuntimeSummaryView> summary(@PathVariable UUID repositoryId,
                                            @RequestParam(required = false) UUID runId) {
        return Blocking.deferOffload(() -> queries.summary(repositoryId, runId));
    }

    /**
     * What artefact retention holds for a repository, and what a pass would remove now.
     *
     * <p>A read, not a dry run: the plan is computed against the current clock every time it is asked
     * for, so there is nothing to schedule and nothing that can go stale. The candidate list states, per
     * file, how many measurements would survive it, because the answer to "may I delete this" depends on
     * that number and not on the bytes.
     *
     * @param days preview this window instead of the configured one; absent means the configured window,
     *             and zero means "pruning is off", which reports the repository and names no candidate
     */
    @GetMapping("/{repositoryId}/runtime/retention")
    public Mono<ArtifactRetentionService.RetentionPlan> retention(@PathVariable UUID repositoryId,
                                                                 @RequestParam(required = false) Integer days) {
        return Blocking.deferOffload(() -> days == null ? retention.plan(repositoryId) : retention.plan(repositoryId, days));
    }

    /**
     * Queues a retention pass.
     *
     * <p>Accepted, not performed, for the same reason analysis is: the work is durable, cancellable and
     * visible in the job list, and a request that deleted files synchronously would have to hold a
     * connection open while it did.
     */
    @PostMapping("/{repositoryId}/runtime/retention/purge")
    public Mono<ResponseEntity<JobView>> purge(@PathVariable UUID repositoryId,
                                               @Valid @RequestBody(required = false) PurgeRetentionRequest request) {
        return Blocking.deferOffload(() -> {
            Integer days = request == null ? null : request.olderThanDays();
            String key = request == null ? null : request.idempotencyKey();
            JobService.Submission submission = runtime.submitRetention(repositoryId, days, key);
            return JobView.from(submission.job());
        }).map(view -> ResponseEntity.status(HttpStatus.ACCEPTED).body(view));
    }
}
