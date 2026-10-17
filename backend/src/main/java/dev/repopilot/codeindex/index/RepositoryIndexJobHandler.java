package dev.repopilot.codeindex.index;

import dev.repopilot.jobs.JobAbortedException;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The {@code REPOSITORY_INDEX} job handler.
 *
 * <p>Thin by design: it owns the repository's lifecycle status transition and the
 * error-to-status mapping, and delegates all indexing work to {@link RepositoryIndexer}.
 * The status transitions live here because the indexer must not know about the job
 * system, and the handler must not know about parsing.
 */
@Component
public class RepositoryIndexJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIndexJobHandler.class);

    private final RepositoryIndexer indexer;
    private final RepositoryService repositories;

    public RepositoryIndexJobHandler(RepositoryIndexer indexer, RepositoryService repositories) {
        this.indexer = indexer;
        this.repositories = repositories;
    }

    @Override
    public JobType jobType() {
        return JobType.REPOSITORY_INDEX;
    }

    @Override
    public JobResult execute(JobContext context) {
        UUID repositoryId = context.repositoryId();
        if (repositoryId == null) {
            // A programming error at submission time, reported as unsupported input rather
            // than an internal error so the caller can correct the request.
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "A REPOSITORY_INDEX job requires a repository id");
        }

        repositories.startIndexing(repositoryId);
        try {
            IndexOutcome outcome = indexer.index(repositoryId, context);
            return JobResult.of(outcome.summary());
        } catch (JobAbortedException e) {
            // Cancellation and timeout are not failures of the repository. Return it to a
            // truthful state and let the dispatcher record the job outcome.
            repositories.abortIndexing(repositoryId);
            throw e;
        } catch (RuntimeException e) {
            log.warn("indexing repository {} failed: {}", repositoryId, e.toString());
            repositories.failIndexing(repositoryId, describe(e));
            throw e;
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 900 ? single.substring(0, 900) + "…" : single;
    }
}
