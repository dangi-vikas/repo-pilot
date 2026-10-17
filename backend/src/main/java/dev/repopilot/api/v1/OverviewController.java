package dev.repopilot.api.v1;

import dev.repopilot.ai.AiProviderRegistry;
import dev.repopilot.ai.ModelAvailability;
import dev.repopilot.jobs.JobService;
import dev.repopilot.repository.RepositoryRepository;
import dev.repopilot.shared.reactive.Blocking;
import dev.repopilot.workspace.WorkspaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Cross-cutting counters for the overview screen.
 *
 * <p>The AI block is reported from a real availability probe (cached for 15s), not
 * assumed. When no provider is present the overview still returns, with
 * {@code ai.usable = false}, which is the state a first-run local installation is
 * expected to be in.
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final WorkspaceRepository workspaces;
    private final RepositoryRepository repositories;
    private final JobService jobs;
    private final AiProviderRegistry ai;

    public OverviewController(WorkspaceRepository workspaces,
                              RepositoryRepository repositories,
                              JobService jobs,
                              AiProviderRegistry ai) {
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.jobs = jobs;
        this.ai = ai;
    }

    @GetMapping
    public Mono<OverviewView> overview(@RequestParam(required = false, defaultValue = "false")
                                       boolean refreshAi) {
        Mono<OverviewView> counts = Blocking.offload(() -> {
            JobService.JobCounters counters = jobs.counters();
            return new OverviewView(
                    workspaces.count(),
                    repositories.countAll(),
                    counters.active(),
                    counters.queued(),
                    counters.running(),
                    counters.succeeded(),
                    counters.failed(),
                    counters.cancelled(),
                    null);
        });

        return Mono.zip(counts, ai.availability(refreshAi))
                .map(tuple -> {
                    OverviewView base = tuple.getT1();
                    ModelAvailability availability = tuple.getT2();
                    return new OverviewView(
                            base.workspaceCount(), base.repositoryCount(), base.activeJobCount(),
                            base.queuedJobCount(), base.runningJobCount(), base.succeededJobCount(),
                            base.failedJobCount(), base.cancelledJobCount(),
                            new OverviewView.AiStatus(
                                    ai.enabled(),
                                    ai.activeProviderId(),
                                    availability.reachable(),
                                    availability.usable(),
                                    ai.isEmbeddingsEnabled(),
                                    availability.message(),
                                    ai.availableProviderIds()));
                });
    }
}
