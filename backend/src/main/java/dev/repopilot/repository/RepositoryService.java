package dev.repopilot.repository;

import dev.repopilot.repository.sandbox.RepositorySandbox;
import dev.repopilot.shared.error.RepoPilotException;
import dev.repopilot.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository registration and lifecycle.
 *
 * <p>This is the only supported entry point for reading repository state from other
 * modules. Keeping it here means the canonical filesystem path and the sandbox
 * validation live behind one boundary rather than being reachable from anywhere that
 * holds a {@code RepositoryRepository}.
 *
 * <p>All filesystem validation is delegated to {@link RepositorySandbox}. This class
 * never calls {@code Path.of} on caller input directly, so the path-traversal and
 * symlink rules are enforced in exactly one audited place.
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

    private final RepositoryRepository repositories;
    private final RepositoryRevisionRepository revisions;
    private final WorkspaceRepository workspaces;
    private final RepositorySandbox sandbox;

    public RepositoryService(RepositoryRepository repositories,
                             RepositoryRevisionRepository revisions,
                             WorkspaceRepository workspaces,
                             RepositorySandbox sandbox) {
        this.repositories = repositories;
        this.revisions = revisions;
        this.workspaces = workspaces;
        this.sandbox = sandbox;
    }

    /**
     * Registers a repository from a filesystem path.
     *
     * @param userSuppliedPath absolute path, or a path relative to the primary workspace root
     */
    @Transactional
    public RepositorySummary register(UUID workspaceId, String requestedName, String userSuppliedPath) {
        if (workspaceId == null || workspaces.findById(workspaceId).isEmpty()) {
            throw RepoPilotException.notFound("Workspace", workspaceId);
        }

        RepositorySandbox.ImportTarget target = sandbox.validateImportTarget(userSuppliedPath);
        String name = deriveName(requestedName, target.canonicalPath());

        if (repositories.findByWorkspaceIdAndName(workspaceId, name).isPresent()) {
            throw RepoPilotException.conflict(
                    "A repository named '" + name + "' already exists in this workspace.");
        }

        Repository repository = new Repository(workspaceId, name, SourceType.LOCAL_PATH,
                target.canonicalPath().toString(), target.displayPath());
        repository.setSizeBytes(directorySize(target.canonicalPath()));
        try {
            // saveAndFlush returns the managed instance; use it rather than the local
            // reference so the summary always carries the generated id and timestamps.
            repository = repositories.saveAndFlush(repository);
        } catch (DataIntegrityViolationException e) {
            // A concurrent import of the same directory wins the unique constraint; the
            // caller is told to reload rather than being handed a half-created row.
            throw RepoPilotException.conflict(
                    "A repository named '" + name + "' already exists in this workspace.");
        }
        log.info("registered repository {} ({}) in workspace {}", repository.getId(), name, workspaceId);
        return RepositorySummary.from(repository);
    }

    @Transactional(readOnly = true)
    public Repository requireEntity(UUID repositoryId) {
        return repositories.findById(repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Repository", repositoryId));
    }

    @Transactional(readOnly = true)
    public RepositorySummary require(UUID repositoryId) {
        return RepositorySummary.from(requireEntity(repositoryId));
    }

    @Transactional(readOnly = true)
    public Page<RepositorySummary> list(UUID workspaceId, Pageable pageable) {
        return repositories.findByWorkspaceIdOrderByNameAsc(workspaceId, pageable)
                .map(RepositorySummary::from);
    }

    @Transactional(readOnly = true)
    public Optional<RepositorySummary> find(UUID repositoryId) {
        return repositories.findById(repositoryId).map(RepositorySummary::from);
    }

    /**
     * Resolves and re-validates the repository root immediately before use.
     *
     * <p>The recorded canonical path is re-checked against the sandbox on every call
     * rather than trusted, so a root that has since been replaced or symlinked away
     * cannot widen access for an already-registered repository.
     */
    @Transactional(readOnly = true)
    public Path resolveRoot(UUID repositoryId) {
        Repository repository = requireEntity(repositoryId);
        return sandbox.requireValidRoot(repository.getCanonicalLocation());
    }

    @Transactional
    public void startIndexing(UUID repositoryId) {
        Repository repository = requireEntity(repositoryId);
        repository.setStatus(RepositoryStatus.INDEXING);
        repository.setIndexingError(null);
    }

    /**
     * Marks the repository indexed and records how many files the revision holds.
     *
     * <p>{@code indexedFileCount} counts every file with a row — parsed cleanly, parsed with
     * problems, failed and recorded-without-an-analyzer alike — so it agrees with the
     * coverage view's {@code totalFiles}. Counting only the files that parsed cleanly would
     * make the inventory disagree with the coverage report about the same revision.
     */
    @Transactional
    public void completeIndexing(UUID repositoryId, int indexedFileCount, Instant at) {
        Repository repository = requireEntity(repositoryId);
        repository.setAnalyzedFileCount(indexedFileCount);
        repository.markIndexed(at);
    }

    @Transactional
    public void failIndexing(UUID repositoryId, String reason) {
        Repository repository = requireEntity(repositoryId);
        repository.markIndexingFailed(reason);
    }

    /**
     * Returns a repository to its pre-indexing state after a cancelled or timed-out
     * run. A repository that already had an indexed revision stays {@code READY},
     * because the earlier data is still queryable; one that never indexed returns to
     * {@code REGISTERED} rather than being reported as failed.
     */
    @Transactional
    public void abortIndexing(UUID repositoryId) {
        Repository repository = requireEntity(repositoryId);
        boolean hasRevision = revisions.findFirstByRepositoryIdOrderByIndexedAtDesc(repositoryId).isPresent();
        repository.setStatus(hasRevision ? RepositoryStatus.READY : RepositoryStatus.REGISTERED);
    }

    @Transactional
    public void delete(UUID repositoryId) {
        Repository repository = requireEntity(repositoryId);
        repositories.delete(repository);
        log.info("deleted repository {} and all dependent records", repositoryId);
    }

    // ------------------------------------------------------------------
    // Revisions
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<RepositoryRevision> findRevision(UUID repositoryId, String revision) {
        return revisions.findByRepositoryIdAndRevision(repositoryId, revision);
    }

    @Transactional(readOnly = true)
    public Optional<RepositoryRevision> latestRevision(UUID repositoryId) {
        return revisions.findFirstByRepositoryIdOrderByIndexedAtDesc(repositoryId);
    }

    @Transactional(readOnly = true)
    public List<RevisionLookup> listRevisions(UUID repositoryId) {
        return revisions.findLookupsByRepository(repositoryId);
    }

    @Transactional
    public RepositoryRevision saveRevision(RepositoryRevision revision) {
        return revisions.saveAndFlush(revision);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String deriveName(String requestedName, Path canonicalPath) {
        String name = requestedName;
        if (name == null || name.isBlank()) {
            name = RepositorySandbox.deriveRepositoryName(canonicalPath);
        } else {
            name = name.strip();
            if (name.length() > 300) {
                throw RepoPilotException.validation("Repository name must be at most 300 characters");
            }
        }
        return name;
    }

    /**
     * Bounded best-effort size of the top level of the repository. This avoids walking
     * an entire tree (which the indexing job does anyway) just to answer an import call.
     */
    private Long directorySize(Path root) {
        try (var stream = java.nio.file.Files.walk(root, 3)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return java.nio.file.Files.size(path);
                        } catch (java.io.IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (java.io.IOException e) {
            log.debug("unable to estimate size of repository root: {}", e.getMessage());
            return null;
        }
    }
}
