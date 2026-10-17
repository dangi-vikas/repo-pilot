package dev.repopilot.api.v1;

import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.shared.reactive.Blocking;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Workspace and workspace-scoped repository endpoints. */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final RepositoryService repositories;

    public WorkspaceController(WorkspaceService workspaces, RepositoryService repositories) {
        this.workspaces = workspaces;
        this.repositories = repositories;
    }

    @GetMapping
    public Mono<List<WorkspaceSummary>> list() {
        return Blocking.offload(workspaces::listSummaries);
    }

    @GetMapping("/{workspaceId}")
    public Mono<WorkspaceSummary> get(@PathVariable UUID workspaceId) {
        return Blocking.deferOffload(() -> workspaces.require(workspaceId));
    }

    @PostMapping
    public Mono<ResponseEntity<WorkspaceSummary>> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return Blocking.deferOffload(() -> workspaces.create(request.name(), request.description()))
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @PatchMapping("/{workspaceId}")
    public Mono<WorkspaceSummary> update(@PathVariable UUID workspaceId,
                                         @Valid @RequestBody UpdateWorkspaceRequest request) {
        return Blocking.deferOffload(() -> workspaces.update(workspaceId, request.name(), request.description()));
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID workspaceId) {
        return Blocking.offloadVoid(() -> workspaces.delete(workspaceId));
    }

    // ------------------------------------------------------------------
    // Workspace-scoped repositories
    // ------------------------------------------------------------------

    @GetMapping("/{workspaceId}/repositories")
    public Mono<PageResponse<RepositorySummary>> listRepositories(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            Page<RepositorySummary> result = repositories.list(workspaceId,
                    Paging.of(page, size, Sort.by(Sort.Direction.ASC, "name")));
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    @PostMapping("/{workspaceId}/repositories")
    public Mono<ResponseEntity<RepositorySummary>> registerRepository(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody RegisterRepositoryRequest request) {
        return Blocking.deferOffload(() ->
                        repositories.register(workspaceId, request.name(), request.path()))
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }
}
