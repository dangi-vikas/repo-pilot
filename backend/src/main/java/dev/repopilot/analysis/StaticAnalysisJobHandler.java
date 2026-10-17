package dev.repopilot.analysis;

import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The {@code STATIC_ANALYSIS} job handler.
 *
 * <p>As thin as the indexing handler, and for the same reason: the job system owns lifecycle
 * and the runner owns analysis. The only decision here is how an unexpected failure is
 * reported — the runner has already closed its run row at that point, so this handler adds
 * nothing to the evidence and only lets the exception reach the dispatcher.
 */
@Component
public class StaticAnalysisJobHandler implements JobHandler {

    private final AnalysisRunner runner;

    public StaticAnalysisJobHandler(AnalysisRunner runner) {
        this.runner = runner;
    }

    @Override
    public JobType jobType() {
        return JobType.STATIC_ANALYSIS;
    }

    @Override
    public JobResult execute(JobContext context) {
        UUID repositoryId = context.repositoryId();
        if (repositoryId == null) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A STATIC_ANALYSIS job requires a repository id");
        }
        AnalysisOutcome outcome = runner.analyse(repositoryId, context.jobId(), context);
        return JobResult.of(outcome.summary(), outcome.runId());
    }
}
