package dev.repopilot.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.ai.AiProviderRegistry;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.Repository;
import dev.repopilot.repository.RepositoryRepository;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryRevisionRepository;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates and cancels investigations.
 *
 * <h2>What is refused before anything is queued</h2>
 * A repository that does not exist, a build with no usable provider, a question that is only stop
 * words, and a repository with no indexed revision (there is no evidence to retrieve). Each is
 * answered with a specific message instead of a job that fails a second later for a reason the API
 * already knew.
 *
 * <p>The job-facing state transitions live in {@link InvestigationStore}, so that this class can
 * depend on {@link JobService} without putting the job handler in a bean cycle.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    /** Questions shorter than this cannot name anything; longer ones are refused at the API edge. */
    public static final int MIN_QUESTION_CHARS = 8;

    private final InvestigationRepository investigations;
    private final RepositoryRepository repositories;
    private final RepositoryRevisionRepository revisions;
    private final JobService jobs;
    private final AiProviderRegistry registry;
    private final InvestigationStore store;
    private final ObjectMapper json;

    public InvestigationService(InvestigationRepository investigations,
                                RepositoryRepository repositories,
                                RepositoryRevisionRepository revisions,
                                JobService jobs,
                                AiProviderRegistry registry,
                                InvestigationStore store,
                                ObjectMapper json) {
        this.investigations = investigations;
        this.repositories = repositories;
        this.revisions = revisions;
        this.jobs = jobs;
        this.registry = registry;
        this.store = store;
        this.json = json;
    }

    /** The identifiers a caller needs to follow the work. */
    public record Submission(UUID investigationId, UUID jobId, String status) {
    }

    @Transactional
    public Submission submit(UUID repositoryId, String question, String scope) {
        String trimmed = question == null ? "" : question.strip();
        if (trimmed.length() < MIN_QUESTION_CHARS) {
            throw RepoPilotException.validation("Ask a question of at least "
                    + MIN_QUESTION_CHARS + " characters: a shorter one does not name anything to look for.");
        }

        Repository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Repository", repositoryId));
        if (registry.active().isEmpty()) {
            // 503 with the registry's own explanation, which names the provider and the fix.
            // Checked before the revision, because "AI is disabled" is the more useful answer
            // than "index the repository first" when both are true.
            throw RepoPilotException.dependencyUnavailable(registry.unavailableMessage());
        }
        if (QuestionTokens.from(trimmed).isEmpty()) {
            throw RepoPilotException.validation("The question names nothing to search for: every "
                    + "word in it is a stop word. Name a class, a method, a file or a behaviour.");
        }
        RepositoryRevision revision = revisions.findFirstByRepositoryIdOrderByIndexedAtDesc(repositoryId)
                .orElseThrow(() -> RepoPilotException.conflict(
                        "This repository has no indexed revision yet, so there is no evidence to "
                                + "answer from. Index it first."));

        Investigation investigation = new Investigation(repositoryId, revision.getId(),
                titleFor(trimmed), trimmed, scope == null || scope.isBlank() ? null : scope.strip());
        Investigation saved = investigations.saveAndFlush(investigation);

        ObjectNode parameters = json.createObjectNode();
        parameters.put("investigationId", saved.getId().toString());
        JobService.Submission job = jobs.submit(JobType.INVESTIGATION, repository.getWorkspaceId(),
                repositoryId, parameters.toString(), "investigation:" + saved.getId());
        saved.setJobId(job.job().getId());

        log.info("queued investigation {} for repository {} (job {})",
                saved.getId(), repositoryId, job.job().getId());
        return new Submission(saved.getId(), job.job().getId(), saved.getStatus().name());
    }

    /**
     * Cancels an investigation: the job is asked to stop, and a queued investigation is marked
     * cancelled at once because a queued job may never reach a handler to do it.
     *
     * @return true when the request was accepted, false when the investigation had already finished
     */
    @Transactional
    public boolean cancel(UUID repositoryId, UUID investigationId) {
        Investigation investigation = investigations.findByIdAndRepositoryId(investigationId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Investigation", investigationId));
        if (investigation.getStatus() == InvestigationStatus.SUCCEEDED
                || investigation.getStatus() == InvestigationStatus.FAILED
                || investigation.getStatus() == InvestigationStatus.CANCELLED) {
            return false;
        }
        if (investigation.getJobId() != null) {
            jobs.requestCancel(investigation.getJobId());
        }
        if (investigation.getStatus() == InvestigationStatus.QUEUED) {
            store.markCancelledIfQueued(investigationId);
        }
        return true;
    }

    private static String titleFor(String question) {
        String title = question.replaceAll("\\s+", " ").strip();
        return title.length() <= 120 ? title : title.substring(0, 119) + "…";
    }
}
