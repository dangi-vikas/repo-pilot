package dev.repopilot.api.v1;

import dev.repopilot.api.PageResponse;
import dev.repopilot.api.Paging;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.shared.reactive.Blocking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Background job inspection and cancellation. */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public Mono<PageResponse<JobView>> list(@RequestParam(required = false) JobStatus status,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return Blocking.deferOffload(() -> {
            Page<JobView> result = jobs.list(status,
                    Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))).map(JobView::from);
            return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                    result.getTotalElements());
        });
    }

    @GetMapping("/counters")
    public Mono<JobService.JobCounters> counters() {
        return Blocking.offload(jobs::counters);
    }

    @GetMapping("/{jobId}")
    public Mono<JobView> get(@PathVariable UUID jobId) {
        return Blocking.deferOffload(() -> JobView.from(jobs.require(jobId)));
    }

    /**
     * Requests cancellation. The response says only that the request was accepted; the
     * job reaches a terminal state at its next checkpoint, which is what the jobs list
     * shows.
     */
    @PostMapping("/{jobId}/cancel")
    public Mono<JobService.CancellationOutcome> cancel(@PathVariable UUID jobId) {
        return Blocking.deferOffload(() -> jobs.requestCancel(jobId));
    }
}
