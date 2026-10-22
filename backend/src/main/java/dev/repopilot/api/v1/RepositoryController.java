package dev.repopilot.api.v1;

import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.codeindex.query.CodeIndexQueryService;
import dev.repopilot.codeindex.query.CoverageView;
import dev.repopilot.codeindex.query.EdgeView;
import dev.repopilot.codeindex.query.GraphView;
import dev.repopilot.codeindex.query.MappingView;
import dev.repopilot.codeindex.query.SourceContentView;
import dev.repopilot.codeindex.query.SourceFileReader;
import dev.repopilot.codeindex.query.SourceFileView;
import dev.repopilot.codeindex.query.SymbolView;
import dev.repopilot.codeindex.query.UniverseView;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositoryStatus;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.repository.RevisionLookup;
import dev.repopilot.runtime.RuntimeService;
import dev.repopilot.shared.error.RepoPilotException;
import dev.repopilot.shared.reactive.Blocking;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Repository lifecycle and code-intelligence endpoints.
 *
 * <p>Every handler bridges a blocking service call onto the bounded elastic scheduler
 * via {@link Blocking}; nothing here performs database or filesystem work on the
 * request thread.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositories;
    private final JobService jobs;
    private final CodeIndexQueryService codeIndex;
    private final SourceFileReader sourceReader;
    private final RuntimeService runtime;

    public RepositoryController(RepositoryService repositories,
                                JobService jobs,
                                CodeIndexQueryService codeIndex,
                                SourceFileReader sourceReader,
                                RuntimeService runtime) {
        this.repositories = repositories;
        this.jobs = jobs;
        this.codeIndex = codeIndex;
        this.sourceReader = sourceReader;
        this.runtime = runtime;
    }

    @GetMapping("/{repositoryId}")
    public Mono<RepositorySummary> get(@PathVariable UUID repositoryId) {
        return Blocking.deferOffload(() -> repositories.require(repositoryId));
    }

    @DeleteMapping("/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID repositoryId) {
        return Blocking.offloadVoid(() -> {
            // The database delete commits when it returns; the recorded bytes are removed after,
            // because filesystem work must not run inside a transaction (ADR 0006). A heap dump
            // that outlived its repository would otherwise sit on disk indefinitely.
            repositories.delete(repositoryId);
            runtime.discardArtifacts(repositoryId);
        });
    }

    /** Submits an indexing job. Returns immediately with the queued job. */
    @PostMapping("/{repositoryId}/index")
    public Mono<ResponseEntity<JobView>> index(@PathVariable UUID repositoryId,
                                               @Valid @RequestBody(required = false) SubmitIndexRequest request) {
        return Blocking.deferOffload(() -> {
            RepositorySummary repository = repositories.require(repositoryId);
            if (repository.status() == RepositoryStatus.INDEXING) {
                throw RepoPilotException.conflict("An indexing job is already running for this repository");
            }
            String key = request == null ? null : request.idempotencyKey();
            JobService.Submission submission = jobs.submit(JobType.REPOSITORY_INDEX,
                    repository.workspaceId(), repositoryId, null, key);
            return submission.job();
        }).map(job -> ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.from(job)));
    }

    @GetMapping("/{repositoryId}/revisions")
    public Mono<List<RevisionLookup>> revisions(@PathVariable UUID repositoryId) {
        return Blocking.deferOffload(() -> repositories.listRevisions(repositoryId));
    }

    @GetMapping("/{repositoryId}/coverage")
    public Mono<CoverageView> coverage(@PathVariable UUID repositoryId,
                                       @RequestParam(required = false) UUID revisionId) {
        return Blocking.deferOffload(() -> codeIndex.coverage(resolveRevisionId(repositoryId, revisionId)));
    }

    // ------------------------------------------------------------------
    // Code explorer
    // ------------------------------------------------------------------

    @GetMapping("/{repositoryId}/files")
    public Mono<PageResponse<SourceFileView>> files(@PathVariable UUID repositoryId,
                                                    @RequestParam(required = false) UUID revisionId,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            UUID resolved = resolveRevisionId(repositoryId, revisionId);
            Page<SourceFileView> result = codeIndex.listFiles(resolved,
                    Paging.of(page, size, Sort.by(Sort.Direction.ASC, "relativePath")));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    @GetMapping("/{repositoryId}/files/by-path")
    public Mono<SourceFileView> fileByPath(@PathVariable UUID repositoryId,
                                           @RequestParam String path,
                                           @RequestParam(required = false) UUID revisionId) {
        return Blocking.deferOffload(() -> {
            UUID resolved = resolveRevisionId(repositoryId, revisionId);
            return codeIndex.fileByPath(resolved, path)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No indexed file at " + path));
        });
    }

    @GetMapping("/{repositoryId}/files/content")
    public Mono<SourceContentView> fileContent(@PathVariable UUID repositoryId,
                                               @RequestParam String path,
                                               @RequestParam(required = false) UUID revisionId) {
        return Blocking.deferOffload(() -> sourceReader.read(repositoryId,
                resolveRevisionId(repositoryId, revisionId), path));
    }

    @GetMapping("/{repositoryId}/files/{sourceFileId}/symbols")
    public Mono<List<SymbolView>> fileSymbols(@PathVariable UUID repositoryId,
                                              @PathVariable UUID sourceFileId) {
        return Blocking.deferOffload(() -> codeIndex.symbolsForFile(sourceFileId));
    }

    @GetMapping("/{repositoryId}/symbols")
    public Mono<PageResponse<SymbolView>> searchSymbols(@PathVariable UUID repositoryId,
                                                        @RequestParam(name = "q", required = false) String query,
                                                        @RequestParam(required = false) UUID revisionId,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            UUID resolved = resolveRevisionId(repositoryId, revisionId);
            Page<SymbolView> result = codeIndex.searchSymbols(resolved, query,
                    Paging.of(page, size, Sort.by(Sort.Direction.ASC, "qualifiedName")));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    @GetMapping("/{repositoryId}/symbols/{symbolId}/references")
    public Mono<List<EdgeView>> symbolReferences(@PathVariable UUID repositoryId,
                                                 @PathVariable UUID symbolId) {
        return Blocking.deferOffload(() -> codeIndex.edgesForSymbol(symbolId));
    }

    @GetMapping("/{repositoryId}/mappings")
    public Mono<List<MappingView>> mappings(@PathVariable UUID repositoryId,
                                            @RequestParam(required = false) MappingType type,
                                            @RequestParam(required = false) UUID revisionId) {
        return Blocking.deferOffload(() ->
                codeIndex.mappings(resolveRevisionId(repositoryId, revisionId), type));
    }

    @GetMapping("/{repositoryId}/graph")
    public Mono<GraphView> graph(@PathVariable UUID repositoryId,
                                 @RequestParam(required = false) String types,
                                 @RequestParam(required = false) UUID revisionId,
                                 @RequestParam(required = false) Integer limit) {
        return Blocking.deferOffload(() -> codeIndex.graphEdges(
                resolveRevisionId(repositoryId, revisionId), parseEdgeTypes(types), limit));
    }

    /**
     * The renderable code universe: nodes plus the edges between them.
     *
     * <p>Separate from {@code /graph} rather than a shape flag on it. The two endpoints answer
     * different questions and have different limits — one pages edge rows, the other returns a
     * drawable slice — and folding them together would make both contracts harder to state.
     */
    @GetMapping("/{repositoryId}/universe")
    public Mono<UniverseView> universe(@PathVariable UUID repositoryId,
                                       @RequestParam(required = false) String types,
                                       @RequestParam(required = false) UUID revisionId,
                                       @RequestParam(required = false) Integer limit) {
        return Blocking.deferOffload(() -> codeIndex.universe(
                resolveRevisionId(repositoryId, revisionId), parseEdgeTypes(types), limit));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID resolveRevisionId(UUID repositoryId, UUID requested) {
        if (requested != null) {
            return requested;
        }
        return repositories.latestRevision(repositoryId)
                .map(RepositoryRevision::getId)
                .orElseThrow(() -> new RepoPilotException(
                        dev.repopilot.shared.error.ErrorCode.CONFLICT,
                        "This repository has not been indexed yet. Run an index job first."));
    }

    private static List<EdgeType> parseEdgeTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return EdgeType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw RepoPilotException.validation("Unknown edge type '" + value + "'");
                    }
                })
                .toList();
    }
}
